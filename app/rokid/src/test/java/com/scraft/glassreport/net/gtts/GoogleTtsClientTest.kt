package com.scraft.glassreport.net.gtts

import com.scraft.glassreport.net.AndroidAppIdentity
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GoogleTtsClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun blankKey_returnsFailureWithoutRequest() =
        runTest {
            val result = GoogleTtsClient(apiKey = "", endpoint = server.url("/v1/text:synthesize").toString()).synthesize("こんにちは")

            assertTrue(result.isFailure)
            assertEquals("読み上げキーが未設定", result.exceptionOrNull()?.message)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun blankText_returnsFailureWithoutRequest() =
        runTest {
            val result = client().synthesize("   \n ")

            assertTrue(result.isFailure)
            assertEquals("読み上げる文が空", result.exceptionOrNull()?.message)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun japanese_sendsNeural2VoiceAndMp3RequestAndDecodesAudio() =
        runTest {
            val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 7, 8)
            server.enqueue(audioResponse(mp3))

            val audio = client().synthesize(" 了解しました。 ", TtsVoice.JA).getOrThrow()
            val request = server.takeRequest()
            val body = JSONObject(request.body.readUtf8())

            assertEquals("POST", request.method)
            assertEquals("/v1/text:synthesize", request.path)
            assertEquals("test-key", request.getHeader("x-goog-api-key"))
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals("了解しました。", body.getJSONObject("input").getString("text"))
            assertEquals("ja-JP", body.getJSONObject("voice").getString("languageCode"))
            assertEquals("ja-JP-Neural2-B", body.getJSONObject("voice").getString("name"))
            assertEquals("MP3", body.getJSONObject("audioConfig").getString("audioEncoding"))
            assertArrayEquals(mp3, audio.mp3)
            assertEquals(TtsVoice.JA, audio.voice)
        }

    @Test
    fun english_usesEnglishNeural2Voice() =
        runTest {
            server.enqueue(audioResponse(byteArrayOf(1, 2, 3)))

            client().synthesize("Understood.", TtsVoice.EN).getOrThrow()
            val voice = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("voice")

            assertEquals("en-US", voice.getString("languageCode"))
            assertEquals("en-US-Neural2-F", voice.getString("name"))
        }

    @Test
    fun speakingRate_isIncludedInSynthesisRequest() =
        runTest {
            server.enqueue(audioResponse(byteArrayOf(1, 2, 3)))

            client().synthesize("了解しました", TtsVoice.JA, speakingRate = 1.3f).getOrThrow()
            val config = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("audioConfig")

            assertEquals(1.3, config.getDouble("speakingRate"), 0.001)
        }

    @Test
    fun androidAppRestrictionHeaders_areSentWhenIdentityIsKnown() =
        runTest {
            server.enqueue(audioResponse(byteArrayOf(1)))
            val identity = AndroidAppIdentity("com.scraft.glassreport", "AABBCCDD")

            GoogleTtsClient(apiKey = "test-key", endpoint = server.url("/v1/text:synthesize").toString(), identity = identity)
                .synthesize("あ")
                .getOrThrow()
            val request = server.takeRequest()

            assertEquals("com.scraft.glassreport", request.getHeader("X-Android-Package"))
            assertEquals("AABBCCDD", request.getHeader("X-Android-Cert"))
        }

    @Test
    fun withoutIdentity_noRestrictionHeaders() =
        runTest {
            server.enqueue(audioResponse(byteArrayOf(1)))

            client().synthesize("あ").getOrThrow()
            val request = server.takeRequest()

            assertNull(request.getHeader("X-Android-Package"))
            assertNull(request.getHeader("X-Android-Cert"))
        }

    @Test
    fun longText_isTruncatedInsteadOfRejectedByServer() =
        runTest {
            server.enqueue(audioResponse(byteArrayOf(1)))

            client().synthesize("あ".repeat(1_500)).getOrThrow()
            val sent = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("input").getString("text")

            assertEquals(1_000, sent.length)
        }

    @Test
    fun rateLimited_isReportedAsRetryableFailure() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Quota exceeded"}}"""))

            val result = client().synthesize("あ")
            val error = result.exceptionOrNull() as GoogleTtsException

            assertEquals("読み上げが混雑中", error.message)
            assertEquals(429, error.code)
            assertTrue(error.rateLimited)
        }

    @Test
    fun invalidKey_isReportedWithoutLeakingBody() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"API key not valid"}}"""))

            val error = client().synthesize("あ").exceptionOrNull() as GoogleTtsException

            assertEquals("読み上げキーが無効", error.message)
            assertEquals(403, error.code)
        }

    @Test
    fun serverError_isReportedAsFailure() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503).setBody("upstream unavailable"))

            val error = client().synthesize("あ").exceptionOrNull() as GoogleTtsException

            assertEquals("読み上げサーバ障害", error.message)
        }

    @Test
    fun emptyAudioContent_isFailure() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"audioContent":""}"""))

            assertEquals("音声が空", client().synthesize("あ").exceptionOrNull()?.message)
        }

    @Test
    fun brokenJson_isFailureInsteadOfCrash() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>proxy</html>"))

            assertEquals("音声が空", client().synthesize("あ").exceptionOrNull()?.message)
        }

    @Test
    fun brokenBase64_isFailureInsteadOfCrash() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"audioContent":"###"}"""))

            assertTrue(client().synthesize("あ").isFailure)
        }

    @Test
    fun connectionRefused_isFailureInsteadOfCrash() =
        runTest {
            // 誰も待ち受けていないポート（グラスの Wi-Fi が OFF のときと同じく接続自体が失敗する）。
            val result = GoogleTtsClient(apiKey = "test-key", endpoint = "http://127.0.0.1:1/v1/text:synthesize").synthesize("あ")

            assertTrue(result.isFailure)
            assertEquals("読み上げの通信エラー", result.exceptionOrNull()?.message)
        }

    private fun client() = GoogleTtsClient(apiKey = "test-key", endpoint = server.url("/v1/text:synthesize").toString())

    private fun audioResponse(mp3: ByteArray): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setBody("""{"audioContent":"${Base64.getEncoder().encodeToString(mp3)}"}""")
}
