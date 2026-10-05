package com.scraft.glassreport.core.sound

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.util.Log
import com.scraft.glassreport.R

private const val TAG = "Chime"

/** 鳴らす長さ。短い「ポーン」で気づければよい（現場の邪魔をしない）。 */
private const val TONE_MS = 250

/** 音量（0〜100）。装着型スピーカーなので大きすぎないところに置く。 */
private const val TONE_VOLUME = 80

/**
 * 新着などを知らせる短い通知音。
 *
 * **LED は一般アプリから制御できない**端末なので、通知は音と画面表示だけが手段
 * （`specs/デバイスメモ.md`／外部設計書 5 章）。実装を差し替えられるようインターフェースにしておく。
 */
interface Chime {
    fun play()

    fun close()
}

/** 何もしない実装（テストや通知音を切りたい構成用）。 */
object NoopChime : Chime {
    override fun play() {}

    override fun close() {}
}

/** macOS Blow を AAC に変換した通知音を鳴らす。再生できない端末では短いOSトーンへ退避。 */
class ToneChime(
    private val context: Context,
    private val stream: Int = AudioManager.STREAM_NOTIFICATION,
    private val volume: Int = TONE_VOLUME,
) : Chime {
    private var generator: ToneGenerator? = null
    private var player: MediaPlayer? = null
    private var unavailable = false

    override fun play() {
        player?.release()
        player = null
        val started =
            runCatching {
                MediaPlayer.create(context, R.raw.notification_blow)?.also { media ->
                    player = media
                    media.setOnCompletionListener { finished ->
                        finished.release()
                        if (player === finished) player = null
                    }
                    media.start()
                } != null
            }.onFailure { Log.w(TAG, "通知音ファイルを鳴らせない", it) }.getOrDefault(false)
        if (started) return

        val tone = generator ?: create() ?: return
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_MS) }
            .onFailure { Log.w(TAG, "通知音を鳴らせない", it) }
    }

    private fun create(): ToneGenerator? {
        if (unavailable) return null
        return runCatching { ToneGenerator(stream, volume) }
            .onFailure {
                Log.w(TAG, "ToneGenerator を作れない（以後は無音で続ける）", it)
                unavailable = true
            }.getOrNull()
            ?.also { generator = it }
    }

    override fun close() {
        runCatching { player?.release() }
        player = null
        runCatching { generator?.release() }
        generator = null
    }
}
