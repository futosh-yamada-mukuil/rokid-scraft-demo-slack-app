package com.scraft.glassreport.net.teams

import com.scraft.glassreport.data.prefs.InMemoryTokenStore
import com.scraft.glassreport.data.prefs.Tokens
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MIME_MP4
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * 投稿の HTTP 契約を MockWebServer で確認する。
 * **お客様の Teams チャネルへは一切投稿しない**（実投稿は文言確認後に人が 1 度だけ行う）。
 */
class TeamsClientTest {
    /** 実データと同じ形の応答（必要なキーだけ）。 */
    private val postedJson =
        """
        {"@odata.context":"https://graph.microsoft.com/v1.0/${'$'}metadata#Microsoft.Graph.chatMessage",
         "id":"1755000000000","messageType":"message",
         "webUrl":"https://teams.microsoft.com/l/message/19%3Aabc%40thread.tacv2/1755000000000"}
        """.trimIndent()

    private lateinit var server: MockWebServer
    private val clock = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** login と graph を 1 つの MockWebServer で兼ねる（パスが別なので取り違えない）。 */
    private fun client(store: InMemoryTokenStore = signedInStore()) =
        TeamsClient(
            auth =
                TeamsAuth(
                    tokens = store,
                    tenantId = "tenant-1",
                    clientId = "client-1",
                    loginBase = server.url("/").toString(),
                    now = { clock },
                ),
            teamId = "team-1",
            channelId = "19:abc@thread.tacv2",
            graphBase = server.url("/v1.0").toString(),
        )

    private fun signedInStore() = InMemoryTokenStore(Tokens("at-1", "rt-1", expiresAt = clock + 600_000L))

