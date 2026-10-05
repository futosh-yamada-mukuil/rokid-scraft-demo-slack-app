package com.scraft.glassreport.net.amivoice

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 実測で確認した成功応答（`results[].text` と最上位 `text` の両方が入る）。 */
private const val SUCCESS_BODY =
    """{"results":[{"tokens":[{"written":"配管","confidence":0.99,"starttime":150,"endtime":600,"spoken":"はいかん"}],""" +
        """"confidence":0.99,"starttime":150,"endtime":5200,"tags":[],"rulename":"","text":"配管から水漏れ"}],""" +
        """"utteranceid":"abcdef","text":"配管から水漏れ","code":"","message":""}"""

/** 認証失敗。**HTTP は 200 のまま**返る。 */
private const val AUTH_ERROR_BODY = """{"code":"-","message":"received illegal service authorization"}"""

class AmiVoiceClientTest {
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
    fun configured_reflectsAppKey() {
        assertTrue(client(appKey = "KEY").configured)
        assertFalse(client(appKey = "").configured)
    }

    @Test
    fun missingAppKey_doesNotSendRequest() =
        runTest {
            val result = client(appKey = "").recognize(byteArrayOf(1, 2, 3))

            assertTrue(result.isFailure)
            assertEquals("音声認識キーが未設定", result.exceptionOrNull()?.message)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun recognize_sendsMultipartWithAudioLast() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(SUCCESS_BODY))
            val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46, 9, 9)

            val transcript = client(appKey = "APPKEY").recognize(wav).getOrThrow()
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("POST", request.method)
            assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
            // u → d → a の順で、音声パートが必ず最後。
            val order = listOf("name=\"u\"", "name=\"d\"", "name=\"a\"").map { body.indexOf(it) }
            assertTrue("パートが見つからない: $order", order.all { it >= 0 })
            assertEquals(order.sorted(), order)
            assertTrue(body.contains("APPKEY"))
            assertTrue(body.contains(AMIVOICE_ENGINE_JA))
            assertTrue(body.contains("filename=\"voice.wav\""))
            assertEquals("配管から水漏れ", transcript.text)
        }

    @Test
    fun engineCanBeOverriddenForEnglish() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(SUCCESS_BODY))

            client(appKey = "APPKEY").recognize(byteArrayOf(1), AMIVOICE_ENGINE_EN).getOrThrow()

            assertTrue(
                server
                    .takeRequest()
                    .body
                    .readUtf8()
                    .contains(AMIVOICE_ENGINE_EN),
            )
        }

    @Test
    fun authFailure_isFailureEvenThoughHttpIs200() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(AUTH_ERROR_BODY))

            val result = client(appKey = "WRONG").recognize(byteArrayOf(1))

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull() as AmiVoiceException
            assertEquals("-", error.code)
            assertEquals("認証エラー", error.message)
        }

    @Test
    fun httpError_isReturnedAsFailure() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

            val result = client(appKey = "APPKEY").recognize(byteArrayOf(1))

            assertTrue(result.isFailure)
            assertEquals("HTTP 500", result.exceptionOrNull()?.message)
        }

    @Test
    fun emptyRecognition_isSuccessWithBlankText() {
        val parsed = AmiVoiceClient.parse("""{"results":[],"text":"","code":"","message":""}""")

        assertTrue(parsed.isSuccess)
        assertEquals("", parsed.getOrThrow())
    }

    @Test
    fun parse_takesTopLevelTextNotTheOneInsideResults() {
        // 最上位の text だけを 2 発話ぶんの連結に差し替える（results[].text はそのまま）。
        val payload = SUCCESS_BODY.replace(""""text":"配管から水漏れ","code"""", """"text":"配管から水漏れ 至急確認を","code"""")

        assertEquals("配管から水漏れ 至急確認を", AmiVoiceClient.parse(payload).getOrThrow())
    }

    @Test
    fun parse_reportsOtherErrorMessagesAsIs() {
        val error = AmiVoiceClient.parse("""{"code":"o","message":"connection error"}""").exceptionOrNull() as AmiVoiceException

        assertEquals("o", error.code)
        assertEquals("connection error", error.message)
    }

    @Test
    fun parse_rejectsBrokenJson() {
        val error = AmiVoiceClient.parse("<html>502</html>").exceptionOrNull() as AmiVoiceException

        assertEquals("応答が不正", error.message)
    }

    private fun client(appKey: String) = AmiVoiceClient(endpoint = server.url("/v1/nolog/recognize").toString(), appKey = appKey)
}
