package com.scraft.glassreport.net.gtts

import android.os.SystemClock
import android.util.Log
import com.scraft.glassreport.BuildConfig
import com.scraft.glassreport.net.AndroidAppIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "GoogleTtsClient"

/** 合成エンドポイント（API キー方式）。 */
const val GOOGLE_TTS_ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"

/**
 * 音声フォーマット。**MP3 を使う**。
 * LINEAR16 は同じ文で 6 倍のサイズになり転送が伸びる。MP3 は `MediaPlayer` が標準で再生できる。
 */
private const val AUDIO_ENCODING = "MP3"

/** 1 回の合成に送る上限。長文は Google 側で 400 になるため、手前で切る。 */
private const val MAX_INPUT_CHARS = 1_000

/**
 * 読み上げに使う声。
 *
 * `Neural2` を選ぶ理由: 実測で日本語 434ms / 英語 721ms と速い。
 * **`Chirp3-HD` は 1,269〜1,405ms（約 6 倍）で体感が悪いので使わない。**
 */
enum class TtsVoice(
    val languageCode: String,
    val voiceName: String,
) {
    JA("ja-JP", "ja-JP-Neural2-B"),
    EN("en-US", "en-US-Neural2-F"),
    ZH_CN("cmn-CN", "cmn-CN-Standard-A"),
    ZH_TW("cmn-TW", "cmn-TW-Standard-A"),
    KO("ko-KR", "ko-KR-Standard-A"),
    ES("es-ES", "es-ES-Standard-E"),
    FR("fr-FR", "fr-FR-Standard-F"),
    DE("de-DE", "de-DE-Standard-G"),
    IT("it-IT", "it-IT-Standard-E"),
    PT("pt-BR", "pt-BR-Standard-A"),
    RU("ru-RU", "ru-RU-Standard-A"),
    AR("ar-XA", "ar-XA-Standard-A"),
    HI("hi-IN", "hi-IN-Standard-A"),
    NL("nl-NL", "nl-NL-Standard-F"),
    ID("id-ID", "id-ID-Standard-A"),
    VI("vi-VN", "vi-VN-Standard-A"),
    TH("th-TH", "th-TH-Standard-A"),
    TR("tr-TR", "tr-TR-Standard-A"),
    PL("pl-PL", "pl-PL-Standard-F"),
    UK("uk-UA", "uk-UA-Standard-B"),
}

/** 合成できた音声。[mp3] はそのまま `MediaPlayer` に渡せる MP3 のバイト列。 */
class TtsAudio(
    val mp3: ByteArray,
    val voice: TtsVoice,
    val elapsedMs: Long,
)

/** 合成に失敗した理由。[message] は HUD にそのまま出せる短い日本語。 */
class GoogleTtsException(
    message: String,
    val code: Int? = null,
) : IOException(message) {
    /** 無料枠・レート上限に当たった（429）。 */
    val rateLimited: Boolean get() = code == 429
}

/**
 * Google Cloud Text-to-Speech（`text:synthesize`）の呼び出し。
 *
 * ```
 * POST https://texttospeech.googleapis.com/v1/text:synthesize
 * x-goog-api-key: <KEY>
 * Content-Type: application/json; charset=utf-8
 * {"input":{"text":"…"},
 *  "voice":{"languageCode":"ja-JP","name":"ja-JP-Neural2-B"},
 *  "audioConfig":{"audioEncoding":"MP3","speakingRate":1.2}}
 * → {"audioContent":"<base64>"}
 * ```
 *
 * 実機（中国国内版 Rokid Glasses）には `TextToSpeech` を実装するサービスが 1 つも無いため、
 * 読み上げはこのクラウド合成が唯一の手段。例外は投げず [Result] に畳んで返す。
 */