    @Test
    fun photoPost_embedsImageInHostedContentsAndReferencesItRelatively() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201).setBody(postedJson))
            val jpeg = byteArrayOf(1, 2, 3, 4, 5)

            val posted = client().postMessage("配管に漏れ", jpeg).getOrThrow()
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("POST", request.method)
            assertEquals("/v1.0/teams/team-1/channels/19%3Aabc%40thread.tacv2/messages", request.path)
            assertEquals("Bearer at-1", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
            assertTrue(body.contains("\"contentType\":\"html\""))
            assertTrue(body.contains("<div>配管に漏れ</div>"))
            // 相対参照。投稿後に Graph 側が絶対 URL に書き換える。
            assertTrue(body.contains("<img src=\\\"../hostedContents/1/\$value\\\""))
            assertTrue(body.contains("\"@microsoft.graph.temporaryId\":\"1\""))
            assertTrue(body.contains("\"contentBytes\":\"${Base64.getEncoder().encodeToString(jpeg)}\""))
            assertTrue(body.contains("\"contentType\":\"image/jpeg\""))
            assertEquals("1755000000000", posted.messageId)
            assertTrue(posted.webUrl.startsWith("https://teams.microsoft.com/l/message/"))
        }

    @Test
    fun textOnlyPost_hasNoHostedContents() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201).setBody(postedJson))

            client().postMessage("テキストのみ").getOrThrow()
            val body = server.takeRequest().body.readUtf8()

            assertFalse(body.contains("hostedContents"))
            assertFalse(body.contains("<img"))
            assertTrue(body.contains("<div>テキストのみ</div>"))
        }

    @Test
    fun blankComment_sendsOnlyImageHtml() =
        runTest {
            for (comment in listOf("", " \n\t ")) {
                server.enqueue(MockResponse().setResponseCode(201).setBody(postedJson))
                client().postMessage(comment, byteArrayOf(9)).getOrThrow()
                val payload = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(
                    "<div><img src=\"../hostedContents/1/\$value\" style=\"max-width:600px\" alt=\"添付画像\"></img></div>",
                    payload.getJSONObject("body").getString("content"),
                )
                assertEquals("CQ==", payload.getJSONArray("hostedContents").getJSONObject(0).getString("contentBytes"))
            }
        }

    @Test
    fun emptyPost_failsLocallyWithMissingOrEmptyImage() =
        runTest {
            for (photo in listOf(null, byteArrayOf())) {
                for (comment in listOf("", " \n\t ")) {
                    server.enqueue(MockResponse().setResponseCode(201).setBody(postedJson))
                    val result = client().postMessage(comment, photo)
                    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                    assertEquals("送信する本文または画像がありません", result.exceptionOrNull()?.message)
                }
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun emptyImageWithText_sendsEscapedTextWithoutHostedContents() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201).setBody(postedJson))
            client().postMessage("  <確認> & 連絡  ", byteArrayOf()).getOrThrow()
            val payload = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("<div>&lt;確認&gt; &amp; 連絡</div>", payload.getJSONObject("body").getString("content"))
            assertFalse(payload.has("hostedContents"))
        }

    @Test
    fun comment_isHtmlEscapedSoTheBodyCannotBreak() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201).setBody(postedJson))

            client().postMessage("<b>圧力</b> & \"温度\"").getOrThrow()
            val body = server.takeRequest().body.readUtf8()

            assertTrue(body.contains("&lt;b&gt;圧力&lt;/b&gt; &amp; \\\"温度\\\""))
        }

    @Test
    fun unauthorized_refreshesTokenOnceAndRetries() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"InvalidAuthenticationToken"}}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"at-2","expires_in":3600}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody(postedJson))
            val store = signedInStore()

            val posted = client(store).postMessage("再試行", byteArrayOf(1))

            assertTrue(posted.isSuccess)
            assertEquals(3, server.requestCount)
            assertEquals("Bearer at-1", server.takeRequest().getHeader("Authorization"))
            assertTrue(
                server
                    .takeRequest()
                    .body
                    .readUtf8()
                    .contains("grant_type=refresh_token"),
            )
            assertEquals("Bearer at-2", server.takeRequest().getHeader("Authorization"))
            assertEquals("at-2", store.load()?.accessToken)
        }

    @Test
    fun unauthorized_withoutRefreshTokenAsksForSignIn() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"InvalidAuthenticationToken"}}"""))
            val store = InMemoryTokenStore(Tokens("at-1", refreshToken = null, expiresAt = clock + 600_000L))

            val result = client(store).postMessage("再試行", byteArrayOf(1))

            assertTrue(result.exceptionOrNull() is SignInRequiredException)
        }

    @Test
    fun missingTokens_askForSignInWithoutCallingGraph() =
        runTest {
            val result = client(InMemoryTokenStore()).postMessage("未サインイン", byteArrayOf(1))

            assertTrue(result.exceptionOrNull() is SignInRequiredException)
            assertEquals("Teams 未サインイン", result.exceptionOrNull()?.message)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun graphError_isReturnedWithStatusAndMessage() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"code":"Forbidden","message":"Message POST is allowed in application-only context"}}""",
                ),
            )

            val result = client().postMessage("失敗", byteArrayOf(1))

            assertTrue(result.isFailure)
            assertEquals("HTTP 403: Message POST is allowed in application-only context", result.exceptionOrNull()?.message)
        }

    @Test
    fun sink_acceptsImagesOnlyAndRejectsVideo() =
        runTest {
            val client = client()

            assertEquals("Teams", client.label)
            assertEquals("Teams チャネル", client.detail)
            assertTrue(client.accepts(MIME_JPEG))
            assertFalse(client.accepts(MIME_MP4))

            val result = client.send(byteArrayOf(1), MIME_MP4)

            assertTrue(result.isFailure)
            assertEquals("Teams には送れない形式です", result.exceptionOrNull()?.message)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun notConfigured_isNotOfferedAsDestination() =
        runTest {
            val client =
                TeamsClient(
                    auth = TeamsAuth(signedInStore(), tenantId = "tenant-1", clientId = "client-1", loginBase = server.url("/").toString()),
                    teamId = "",
                    channelId = "",
                    graphBase = server.url("/v1.0").toString(),
                )

            assertFalse(client.configured)
            assertEquals("Teams 未設定", client.detail)
            assertTrue(client.postMessage("設定なし").isFailure)
            assertEquals(0, server.requestCount)
        }
}
