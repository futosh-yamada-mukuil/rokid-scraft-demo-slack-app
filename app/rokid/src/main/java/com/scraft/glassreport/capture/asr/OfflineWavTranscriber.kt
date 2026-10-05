package com.scraft.glassreport.capture.asr

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** On-device SenseVoiceSmall int8, automatic input language. No network fallback. */
class OfflineWavTranscriber(
    context: Context,
) : WavTranscriber {
    private val appContext = context.applicationContext
    private val assetsPresent =
        runCatching {
            appContext.assets
                .list(ASSET_DIRECTORY)
                ?.toSet()
                .orEmpty()
                .containsAll(MODEL_FILES)
        }.getOrDefault(false)

    override val available: Boolean get() = assetsPresent
    override val unavailableReason: String get() = "オフライン音声認識モデルがAPKにありません"

    override suspend fun transcribe(wav: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            // A process-wide lock also prevents overlapping recognizers after screen recreation.
            recognizerLock.withLock {
                mutableBusy.value = true
                try {
                    check(available) { unavailableReason }
                    val samples = OfflinePcmWav.samples(wav)
                    if (samples.isEmpty() || samples.all { it == 0f }) return@withLock Result.success("")
                    currentCoroutineContext().ensureActive()
                    val memory = ActivityManager.MemoryInfo()
                    appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                    check(!memory.lowMemory && memory.availMem >= MIN_AVAILABLE_MEMORY) {
                        "オフライン認識用メモリが不足しています。カメラを閉じて再試行してください"
                    }
                    val started = SystemClock.elapsedRealtime()
                    // File loading avoids an extra full model byte array through AssetManager/JNI.
                    val modelDirectory = installModels()
                    currentCoroutineContext().ensureActive()
                    val recognizer =
                        OfflineRecognizer(
                            config =
                                OfflineRecognizerConfig(
                                    modelConfig =
                                        OfflineModelConfig(
                                            senseVoice =
                                                OfflineSenseVoiceModelConfig(
                                                    model = File(modelDirectory, MODEL_FILES[0]).absolutePath,
                                                    language = "auto", // Independent of display/translation language.
                                                    useInverseTextNormalization = true,
                                                ),
                                            tokens = File(modelDirectory, MODEL_FILES[1]).absolutePath,
                                            numThreads = 2,
                                            provider = "cpu",
                                            debug = false,
                                        ),
                                ),
                        )
                    try {
                        currentCoroutineContext().ensureActive()
                        val stream = recognizer.createStream()
                        try {
                            // Short tap-stop recordings may end at a phoneme boundary. Symmetric 250ms
                            // silence preserves all recorded samples and stabilizes automatic decoding.
                            val padded = FloatArray(samples.size + 2 * BOUNDARY_SAMPLES)
                            samples.copyInto(padded, BOUNDARY_SAMPLES)
                            stream.acceptWaveform(padded, 16000)
                            recognizer.decode(stream)
                            currentCoroutineContext().ensureActive()
                            val text =
                                recognizer
                                    .getResult(stream)
                                    .text
                                    .trim()
                                    .replace(JAPANESE_WORD_SPACES, "")
                            Log.i(TAG, "Offline STT completed elapsedMs=${SystemClock.elapsedRealtime() - started} samples=${samples.size}")
                            Result.success(text)
                        } finally {
                            stream.release()
                        }
                    } finally {
                        // Never retain the model weights while returning to the camera or lobby.
                        recognizer.release()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                } catch (failure: LinkageError) {
                    Result.failure(IllegalStateException("この端末ではオフライン音声認識を初期化できません", failure))
                } catch (failure: OutOfMemoryError) {
                    Result.failure(IllegalStateException("オフライン認識用メモリが不足しています。時間をおいて再試行してください", failure))
                } finally {
                    mutableBusy.value = false
                }
            }
        }

    // Resources live only inside transcribe's try/finally. Releasing JNI from another thread
    // during decode causes a native crash, so cancellation is handled by its owning coroutine.
    override fun close() = Unit

    private fun installModels(): File {
        val directory = File(appContext.noBackupFilesDir, "offline-stt-sensevoice-int8-c71f0ce")
        check(directory.isDirectory || directory.mkdirs()) { "音声認識モデルを保存できません" }
        MODEL_FILES.forEachIndexed { index, name ->
            val destination = File(directory, name)
            val expectedLength = MODEL_LENGTHS[index]
            if (!destination.isFile || destination.length() != expectedLength) {
                val temporary = File(directory, "$name.partial")
                try {
                    appContext.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                        temporary.outputStream().use { input.copyTo(it) }
                    }
                    check(temporary.length() == expectedLength && temporary.renameTo(destination)) { "音声認識モデルの保存に失敗しました" }
                } finally {
                    temporary.delete()
                }
            }
        }
        return directory
    }

    companion object {
        private const val TAG = "OfflineWavTranscriber"
        private const val ASSET_DIRECTORY = "offline-stt"
        private const val MIN_AVAILABLE_MEMORY = 600L * 1024 * 1024
        private const val BOUNDARY_SAMPLES = 4_000
        private val JAPANESE_WORD_SPACES =
            Regex("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])\\s+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])")
        private val MODEL_FILES = listOf("sensevoice-model.int8.onnx", "sensevoice-tokens.txt")
        private val MODEL_LENGTHS = listOf(239233841L, 315894L)
        private val recognizerLock = Mutex()
        private val mutableBusy = MutableStateFlow(false)
        val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    }
}
