package com.scraft.glassreport.net.amivoice

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

private const val TAG = "AmiVoiceClient"

/** 同期HTTP・`nolog`（音声を残さない）の認識エンドポイント。 */
const val AMIVOICE_URL = "https://acp-api.amivoice.com/v1/nolog/recognize"

/** 日本語エンジン。3種を比較して最速かつ最良だったもの。 */
const val AMIVOICE_ENGINE_JA = "-a-general"

/** 英語エンジン（Phase 4 の英語運用で使う）。 */
const val AMIVOICE_ENGINE_EN = "-a-general-en"

/** WAV パートの Content-Type。 */
private const val MIME_WAV = "audio/wav"

/** 認識結果。[elapsedMs] は POST 開始から応答までの実測値（実測 0.8 秒前後）。 */
data class Transcript(
    val text: String,
    val elapsedMs: Long,
)

/**
 * AmiVoice が JSON で返したエラー。
 * **HTTP は 200 でもこれが返る**（例: 認証失敗は `code="-"` /
 * `message="received illegal service authorization"`）。
 */
class AmiVoiceException(
    val code: String,
    message: String,
) : IOException(message)

/**
 * AmiVoice 同期HTTP認識クライアント。
 *
 * ```
 * POST https://acp-api.amivoice.com/v1/nolog/recognize
 * Content-Type: multipart/form-data
 *   u = APPKEY
 *   d = -a-general
 *   a = 音声バイナリ   ← 必ず最後のパート（ここを守らないと認識されない）
 * ```
 *
 * **成否は HTTP ステータスではなく JSON の `code` / `message` で判定する。**
 * 成功時は両方空文字。音声は 16kHz / mono / 16bit PCM の WAV（[com.scraft.glassreport.capture.asr.VoiceRecorder]）。
 */
class AmiVoiceClient(
    private val endpoint: String = AMIVOICE_URL,
    private val appKey: String = "",
    private val client: OkHttpClient = defaultClient(),
) {
    /** APPKEY が焼き込まれているか。未設定（`.env` が無いクローン）なら音声コメントは飛ばす。 */
    val configured: Boolean get() = appKey.isNotBlank()

    /**
     * [wav] を [engine] で文字起こしする。例外は投げず、失敗理由を [Result] に畳んで返す
     * （HUD にそのまま出せる短い日本語）。認識できなかった場合は成功・空文字になる。
     */
    suspend fun recognize(
        wav: ByteArray,
        engine: String = AMIVOICE_ENGINE_JA,
    ): Result<Transcript> =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext Result.failure(IllegalStateException("音声認識キーが未設定"))

            // パートの順序は OkHttp が保持する。音声（a）を最後に積むこと。
            val body =
                MultipartBody
                    .Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("u", appKey)
                    .addFormDataPart("d", engine)
                    .addFormDataPart("a", "voice.wav", wav.toRequestBody(MIME_WAV.toMediaType()))
                    .build()
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .post(body)
                    .build()

            val startedAt = SystemClock.elapsedRealtime()
            try {
                client.newCall(request).execute().use { res ->
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val payload = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        Log.w(TAG, "認識失敗 HTTP ${res.code}")
                        return@withContext Result.failure(IOException("HTTP ${res.code}"))
                    }
                    val text = parse(payload).getOrElse { return@withContext Result.failure(it) }
                    Log.i(TAG, "認識成功 ${text.length}文字 ${elapsed}ms")
                    Result.success(Transcript(text = text, elapsedMs = elapsed))
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "認識タイムアウト", e)
                Result.failure(IOException("タイムアウト"))
            } catch (e: IOException) {
                Log.w(TAG, "認識エラー", e)
                Result.failure(IOException(e.message?.take(40) ?: "通信エラー"))
            }
        }

    companion object {
        /** 実測 0.8 秒前後で返るが、テザリングの揺れを見て読み取りは 20 秒まで待つ。 */
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

        /**
         * 応答 JSON から本文を取り出す。**`code` / `message` が入っていればエラー**として
         * [AmiVoiceException] を返す（HTTP ステータスは 200 のまま来る）。
         *
         * 成功例: `{"results":[{…,"text":"配管から水漏れ"}],"utteranceid":"…","text":"配管から水漏れ","code":"","message":""}`
         */
        fun parse(payload: String): Result<String> {
            val json =
                try {
                    JSONObject(payload)
                } catch (e: JSONException) {
                    Log.w(TAG, "応答の解析に失敗", e)
                    return Result.failure(AmiVoiceException(code = "", message = "応答が不正"))
                }
            val code = json.optString("code")
            val message = json.optString("message")
            if (code.isNotBlank() || message.isNotBlank()) {
                return Result.failure(AmiVoiceException(code = code, message = reason(code, message)))
            }
            return Result.success(json.optString("text"))
        }

        /** HUD に出せる短い日本語にする。 */
        private fun reason(
            code: String,
            message: String,
        ): String =
            when {
                message.contains("authorization", ignoreCase = true) -> "認証エラー"
                message.isNotBlank() -> message.take(40)
                else -> "エラー($code)"
            }
    }
}
