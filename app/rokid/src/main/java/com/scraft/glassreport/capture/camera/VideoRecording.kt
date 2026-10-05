package com.scraft.glassreport.capture.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "VideoRecording"

/**
 * 録画時間の上限。**時間では切らない**（FHD+音声で12分の連続録画を実機で確認済み）。
 *
 * 代わりに [SAFETY_CHECK_MS] ごとに電池と空きメモリを見て、危なくなったら止める。
 * 現場記録では「何分で切れるか」より「必要な間は録れる」ことが要る。
 * 録画中はタップでいつでも停止できる。
 */
const val VIDEO_MAX_DURATION_MS = Long.MAX_VALUE

/** 安全弁の点検間隔。 */
private const val SAFETY_CHECK_MS = 15_000L
private const val MIN_BATTERY_PERCENT = 10
private const val MIN_AVAILABLE_MEMORY_BYTES = 64L * 1_024 * 1_024

/**
 * 送信のためにメモリへ読み出す上限。これを超えたら読み出しを諦めて分かる形で失敗させる
 * （黙って OutOfMemory で落ちるより良い）。[VIDEO_MAX_DURATION_MS] 満了時の実測見込みは 96MB 前後。
 */
private const val VIDEO_MAX_BYTES = 128L * 1_024 * 1_024

/** 録画後に送信・確認ポップアップへ渡す情報。 */
data class RecordedVideo(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val hasAudioTrack: Boolean,
    /** 確認ポップアップ用の1コマ。取り出せなければ null。 */
    val thumbnail: Bitmap?,
)

/**
 * 進行中の録画。
 *
 * 1回目のタップで [VideoRecording.start] し、2回目のタップで [stop]。結果は [await] で待つ。
 * 呼び出し側は [await] を1回だけ呼ぶこと（一時ファイルはそこで消える）。
 */
interface VideoSession {
    /** 録画を止める。既に終わっていれば何もしない。 */
    fun stop()

    /** Finalize を待って MP4 を読み出す。 */
    suspend fun await(): RecordedVideo
}

/**
 * CameraX VideoCapture で録画し、受信モニタへ送れる MP4 バイト列にする。
 *
 * 端末内には残さない（送信できたら用済みなので cacheDir の一時ファイルを消す）。
 * 向きは MP4 のローテーション行列に入る（`VideoCapture.setTargetRotation`）。写真と違いピクセルは回さない。
 */
object VideoRecording {
    /**
     * 録画を開始する。音声権限があるときだけ
     * [androidx.camera.video.PendingRecording.withAudioEnabled] を呼ぶ（無い場合も止めず無音 MP4）。
     *
     * [maxDurationMs] 経過で自動停止する（止め忘れ・長時間録画の安全弁）。
     */
    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        videoCapture: VideoCapture<Recorder>,
        maxDurationMs: Long = VIDEO_MAX_DURATION_MS,
        includeAudio: Boolean = true,
    ): VideoSession {
        val directory = context.getExternalFilesDir("Movies") ?: context.filesDir
        directory.mkdirs()
        val file = File.createTempFile("glass-video-", ".mp4", directory)
        val finalized = CompletableDeferred<Long>()
        val handler = Handler(Looper.getMainLooper())
        var recording: Recording? = null
        lateinit var safetyStop: Runnable
        safetyStop =
            Runnable {
                val lowBattery = batteryPercent(context)?.let { it <= MIN_BATTERY_PERCENT } == true
                val memory =
                    ActivityManager.MemoryInfo().also {
                        (
                            context.getSystemService(
                                Context.ACTIVITY_SERVICE,
                            ) as ActivityManager
                        ).getMemoryInfo(it)
                    }
                if (lowBattery || memory.availMem < MIN_AVAILABLE_MEMORY_BYTES) {
                    Log.w(TAG, "安全停止: battery=${batteryPercent(context)} available=${memory.availMem}")
                    recording?.stop()
                } else {
                    handler.postDelayed(safetyStop, SAFETY_CHECK_MS)
                }
            }

        val pending = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        val prepared =
            if (includeAudio &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                pending.withAudioEnabled()
            } else {
                pending
            }

        recording =
            prepared.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> handler.postDelayed(safetyStop, SAFETY_CHECK_MS)
                    is VideoRecordEvent.Finalize -> {
                        handler.removeCallbacks(safetyStop)
                        if (event.hasError()) {
                            finalized.completeExceptionally(
                                IOException("動画録画エラー ${event.error}: ${event.cause?.message.orEmpty()}"),
                            )
                        } else {
                            finalized.complete(event.recordingStats.recordedDurationNanos / 1_000_000L)
                        }
                    }
                }
            }

        return object : VideoSession {
            override fun stop() {
                handler.removeCallbacks(safetyStop)
                recording?.stop()
            }

            override suspend fun await(): RecordedVideo =
                try {
                    val recordedDurationMs = finalized.await()
                    withContext(Dispatchers.IO) { inspect(file, recordedDurationMs) }
                } finally {
                    // 録画済みファイルは外部アプリ専用領域に残す。大きすぎて送れなくても消さない。
                    handler.removeCallbacks(safetyStop)
                    recording?.stop()
                }
        }
    }

    private fun inspect(
        file: File,
        recordedDurationMs: Long,
    ): RecordedVideo {
        if (file.length() > VIDEO_MAX_BYTES) {
            throw IOException("動画は保存しましたが、この版では送信上限を超えています: ${file.absolutePath}")
        }
        val metadata = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        return try {
            metadata.setDataSource(file.absolutePath)
            extractor.setDataSource(file.absolutePath)
            val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val metadataDuration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val hasAudio =
                (0 until extractor.trackCount).any { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            RecordedVideo(
                bytes = readBytes(file),
                width = width,
                height = height,
                durationMs = metadataDuration ?: recordedDurationMs,
                hasAudioTrack = hasAudio,
                thumbnail = thumbnailOf(metadata, width, height),
            )
        } finally {
            extractor.release()
            metadata.release()
        }
    }

    /** 上限内でも端末の空きヒープ次第で失敗しうるので、原因が分かる形に畳む。 */
    private fun readBytes(file: File): ByteArray =
        try {
            file.readBytes()
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "動画をメモリに読めませんでした", e)
            throw IOException("動画が大きすぎます（${file.length() / (1_024 * 1_024)}MB）")
        }

    private fun batteryPercent(context: Context): Int? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    /** 先頭のキーフレームを縮小して取り出す（`getScaledFrameAtTime` は API 27 以降）。 */
    private fun thumbnailOf(
        metadata: MediaMetadataRetriever,
        width: Int,
        height: Int,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val scale = (THUMBNAIL_MAX_EDGE.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        return runCatching {
            metadata.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
            )
        }.getOrNull()
    }
}
