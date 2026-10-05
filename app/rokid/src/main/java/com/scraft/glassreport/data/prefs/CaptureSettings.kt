package com.scraft.glassreport.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.scraft.glassreport.domain.model.CaptureQualities
import com.scraft.glassreport.domain.model.PhotoQuality
import com.scraft.glassreport.domain.model.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

private const val TAG = "CaptureSettings"

/** 秘密ではないので暗号化しない（トークンだけが EncryptedSharedPreferences）。 */
const val CAPTURE_PREFS_NAME = "capture-settings"

private const val KEY_PHOTO = "photo_quality"
private const val KEY_VIDEO = "video_quality"
private const val KEY_SINK = "default_sink"
private const val KEY_REPLY_FONT_SIZE = "reply_font_size"
private const val KEY_TTS_SPEED = "tts_speed"
const val DEFAULT_REPLY_FONT_SIZE = 20
const val DEFAULT_TTS_SPEED = 1.2f
private const val MIN_TTS_SPEED = 0.75f
private const val MAX_TTS_SPEED = 1.5f

/** 画質・文字サイズ・読み上げ速度のローカル設定。送信先は Teams 固定。 */
class CaptureSettings(
    private val prefs: SharedPreferences,
) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<CaptureQualities> = _state.asStateFlow()

    /** 旧保存値（モニター等）は無視する。上書きインストール後も Teams 以外へ送信しない。 */
    val defaultSink: StateFlow<String> = MutableStateFlow("Teams").asStateFlow()

    init {
        runCatching { prefs.edit().remove(KEY_SINK).apply() }
            .onFailure { Log.w(TAG, "旧送信先設定を削除できませんでした", it) }
    }

    /** 返信本文の大きさ。従来のbodyLargeと同じ20spを初期値にする。 */
    private val _replyFontSize = MutableStateFlow(loadReplyFontSize())
    val replyFontSize: StateFlow<Int> = _replyFontSize.asStateFlow()

    /** Google TTS の話速。1.0 が標準、初期値は少し速い 1.2。 */
    private val _ttsSpeed = MutableStateFlow(loadTtsSpeed())
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    fun adjustReplyFontSize(delta: Int) {
        val next = (_replyFontSize.value + delta).coerceIn(14, 36)
        _replyFontSize.value = next
        runCatching { prefs.edit().putInt(KEY_REPLY_FONT_SIZE, next).apply() }
            .onFailure { Log.w(TAG, "返信文字サイズを保存できませんでした", it) }
    }

    /** 設定画面から ±0.1 ずつ変更する。上下限だけ 0.75 / 1.5 に丸める。 */
    fun adjustTtsSpeed(delta: Float) {
        val next = ((_ttsSpeed.value + delta).coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED) * 100).roundToInt() / 100f
        _ttsSpeed.value = next
        runCatching { prefs.edit().putFloat(KEY_TTS_SPEED, next).apply() }
            .onFailure { Log.w(TAG, "読み上げ速度を保存できませんでした", it) }
    }

    /** 写真の画質を次の候補へ送る（1280 → 1920 → 無加工 → 1280 …）。 */
    fun cyclePhoto() = update(_state.value.copy(photo = _state.value.photo.next()))

    /** 動画の画質を次の候補へ送る（SD → HD → FHD → SD …）。 */
    fun cycleVideo() = update(_state.value.copy(video = _state.value.video.next()))

    private fun update(next: CaptureQualities) {
        _state.value = next
        runCatching {
            prefs
                .edit()
                .putString(KEY_PHOTO, next.photo.name)
                .putString(KEY_VIDEO, next.video.name)
                .apply()
        }.onFailure { Log.w(TAG, "画質設定を保存できませんでした", it) }
        Log.i(TAG, "画質設定 写真=${next.photo.label} 動画=${next.video.label}")
    }

    /** 未設定・壊れた値は既定（写真1920 / 動画FHD）に落とす。 */
    private fun load(): CaptureQualities =
        runCatching {
            CaptureQualities(
                photo = PhotoQuality.from(prefs.getString(KEY_PHOTO, null)),
                video = VideoQuality.from(prefs.getString(KEY_VIDEO, null)),
            )
        }.getOrElse { CaptureQualities() }

    private fun loadReplyFontSize(): Int =
        runCatching { prefs.getInt(KEY_REPLY_FONT_SIZE, DEFAULT_REPLY_FONT_SIZE).coerceIn(14, 36) }
            .getOrElse { DEFAULT_REPLY_FONT_SIZE }

    private fun loadTtsSpeed(): Float =
        runCatching {
            if (prefs.contains(KEY_TTS_SPEED)) {
                prefs.getFloat(KEY_TTS_SPEED, DEFAULT_TTS_SPEED).coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
            } else {
                DEFAULT_TTS_SPEED
            }
        }.getOrElse { DEFAULT_TTS_SPEED }

    companion object {
        fun create(context: Context): CaptureSettings =
            CaptureSettings(context.getSharedPreferences(CAPTURE_PREFS_NAME, Context.MODE_PRIVATE))
    }
}
