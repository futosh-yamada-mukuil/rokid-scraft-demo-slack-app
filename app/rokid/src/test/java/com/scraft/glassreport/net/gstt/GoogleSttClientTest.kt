package com.scraft.glassreport.net.gstt

import com.scraft.glassreport.capture.asr.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class GoogleSttClientTest {
    private val wav = VoiceRecorder.toWav(byteArrayOf(1, 0, 2, 0))

    @Test fun sendsPcmWavAndFourLanguageAutoConfig() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"results":[{"alternatives":[{"transcript":"確認しました"}]}]}"""))
                val client = GoogleSttClient("dummy-key", server.url("/recognize").toString())
                assertEquals("確認しました", client.transcribe(wav).getOrThrow())
                val request = server.takeRequest()
                assertEquals("dummy-key", request.getHeader("x-goog-api-key"))
                val body = JSONObject(request.body.readUtf8())
                val config = body.getJSONObject("config")
                assertEquals("LINEAR16", config.getString("encoding"))
                assertEquals(16000, config.getInt("sampleRateHertz"))
                assertEquals("ja-JP", config.getString("languageCode"))
                assertEquals(
                    listOf("en-US", "cmn-Hans-CN", "ko-KR"),
                    (0..2).map { config.getJSONArray("alternativeLanguageCodes").getString(it) },
                )
                assertTrue(wav.contentEquals(Base64.getDecoder().decode(body.getJSONObject("audio").getString("content"))))
            }
        }

    @Test fun missingKeyAndEmptyAudioFailLocally() =
        runTest {
            val missing = GoogleSttClient("")
            assertFalse(missing.available)
            assertTrue(
                missing
                    .transcribe(wav)
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("キーが未設定"),
            )
            assertTrue(GoogleSttClient("dummy").transcribe(ByteArray(44)).isFailure)
        }

    @Test fun errorsNeverExposeServerBodyOrCredentials() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(403).setBody("SECRET_SERVER_DETAIL"))
                val result = GoogleSttClient("SECRET_KEY", server.url("/").toString()).transcribe(wav)
                assertTrue(result.isFailure)
                assertTrue(
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("課金設定"),
                )
                assertFalse(result.exceptionOrNull().toString().contains("SECRET"))
            }
            assertEquals("", parseSttResponse("{}").getOrThrow())
            assertTrue(parseSttResponse("bad json").isFailure)
        }

    @Test fun utterancesSplitByAPauseAreJoinedWithoutSpacesInJapanese() {
        fun results(vararg transcripts: String) =
            """{"results":[${transcripts.joinToString(",") { """{"alternatives":[{"transcript":"$it"}]}""" }}]}"""
        assertEquals("バルブを閉めました。圧力は正常です", parseSttResponse(results("バルブを閉めました。", " 圧力は正常です")).getOrThrow())
        assertEquals("エラー発生", parseSttResponse(results("エラー 発生")).getOrThrow())
        assertEquals("valve closed pressure is normal", parseSttResponse(results("valve closed", "pressure is normal")).getOrThrow())
        assertEquals("확인했습니다 사진 보냅니다", parseSttResponse(results("확인했습니다", "사진 보냅니다")).getOrThrow())
        assertEquals("", parseSttResponse("""{"results":[{"alternatives":[{}]},{}]}""").getOrThrow())
    }

    @Test fun cancellationStopsAnInFlightRequest() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val client = GoogleSttClient("dummy", server.url("/").toString())
                val job = async(Dispatchers.IO) { client.transcribe(wav) }
                withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
                client.close()
            }
        }
}
