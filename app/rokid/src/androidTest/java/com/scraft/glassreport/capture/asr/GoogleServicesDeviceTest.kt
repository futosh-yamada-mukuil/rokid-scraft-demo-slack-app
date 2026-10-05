package com.scraft.glassreport.capture.asr

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.net.androidAppIdentity
import com.scraft.glassreport.net.gstt.GoogleSttClient
import com.scraft.glassreport.net.gtts.GoogleTtsClient
import com.scraft.glassreport.net.gtts.TtsVoice
import com.scraft.glassreport.net.translate.CloudTranslationClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Opt-in real API check with generated samples only. Never connects to Microsoft Graph. */
class GoogleServicesDeviceTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val identity = androidAppIdentity(targetContext)
        val stt = GoogleSttClient(identity = identity)
        try {
            // A keyless probe makes emulator TLS/DNS failures distinguishable from API errors.
            OkHttpClient().newCall(Request.Builder().url("https://speech.googleapis.com/").build()).execute().use {
                sendStatus(0, Bundle().apply { putString("stream", "Keyless HTTPS probe: HTTP ${it.code}\n") })
            }
            val samples =
                listOf(
                    Triple("ja", SpeechLanguage.JA, "現場の写真を確認してください。"),
                    Triple("en", SpeechLanguage.EN, "Please check the photos from the site."),
                    Triple("zh", SpeechLanguage.ZH_CN, "请检查现场的照片。"),
                    Triple("ko", SpeechLanguage.KO, "현장 사진을 확인해 주세요."),
                )
            val translator = CloudTranslationClient(identity = identity)
            val tts = GoogleTtsClient(identity = identity)
            runBlocking {
                samples.forEach { (fileName, language, expected) ->
                    val wav = File(targetContext.filesDir, "offline-stt-test/$fileName.wav").readBytes()
                    val transcript = stt.transcribe(wav).getOrThrow()
                    check(normalize(transcript) == normalize(expected)) { "STT $fileName: $transcript" }
                    sendStatus(0, Bundle().apply { putString("stream", "STT ${language.code}: $transcript\n") })
                    samples.forEach { (_, target, _) ->
                        val translated = translator.translate(transcript, target).getOrThrow()
                        check(translated.isNotBlank())
                        sendStatus(0, Bundle().apply { putString("stream", "Translate ${language.code}->${target.code}: $translated\n") })
                    }
                    val voice =
                        when (language) {
                            SpeechLanguage.JA -> TtsVoice.JA
                            SpeechLanguage.EN -> TtsVoice.EN
                            SpeechLanguage.ZH_CN -> TtsVoice.ZH_CN
                            SpeechLanguage.KO -> TtsVoice.KO
                            else -> error("Unexpected test language")
                        }
                    val audio = tts.synthesize(transcript, voice, 1.2f).getOrThrow()
                    check(audio.mp3.size > 1000)
                    sendStatus(0, Bundle().apply { putString("stream", "TTS ${language.code}: ${audio.mp3.size} bytes\n") })
                }
            }
            result.putString("stream", "OK (APK Google STT 4, translation 16, TTS 4)")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            stt.close()
        }
    }

    private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()
}
