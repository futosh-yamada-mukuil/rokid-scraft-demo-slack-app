package com.scraft.glassreport.net.slack

import com.scraft.glassreport.data.prefs.InMemoryTokenStore
import com.scraft.glassreport.data.prefs.Tokens
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
