package com.scraft.glassreport.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.scraft.glassreport.capture.tts.SpeechLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 本体のローカル設定（旧 schema v1 と保存互換）。接続先 t/c/u/k は無視する。 */
data class GlassConfig(
    val language: String = "",
    val voiceJa: String = "",
    val voiceEn: String = "",
    val voiceNl: String = "",
    val feedIntervalSec: Int = 0,
    val asrEngine: String = "",
) {
    /** 指定されたローカル設定だけ既存値へ重ねる。空欄で既存設定を消さない。 */
    fun merge(update: GlassConfig): GlassConfig =
        copy(
            language =
                update.language
                    .ifBlank { language }
                    .takeIf(
                        String::isNotBlank,
                    )?.let { SpeechLanguage.forSettings(it).code }
                    .orEmpty(),
            voiceJa = update.voiceJa.ifBlank { voiceJa },
            voiceEn = update.voiceEn.ifBlank { voiceEn },
            voiceNl = update.voiceNl.ifBlank { voiceNl },
            feedIntervalSec = update.feedIntervalSec.takeIf { it > 0 } ?: feedIntervalSec,
            asrEngine = update.asrEngine.ifBlank { asrEngine },
        )

    companion object {
        fun fromSavedJson(raw: String): Result<GlassConfig> =
            runCatching {
                val json = JSONObject(raw)
                require(json.optInt("v", 0) == 1) { "保存設定の形式が不正です" }
                GlassConfig(
                    language =
                        json
                            .optString("l")
                            .trim()
                            .takeIf(String::isNotBlank)
                            ?.let { SpeechLanguage.forSettings(it).code }
                            .orEmpty(),
                    voiceJa = json.optString("vj").trim(),
                    voiceEn = json.optString("ve").trim(),
                    voiceNl = json.optString("vn").trim(),
                    feedIntervalSec = json.optInt("f", 0).coerceIn(0, 3600),
                    asrEngine = json.optString("a").trim(),
                )
            }
    }
}

/** 本体ローカル設定の保存先。APK の Teams 接続先は上書きしない。 */
class GlassConfigStore(
    private val prefs: SharedPreferences,
) {
    private val revisionState = MutableStateFlow(0L)
    val revisions = revisionState.asStateFlow()

    fun load(): GlassConfig = GlassConfig.fromSavedJson(prefs.getString("config", "").orEmpty()).getOrDefault(GlassConfig())

    fun merge(update: GlassConfig) {
        prefs.edit().putString("config", toJson(load().merge(update))).apply()
        revisionState.value++
    }

    private fun toJson(c: GlassConfig) =
        JSONObject()
            .apply {
                put("v", 1)
                if (c.language.isNotBlank()) put("l", c.language)
                if (c.voiceJa.isNotBlank()) put("vj", c.voiceJa)
                if (c.voiceEn.isNotBlank()) put("ve", c.voiceEn)
                if (c.voiceNl.isNotBlank()) put("vn", c.voiceNl)
                if (c.feedIntervalSec > 0) put("f", c.feedIntervalSec)
                if (c.asrEngine.isNotBlank()) put("a", c.asrEngine)
            }.toString()

    companion object {
        fun create(context: Context) = GlassConfigStore(context.getSharedPreferences("glass-config", Context.MODE_PRIVATE))
    }
}
