package com.scraft.glassreport.capture.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

private const val TAG = "VoiceRecorder"

/**
 * 従来の録音長。新しい撮影フローの5秒録音は呼び出し側で指定する。
 * （`VIDEO_MAX_DURATION_MS` と同じく「ここだけ直せば全部変わる」ようにする）
 */
const val VOICE_DURATION_MS = 3_000L

/** Google STTとオフラインSTTに共通の録音形式（16kHz / mono / PCM16）。 */
const val VOICE_SAMPLE_RATE = 16_000
const val VOICE_CHANNELS = 1
const val VOICE_BITS_PER_SAMPLE = 16

/** WAV（RIFF）の固定ヘッダ長。 */
const val WAV_HEADER_BYTES = 44

/** これ未満の振幅しか無ければマイクが音を拾えていないと見なす（PCM16 の全振幅は 32767）。 */
private const val SILENCE_PEAK = 500

private const val BYTES_PER_SAMPLE = VOICE_BITS_PER_SAMPLE / 8

/** 1回の録音結果。[wav] は各STTエンジンに渡す16kHz/mono/PCM16 WAV。 */
data class RecordedVoice(
    val wav: ByteArray,
    val durationMs: Long,
    /** 区間中の最大振幅（0..32767）。マイクが死んでいるのか無言だったのかを切り分ける。 */
    val peak: Int,
) {
    /** マイクが音を拾えていないと思われる状態。 */
    val silent: Boolean get() = peak < SILENCE_PEAK
}

/** 録音の抽象。テストではフェイクを差し込む（実機のマイクを使わずに ViewModel を回すため）。 */
fun interface VoiceCapture {
    suspend fun record(durationMs: Long): RecordedVoice

    suspend fun record(
        durationMs: Long,
        stop: VoiceStopSignal,
    ): RecordedVoice = record(durationMs)
}

/** Stop returns the WAV recorded so far; coroutine cancellation instead discards it. */
class VoiceStopSignal {
    private val requested = AtomicBoolean(false)
    private val stopped = CompletableDeferred<Unit>()
    val isRequested: Boolean get() = requested.get()

    fun stop() {
        requested.set(true)
        stopped.complete(Unit)
    }

    suspend fun awaitStop() = stopped.await()
}

/** Small adapter makes stop/cancel/error cleanup testable without a physical microphone. */
internal interface VoicePcmInput {
    val bufferBytes: Int get() = 4096

    fun start()

    fun read(
        buffer: ByteArray,
        size: Int,
    ): Int

    fun stop()

    fun release()
}

/**
 * `AudioRecord` で 16kHz / mono / PCM16 を [durationMs] だけ録り、WAV ヘッダを付けて返す。
 *
 * - Google STTとオフラインSTTの共通入力契約に合わせ、16kHz / mono / PCM16を保持する。
 * - 端末内にファイルは作らない（送ったら用済みなのでメモリ上だけで扱う）。
 * - 権限（`RECORD_AUDIO`）の確認は呼び出し側の責務。無い場合は録音を試みずに飛ばす
 *   （[RecordedSpeechSource] が判断する）。
 */
object VoiceRecorder : VoiceCapture {
    /** 権限は呼び出し側（[RecordedSpeechSource]）が確認済み。 */
    @SuppressLint("MissingPermission")
    override suspend fun record(durationMs: Long): RecordedVoice = record(durationMs, VoiceStopSignal())

    @SuppressLint("MissingPermission")
    override suspend fun record(
        durationMs: Long,
        stop: VoiceStopSignal,
    ): RecordedVoice =
        withContext(Dispatchers.IO) {
            val minBuffer =
                AudioRecord.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IOException("録音バッファを確保できません")

            // Match the known-working mukuil-one Rokid MicCaptureThread capture path.
            val bufferSize = minBuffer * 2
            val recorder = open(bufferSize)
            val samples = ShortArray(bufferSize / BYTES_PER_SAMPLE)
            recordWithStop(
                object : VoicePcmInput {
                    override val bufferBytes = bufferSize

                    override fun start() {
                        recorder.startRecording()
                        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("録音を開始できません")
                    }

                    override fun read(
                        buffer: ByteArray,
                        size: Int,
                    ): Int {
                        val count = recorder.read(samples, 0, minOf(samples.size, size / BYTES_PER_SAMPLE), AudioRecord.READ_BLOCKING)
                        if (count <= 0) return count
                        pcm16ToLittleEndian(samples, count, buffer)
                        return count * BYTES_PER_SAMPLE
                    }

                    override fun stop() = recorder.stop()

                    override fun release() = recorder.release()
                },
                durationMs,
                stop,
            )
        }