class GoogleTtsClient(
    private val apiKey: String = BuildConfig.GOOGLE_STT_TTS_TRANSLATE_API_KEY,
    private val endpoint: String = GOOGLE_TTS_ENDPOINT,
    private val identity: AndroidAppIdentity? = null,
) {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS) // 実測 189〜234ms。詰まったら諦めて HUD に出す
            .build()

    /** [text] を [voice] の声で合成する。失敗理由は短い日本語で [Result] に入れて返す。 */
    suspend fun synthesize(
        text: String,
        voice: TtsVoice = TtsVoice.JA,
        speakingRate: Float = 1.0f,
    ): Result<TtsAudio> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(GoogleTtsException("読み上げキーが未設定"))
            }
            val body = text.trim().take(MAX_INPUT_CHARS)
            if (body.isEmpty()) {
                return@withContext Result.failure(GoogleTtsException("読み上げる文が空"))
            }
            if (text.trim().length > MAX_INPUT_CHARS) {
                Log.w(TAG, "長すぎる文を $MAX_INPUT_CHARS 文字で切って合成する")
            }

            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .header("x-goog-api-key", apiKey)
                    .apply {
                        // キーに Android アプリ制限を掛けた場合に必須になる 2 ヘッダ。無くても通るが常に付ける。
                        identity?.let {
                            header("X-Android-Package", it.packageName)
                            header("X-Android-Cert", it.certSha1)
                        }
                    }.post(requestJson(body, voice, speakingRate).toRequestBody(JSON_UTF8))
                    .build()

            val startedAt = SystemClock.elapsedRealtime()
            try {
                client.newCall(request).execute().use { res ->
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val payload = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        // API の理由文はログにだけ出す（HUD は短く）。キーは載せない。
                        Log.w(TAG, "合成失敗 HTTP ${res.code}: ${apiErrorMessage(payload).take(200)}")
                        return@withContext Result.failure(GoogleTtsException(hudMessage(res.code), res.code))
                    }
                    val mp3 =
                        decodeAudio(payload)
                            ?: return@withContext Result.failure(GoogleTtsException("音声が空"))
                    Log.i(TAG, "合成成功 ${voice.voiceName} ${mp3.size / 1_024}KB ${elapsed}ms")
                    Result.success(TtsAudio(mp3, voice, elapsed))
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "合成タイムアウト", e)
                Result.failure(GoogleTtsException("読み上げがタイムアウト"))
            } catch (e: IOException) {
                Log.w(TAG, "合成の通信エラー", e)
                Result.failure(GoogleTtsException("読み上げの通信エラー"))
            }
        }

    private fun requestJson(
        text: String,
        voice: TtsVoice,
        speakingRate: Float,
    ): String =
        JSONObject()
            .put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", voice.languageCode).put("name", voice.voiceName))
            .put(
                "audioConfig",
                JSONObject()
                    .put("audioEncoding", AUDIO_ENCODING)
                    .put("speakingRate", speakingRate.takeIf { it.isFinite() }?.coerceIn(0.75f, 1.5f) ?: 1.0f),
            ).toString()

    /** `{"audioContent":"<base64>"}` を MP3 のバイト列へ。壊れていれば null。 */
    private fun decodeAudio(payload: String): ByteArray? {
        val base64 =
            runCatching { JSONObject(payload).optString("audioContent") }
                .getOrElse {
                    Log.w(TAG, "応答 JSON を解釈できない", it)
                    return null
                }
        if (base64.isBlank()) return null
        // 改行混じりでも読めるよう MIME デコーダを使う。
        return runCatching { Base64.getMimeDecoder().decode(base64) }
            .onFailure { Log.w(TAG, "base64 の復号に失敗", it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** `{"error":{"message":"…"}}` の理由文。無ければ本文の先頭。 */
    private fun apiErrorMessage(payload: String): String =
        runCatching { JSONObject(payload).optJSONObject("error")?.optString("message").orEmpty() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: payload

    /** HUD に出す短い理由。原因の切り分けができる粒度に留める。 */
    private fun hudMessage(code: Int): String =
        when (code) {
            400 -> "読み上げ要求が不正"
            401, 403 -> "読み上げキーが無効"
            429 -> "読み上げが混雑中"
            in 500..599 -> "読み上げサーバ障害"
            else -> "読み上げ失敗 HTTP $code"
        }

    private companion object {
        val JSON_UTF8 = "application/json; charset=utf-8".toMediaType()
    }
}
