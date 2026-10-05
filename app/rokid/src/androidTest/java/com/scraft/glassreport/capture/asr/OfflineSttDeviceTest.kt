package com.scraft.glassreport.capture.asr

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

/** Reads only developer-supplied dummy WAVs from app-private files. Never opens camera or network. */
class OfflineSttDeviceTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val report = StringBuilder()
            val transcriber = OfflineWavTranscriber(targetContext)
            check(transcriber.available)
            runBlocking {
                mapOf(
                    "ja" to "現場の写真を確認してください。",
                    "en" to "Please check the photos from the site.",
                    "zh" to "请检查现场的照片。",
                    "ko" to "현장 사진을 확인해 주세요.",
                ).forEach { (language, expected) ->
                    val wav = File(targetContext.filesDir, "offline-stt-test/$language.wav").readBytes()
                    val started = SystemClock.elapsedRealtime()
                    val transcript = transcriber.transcribe(wav).getOrThrow()
                    check(normalized(transcript) == normalized(expected)) {
                        // These are explicitly developer-supplied dummy sentences, never user recordings.
                        "$language mismatch: expected=$expected actual=$transcript"
                    }
                    val line = "$language: $transcript elapsedMs=${SystemClock.elapsedRealtime() - started} pssKb=${Debug.getPss()}\n"
                    report.append(line)
                    sendStatus(0, Bundle().apply { putString("stream", line) })
                }
            }
            transcriber.close()
            result.putString("stream", "OK (4 offline ASR transcripts match expected sentences)\n$report")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun normalized(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[\\p{P}\\p{Z}\\s]+"), "")
}
