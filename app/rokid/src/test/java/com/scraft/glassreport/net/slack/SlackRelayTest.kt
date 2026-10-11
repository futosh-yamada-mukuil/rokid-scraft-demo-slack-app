package com.scraft.glassreport.net.slack

import com.scraft.glassreport.data.prefs.InMemoryTokenStore
import com.scraft.glassreport.data.prefs.Tokens
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SlackRelayTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryTokenStore()
    private lateinit var auth: SlackAuth
    private lateinit var client: SlackRelayClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        auth = SlackAuth(store, server.url("/").toString(), deviceLabel = "Rokid_Test")
        client = SlackRelayClient(auth)
    }

    @After
    fun close() = server.shutdown()

    private fun connected() = store.save(Tokens("device-secret", auth.relayUrl, Long.MAX_VALUE))

    @Test
    fun pairingUsesRelayCodeAndStoresOnlyDeviceCredential() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """{"user_code":"ABCD1234","device_code":"pair-secret","verification_uri":"http://localhost:8787/slack/pair","interval":2,"expires_in":600}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
            server.enqueue(MockResponse().setBody("""{"status":"connected","access_token":"device-only"}"""))
            val code = auth.requestDeviceCode().getOrThrow()
            assertEquals("ABCD1234", code.userCode)
            assertTrue(
                server
                    .takeRequest()
                    .body
                    .readUtf8()
                    .contains("Rokid_Test"),
            )
            auth.awaitSignIn(code).getOrThrow()
            assertEquals("device-only", auth.accessToken())
            assertTrue(auth.signedIn())
            assertEquals(auth.relayUrl, store.load()?.refreshToken)
        }

    @Test
    fun differentRelayCannotReuseSavedCredential() =
        runTest {
            connected()
            val other = SlackAuth(store, "https://different.example")
            assertFalse(other.signedIn())
            assertNull(other.accessToken())
            assertFalse(SlackAuth(store, "http://192.168.1.2:8787").configured)
            assertFalse(SlackAuth(store, "https://user:secret@example.com").configured)
        }

    @Test
    fun feedPreservesAuthorImageCountAndRemoteOwnership() =
        runTest {
            connected()
            server.enqueue(
                MockResponse().setBody(
                    """{"posts":[{"id":"C1/1000.1","author":"PC担当","text":"確認しました","createdAtMs":1000100,"attachmentCount":1,"imageCount":1,"isOwn":false}]}""",
                ),
            )
            val post = client.latest().getOrThrow().single()
            assertEquals("PC担当", post.author)
            assertEquals("確認しました", post.text)
            assertEquals(1, post.imageCount)
            assertEquals(false, post.isOwn)
            val request = server.takeRequest()
            assertEquals("/api/slack/feed", request.path)
            assertEquals("Bearer device-secret", request.getHeader("Authorization"))
        }

    @Test
    fun unauthorizedFeedClearsCredentialAndRequestsPairing() =
        runTest {
            connected()
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"register"}"""))
            assertTrue(client.latest().exceptionOrNull() is SignInRequiredException)
            assertNull(store.load())
        }

    @Test
    fun retryReusesMultipartRequestIdUntilSendSucceeds() =
        runTest {
            connected()
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"少し待ってください"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"fileId":"F1"}"""))
            val bytes = byteArrayOf(-1, -40, -1)
            assertTrue(client.send(bytes, "image/jpeg", "設備確認", null).isFailure)
            assertTrue(client.send(bytes, "image/jpeg", "設備確認", null).isSuccess)
            val first = server.takeRequest().body.readUtf8()
            val second = server.takeRequest().body.readUtf8()
            val pattern = Regex("name=\"requestId\"\\r\\n.*?\\r\\n\\r\\n([^\\r]+)", RegexOption.DOT_MATCHES_ALL)
            val firstId = pattern.find(first)?.groupValues?.get(1)
            assertFalse(firstId.isNullOrBlank())
            assertEquals(firstId, pattern.find(second)?.groupValues?.get(1))
            assertTrue(first.contains("設備確認"))
            assertTrue(first.contains("name=\"photo\""))
            assertFalse(client.accepts("video/mp4"))
        }

    private fun feed(
        version: String,
        text: String = "確認しました",
    ) = MockResponse().setBody(
        """{"posts":[{"id":"C1/1000.1","author":"PC担当","text":"$text","createdAtMs":1000100}],"version":"$version"}""",
    )

    @Test
    fun relayChangeIsAnnouncedByWaitingOnTheVersionOnScreen() =
        runTest {
            connected()
            server.enqueue(feed("v1"))
            // 変化なしで保留が明けた応答では知らせず、もう一度待つ。
            server.enqueue(feed("v1"))
            server.enqueue(feed("v2", "追加で撮影してください"))

            client.latest().getOrThrow()
            assertEquals("/api/slack/feed", server.takeRequest().path)
            client.updates.first()

            val waited = server.takeRequest()
            assertEquals("/api/slack/feed?since=v1&wait=25", waited.path)
            assertEquals("Bearer device-secret", waited.getHeader("Authorization"))
            assertEquals("/api/slack/feed?since=v1&wait=25", server.takeRequest().path)
            // 知らせた後は新しい版で待ち直す。その要求が出る前に購読を終えることもある。
            assertTrue(server.requestCount in 3..4)
        }

    @Test
    fun nothingWaitsUntilTheFirstFeedIsShownOrWhenTheRelayHasNoVersions() =
        runTest {
            connected()
            // 版を返さない旧中継サーバ。待つ相手が無いので取得は定期更新だけになる。
            server.enqueue(MockResponse().setBody("""{"posts":[]}"""))
            client.latest().getOrThrow()

            val waiting = launch { client.updates.first() }
            testScheduler.advanceTimeBy(120_000)
            assertEquals(1, server.requestCount)
            waiting.cancel()
        }

    @Test
    fun revokedDeviceIsAnnouncedOnceAndWaitingStopsUntilItIsPairedAgain() =
        runTest {
            connected()
            server.enqueue(feed("v1"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"register"}"""))
            client.latest().getOrThrow()

            client.updates.first()
            assertNull(store.load())
            assertTrue(client.latest().exceptionOrNull() is SignInRequiredException)

            val waiting = launch { client.updates.first() }
            testScheduler.advanceTimeBy(120_000)
            assertEquals(2, server.requestCount)
            waiting.cancel()
        }

    @Test
    fun connectionLossWhileWaitingRetriesInsteadOfEndingUpdates() =
        runTest {
            connected()
            server.enqueue(feed("v1"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"Slackの利用制限です"}"""))
            server.enqueue(feed("v2"))
            client.latest().getOrThrow()

            client.updates.first()

            assertTrue(server.requestCount >= 4)
            assertEquals("device-secret", store.load()?.accessToken)
        }

    @Test
    fun leavingTheScreenClosesTheWaitingRequest() =
        runTest {
            connected()
            server.enqueue(feed("v1"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            client.latest().getOrThrow()
            server.takeRequest()

            val waiting = async(Dispatchers.IO) { client.updates.first() }
            withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            waiting.cancel()
            // 25秒の保留が明けるのを待たずに終わる。
            withContext(Dispatchers.IO) { withTimeout(5_000) { waiting.join() } }
            assertTrue(waiting.isCancelled)
        }

    @Test
    fun sendingAPhotoAnnouncesAnUpdateSoTheLobbyShowsItAtOnce() =
        runTest {
            connected()
            server.enqueue(MockResponse().setBody("""{"ok":true,"fileId":"F1"}"""))
            val announced = async { client.updates.first() }
            testScheduler.runCurrent()

            assertTrue(client.send(byteArrayOf(-1, -40, -1), "image/jpeg", "設備確認", null).isSuccess)

            announced.await()
        }

    @Test
    fun malformedFeedIsReportedAndCancellationPropagates() =
        runTest {
            connected()
            server.enqueue(MockResponse().setBody("""{"posts":[{"text":"missing id"}]}"""))
            assertTrue(client.latest().isFailure)
            var cancelled = false
            try {
                pairingResult<Unit> { throw CancellationException("leave auth") }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }
}
