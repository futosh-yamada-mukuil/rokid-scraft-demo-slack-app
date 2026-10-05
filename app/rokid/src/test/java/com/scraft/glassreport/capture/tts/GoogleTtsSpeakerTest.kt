package com.scraft.glassreport.capture.tts

import com.scraft.glassreport.net.gtts.GoogleTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 再生そのもの（MediaPlayer）はローカル JVM では動かせないため、
 * **失敗時に落ちずに状態へ現れること**を確かめる。再生の確認は実機で行う。
 */
class GoogleTtsSpeakerTest {
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
    fun blankText_failsWithoutCallingTheApi() {
        val speaker = speaker()

        speaker.speak("   ")

        assertEquals(SpeakPhase.FAILED, speaker.status.value.phase)
        assertEquals("読み上げる文がありません", speaker.status.value.message)
        assertEquals(0, server.requestCount)
        speaker.close()
    }

    @Test
    fun httpFailure_isReportedAsFailedStatus() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Quota exceeded"}}"""))
            val speaker = speaker()

            speaker.speak("了解しました。")
            val status = withTimeout(TIMEOUT_MS) { speaker.status.first { it.phase == SpeakPhase.FAILED } }

            assertEquals("読み上げが混雑中", status.message)
            assertEquals(SpeechLanguage.JA, status.language)
            speaker.close()
        }

    @Test
    fun networkFailure_keepsTheRequestedLanguageInStatus() =
        runBlocking {
            val offline =
                GoogleTtsSpeaker(
                    client = GoogleTtsClient(apiKey = "test-key", endpoint = "http://127.0.0.1:1/v1/text:synthesize"),
                    dispatcher = Dispatchers.Unconfined,
                )

            offline.speak("Understood.", SpeechLanguage.EN)
            val status = withTimeout(TIMEOUT_MS) { offline.status.first { it.phase == SpeakPhase.FAILED } }

            assertEquals("読み上げの通信エラー", status.message)
            assertEquals(SpeechLanguage.EN, status.language)
            offline.close()
        }

    @Test
    fun stop_returnsToIdle() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))
            val speaker = speaker()

            speaker.speak("あ")
            withTimeout(TIMEOUT_MS) { speaker.status.first { it.phase == SpeakPhase.FAILED } }
            speaker.stop()

            assertEquals(SpeakPhase.IDLE, speaker.status.value.phase)
            assertEquals("", speaker.status.value.message)
            speaker.close()
        }

    @Test
    fun speakingRateProvider_isReadForEachUtterance() =
        runBlocking {
            var rate = 1.2f
            repeat(2) { server.enqueue(MockResponse().setResponseCode(429)) }
            val speaker =
                GoogleTtsSpeaker(
                    client = GoogleTtsClient(apiKey = "test-key", endpoint = server.url("/v1/text:synthesize").toString()),
                    dispatcher = Dispatchers.Unconfined,
                    speakingRate = { rate },
                )

            speaker.speak("最初")
            withTimeout(TIMEOUT_MS) { speaker.status.first { it.phase == SpeakPhase.FAILED } }
            val first = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("audioConfig")
            assertEquals(1.2, first.getDouble("speakingRate"), 0.001)

            rate = 1.4f
            speaker.speak("次")
            withTimeout(TIMEOUT_MS) { speaker.status.first { it.phase == SpeakPhase.FAILED } }
            val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("audioConfig")
            assertEquals(1.4, second.getDouble("speakingRate"), 0.001)
            speaker.close()
        }

    private fun speaker() =
        GoogleTtsSpeaker(
            client = GoogleTtsClient(apiKey = "test-key", endpoint = server.url("/v1/text:synthesize").toString()),
            // Unconfined にすると speak() の続きが呼び出しスレッドで進むため、Looper が無くても検証できる。
            dispatcher = Dispatchers.Unconfined,
        )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
