package com.scraft.glassreport.capture.asr

import android.util.Log
import com.scraft.glassreport.net.amivoice.AMIVOICE_ENGINE_JA
import com.scraft.glassreport.net.amivoice.AmiVoiceClient
import kotlinx.coroutines.CancellationException

private const val TAG = "AmiVoiceSpeechSource"

/**
 * [VoiceRecorder]（録音）と [AmiVoiceClient]（認識）を束ねた [SpeechSource] の実装。
 *
 * 「録音して文字起こしする」までを1回の suspend 呼び出しにまとめる。
 * 呼び出し側（`CaptureViewModel`）は録音の残り秒数を自分で数えて HUD に出す
 * （動画のカウントダウンと同じ作り）。
 *
 * マイク権限の確認は行わない。権限が無いときは呼び出し側が音声コメントを飛ばす
 * （落とさず、コメントなしで送る）。
 */
class AmiVoiceSpeechSource(
    private val client: AmiVoiceClient = AmiVoiceClient(),
    private val recorder: VoiceCapture = VoiceRecorder,
    private val durationMs: Long = VOICE_DURATION_MS,
    private val engine: String = AMIVOICE_ENGINE_JA,
    /** falseなら録音だけ行い、言語判定と認識はPC側へ任せる。 */
    private val recognize: Boolean = true,
) : SpeechSource {
    private var activeStop: VoiceStopSignal? = null
    override val available: Boolean get() = !recognize || client.configured

    override suspend fun listenOnce(): Result<SpeechResult> {
        if (recognize && !client.configured) return Result.failure(IllegalStateException("音声認識キーが未設定"))

        val voice = beginRecording().await().getOrElse { return Result.failure(it) }
        return transcribe(voice)
    }

    override fun beginRecording(durationMs: Long?): SpeechRecordingSession {
        val stop = VoiceStopSignal()
        activeStop = stop
        return object : SpeechRecordingSession {
            override fun stop() = stop.stop()

            override suspend fun await(): Result<RecordedVoice> =
                try {
                    Result.success(recorder.record(durationMs ?: this@AmiVoiceSpeechSource.durationMs, stop))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "録音に失敗", error)
                    Result.failure(error)
                } finally {
                    if (activeStop === stop) activeStop = null
                }
        }
    }

    override suspend fun transcribe(voice: RecordedVoice): Result<SpeechResult> {
        // An immediate stop can legitimately contain no PCM. Never send an empty WAV to STT.
        if (voice.wav.size <= WAV_HEADER_BYTES) {
            return Result.success(SpeechResult("", voice.durationMs, 0L, silent = true, wav = voice.wav))
        }

        if (!recognize) {
            return Result.success(SpeechResult("", voice.durationMs, 0L, voice.silent, voice.wav))
        }

        val transcript =
            client
                .recognize(voice.wav, engine)
                .getOrElse { return Result.failure(it) }

        return Result.success(
            SpeechResult(
                text = transcript.text.trim(),
                recordMs = voice.durationMs,
                recognizeMs = transcript.elapsedMs,
                silent = voice.silent,
                wav = voice.wav,
            ),
        )
    }

    override fun close() {
        activeStop?.stop()
    }
}