    /** The waiter can stop AudioRecord from another IO thread while native read is blocked. */
    internal suspend fun recordWithStop(
        recorder: VoicePcmInput,
        durationMs: Long,
        stop: VoiceStopSignal,
        stopTimeoutMs: Long = durationMs * 2 + 1000L,
    ): RecordedVoice =
        coroutineScope {
            val lock = Any()
            var stopped = false
            var released = false
            val guarded =
                object : VoicePcmInput {
                    override val bufferBytes get() = recorder.bufferBytes

                    override fun start() =
                        synchronized(lock) {
                            // Cancellation/stop can win before the recording worker starts.
                            if (!stop.isRequested && !stopped) recorder.start()
                        }

                    override fun read(
                        buffer: ByteArray,
                        size: Int,
                    ): Int = recorder.read(buffer, size)

                    override fun stop() =
                        synchronized(lock) {
                            if (!stopped && !released) {
                                stopped = true
                                recorder.stop()
                            }
                        }

                    override fun release() =
                        synchronized(lock) {
                            if (!released) {
                                released = true
                                recorder.release()
                            }
                        }
                }
            val stopper =
                launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        withTimeout(stopTimeoutMs) { stop.awaitStop() }
                    } finally {
                        stop.stop()
                        runCatching { guarded.stop() }
                    }
                }
            try {
                recordFrom(guarded, durationMs, stop)
            } finally {
                stopper.cancelAndJoin()
            }
        }

    internal suspend fun recordFrom(
        recorder: VoicePcmInput,
        durationMs: Long,
        stop: VoiceStopSignal,
        now: () -> Long = SystemClock::elapsedRealtime,
    ): RecordedVoice {
        try {
            require(durationMs > 0)
            val targetBytes = ((VOICE_SAMPLE_RATE * BYTES_PER_SAMPLE * durationMs) / 1_000L).toInt()
            val pcm = readAll(recorder, targetBytes, durationMs, stop, now)
            val recorded = (pcm.bytes.size.toLong() * 1_000L) / (VOICE_SAMPLE_RATE * BYTES_PER_SAMPLE)
            Log.i(TAG, "録音 ${pcm.bytes.size}B (${recorded}ms) peak=${pcm.peak}")
            return RecordedVoice(wav = toWav(pcm.bytes), durationMs = recorded, peak = pcm.peak)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * Use the same MIC source and continuous PCM reads as mukuil-one.
     * Rokid reports isClientSilenced=true even while returning nonzero microphone PCM (device-probed).
     * Do not treat that vendor flag as an error; RecordedVoice.silent uses the actual PCM peak.
     * This does not change microphone permissions, privacy settings or the audio source.
     */
    @SuppressLint("MissingPermission")
    private fun open(bufferBytes: Int): AudioRecord {
        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                VOICE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("マイクを開けません")
        }
        return recorder
    }

    internal fun pcm16ToLittleEndian(
        samples: ShortArray,
        count: Int,
        out: ByteArray,
    ) {
        for (index in 0 until count) {
            val value = samples[index].toInt()
            out[index * 2] = value.toByte()
            out[index * 2 + 1] = (value shr 8).toByte()
        }
    }

    private class Pcm(
        val bytes: ByteArray,
        val peak: Int,
    )

    /**
     * [targetBytes] 分だけ読む。`read` が 0 を返し続ける端末で固まらないよう、
     * 録音長の2倍＋1秒を過ぎたら読めた分で打ち切る。
     */
    private suspend fun readAll(
        recorder: VoicePcmInput,
        targetBytes: Int,
        durationMs: Long,
        stop: VoiceStopSignal,
        now: () -> Long,
    ): Pcm {
        recorder.start()

        val out = ByteArrayOutputStream(targetBytes + 1_024)
        val chunk = ByteArray(minOf(targetBytes, recorder.bufferBytes).coerceAtLeast(BYTES_PER_SAMPLE))
        val deadline = now() + durationMs * 2 + 1_000L
        var peak = 0
        while (out.size() < targetBytes && !stop.isRequested) {
            coroutineContext.ensureActive()
            val read = recorder.read(chunk, minOf(chunk.size, targetBytes - out.size()))
            if (read < 0) {
                if (stop.isRequested) break
                throw IOException("録音エラー ($read)")
            }
            if (read > 0) {
                out.write(chunk, 0, read)
                peak = maxOf(peak, peakOf(chunk, read))
            } else {
                delay(10L)
            }
            if (now() >= deadline) {
                Log.w(TAG, "録音が期待の長さに届きませんでした ${out.size()}/$targetBytes B")
                break
            }
        }
        if (out.size() == 0 && !stop.isRequested) throw IOException("音声を取得できませんでした")
        return Pcm(out.toByteArray(), peak)
    }

    /** リトルエンディアン PCM16 の最大振幅。 */
    private fun peakOf(
        buffer: ByteArray,
        length: Int,
    ): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            i += BYTES_PER_SAMPLE
        }
        return peak
    }

    /**
     * PCM の生バイト列に 44 バイトの WAV（RIFF）ヘッダを付ける。**純粋関数**（ユニットテスト対象）。
     * 数値は全てリトルエンディアン。
     */
    fun toWav(
        pcm: ByteArray,
        sampleRate: Int = VOICE_SAMPLE_RATE,
        channels: Int = VOICE_CHANNELS,
        bitsPerSample: Int = VOICE_BITS_PER_SAMPLE,
    ): ByteArray {
        val bytesPerFrame = channels * bitsPerSample / 8
        val header =
            ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(ascii("RIFF"))
                putInt(WAV_HEADER_BYTES - 8 + pcm.size) // これ以降のバイト数
                put(ascii("WAVE"))
                put(ascii("fmt "))
                putInt(16) // fmt チャンクの長さ
                putShort(1) // 1 = リニア PCM（非圧縮）
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * bytesPerFrame) // バイト毎秒
                putShort(bytesPerFrame.toShort())
                putShort(bitsPerSample.toShort())
                put(ascii("data"))
                putInt(pcm.size)
            }
        return header.array() + pcm
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
