package com.scraft.glassreport.capture.asr

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import com.scraft.glassreport.MainActivity
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/** Explicit manual microphone probe. Reports levels only; never saves or uploads audio. */
class MicrophoneDeviceTest : Instrumentation() {
    private var production = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        production = arguments?.getString("production") == "true"
        start()
    }

    @Suppress("MissingPermission")
    override fun onStart() {
        val result = Bundle()
        var recorder: AudioRecord? = null
        try {
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(1000)
            if (production) {
                val voice = runBlocking { VoiceRecorder.record(5000) }
                check(voice.durationMs == 5000L)
                check(voice.wav.size == WAV_HEADER_BYTES + 160000)
                result.putString(
                    "stream",
                    "Production recorder complete durationMs=${voice.durationMs} peak=${voice.peak} silent=${voice.silent} (no audio saved/uploaded)\n",
                )
                finish(Activity.RESULT_OK, result)
                return
            }
            val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0)
            val mic =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
            recorder = mic
            check(mic.state == AudioRecord.STATE_INITIALIZED)
            val buffer = ShortArray(minBuffer)
            mic.startRecording()
            val started = SystemClock.elapsedRealtime()
            var nextReport = 0L
            var peak = 0
            var nonZero = 0L
            while (SystemClock.elapsedRealtime() - started < 6000) {
                val count = mic.read(buffer, 0, buffer.size)
                check(count >= 0)
                for (index in 0 until count) {
                    peak = maxOf(peak, abs(buffer[index].toInt()))
                    if (buffer[index].toInt() != 0) nonZero++
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed >= nextReport) {
                    val silenced = mic.activeRecordingConfiguration?.isClientSilenced
                    sendStatus(
                        0,
                        Bundle().apply { putString("stream", "elapsedMs=$elapsed silenced=$silenced peak=$peak nonZero=$nonZero\n") },
                    )
                    nextReport = elapsed + 500
                }
            }
            result.putString("stream", "PCM probe complete peak=$peak nonZero=$nonZero (no audio saved/uploaded)\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runCatching { recorder?.stop() }
            recorder?.release()
        }
    }
}
