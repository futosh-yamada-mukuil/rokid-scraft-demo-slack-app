package com.scraft.glassreport.capture.asr

import kotlinx.coroutines.CancellationException

/** Shared stop/pause/resume-compatible recording path for cloud and offline recognition. */
class RecordedSpeechSource(
    private val transcriber: WavTranscriber,
    private val recorder: VoiceCapture = VoiceRecorder,
    private val durationMs: Long = VOICE_DURATION_MS,
) : SpeechSource {
    private var activeStop: VoiceStopSignal? = null
    override val available: Boolean get() = transcriber.available
    override val unavailableReason: String get() = transcriber.unavailableReason

    override suspend fun listenOnce(): Result<SpeechResult> {
        if (!available) return Result.failure(IllegalStateException(unavailableReason))
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
                    Result.success(recorder.record(durationMs ?: this@RecordedSpeechSource.durationMs, stop))
                } catch (
                    cancel: CancellationException,
                ) {
                    throw cancel
                } catch (error: Exception) {
                    Result.failure(error)
                } finally {
                    if (activeStop === stop) activeStop = null
                }
        }
    }

    override suspend fun transcribe(voice: RecordedVoice): Result<SpeechResult> {
        if (voice.wav.size <= WAV_HEADER_BYTES || voice.silent) {
            return Result.success(SpeechResult("", voice.durationMs, 0, true, voice.wav))
        }
        if (!available) return Result.failure(IllegalStateException(unavailableReason))
        val started = System.nanoTime()
        return transcriber.transcribe(voice.wav).map {
            SpeechResult(it.trim(), voice.durationMs, (System.nanoTime() - started) / 1_000_000, voice.silent, voice.wav)
        }
    }

    override fun close() {
        activeStop?.stop()
        transcriber.close()
    }
}
