package com.scraft.glassreport.net.devmonitor

import android.os.SystemClock
import android.util.Log
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.net.JsonLite
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MIME_MP4
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "UploadClient"

/** 送信できたときの結果。[elapsedMs] は POST 開始から応答までの実測値。 */
data class UploadResult(
    val id: String,
    val kind: String,
    val bytes: Int,
    val elapsedMs: Long,
)

/**
 * 開発用の受信モニタ（`app/api`）へ写真／動画を送るクライアント。
 *
 * 宛先は `.env` の `DEV_UPLOAD_URL` → [BuildConfig.DEV_UPLOAD_URL]。同一 LAN の平文 HTTP なので
 * debug ビルドでのみ通る（`src/debug/AndroidManifest.xml` で cleartext を許可）。
 *
 * サーバの受け口は「生バイト方式」。
 * ```
 * POST <DEV_UPLOAD_URL>
 * Content-Type: image/jpeg | video/mp4    ← これ以外は 415 で拒否される
 * X-Device: rokid-glasses
 * X-Comment: <URLエンコードした文字列>
 * body: JPEG / MP4 のバイト列
 * ```
 *
 * @param endpointProvider 宛先を**送信のたびに読む**。設定QR を読んだ直後から新しい宛先へ
 *   飛ばせるようにするため、起動時の値で固定しない（会場では網が変わるたびに読ませ直す）。
 */
class UploadClient(
    private val endpointProvider: () -> String = { "" },
    private val deviceName: String = "rokid-glasses",
    /** 受信サーバの共有トークン。設定QR で受け取る。送信のたびに読む。 */
    private val tokenProvider: () -> String = { "" },
    /** PCからの返信をこの言語へ翻訳する。ja / zh-CN / en / ko。 */
    private val languageProvider: () -> String = { "ja" },
    /** 撮影コメントの文字起こし済みテキストを保存し、PC側で再解析・翻訳しない。 */
    private val analyzeOnServer: Boolean = true,
) : MediaSink {
    /** 宛先が変わらない場合（テストや固定運用）向け。 */
    constructor(endpoint: String, deviceName: String = "rokid-glasses") : this({ endpoint }, deviceName, { "" })

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS) // 動画は数MBあるので長めに取る
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /** 宛先のホスト部（`192.168.0.27:8787`）。HUD にデバッグ表示する用。未設定なら null。 */
    val host: String? get() =
        endpointProvider()
            .takeIf { it.isNotBlank() }
            ?.substringAfter("://", "")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }

    val configured: Boolean get() = endpointProvider().isNotBlank()

    // ── MediaSink（撮影画面からは宛先を区別せずに呼ばれる） ─────────────────────
    override val label: String = "モニタ"
    override val detail: String get() = host ?: "宛先未設定"

    /** 受信モニタは写真も動画も受ける（動画を受けられる宛先はこちらだけ）。 */
    override fun accepts(mime: String): Boolean = mime == MIME_JPEG || mime == MIME_MP4

    override suspend fun send(
        bytes: ByteArray,
        mime: String,
        comment: String,
        voice: ByteArray?,
    ): Result<SendOutcome> = upload(bytes, mime, comment, voice).map { SendOutcome(it.elapsedMs) }

    /**
     * [bytes] を [mime]（[MIME_JPEG] / [MIME_MP4]）として POST する。
     * 例外は投げず、失敗理由を [Result] に畳んで返す（HUD にそのまま出せる短い日本語）。
     */
    suspend fun upload(
        bytes: ByteArray,
        mime: String,
        comment: String = "",
        voice: ByteArray? = null,
    ): Result<UploadResult> =
        withContext(Dispatchers.IO) {
            val endpoint = endpointProvider()
            if (endpoint.isBlank()) {
                return@withContext Result.failure(IllegalStateException("送信先が未設定"))
            }
            val url =
                endpoint.toHttpUrlOrNull()
                    ?: return@withContext Result.failure(IllegalArgumentException("送信先URLが不正です"))
            if (!accepts(mime)) return@withContext Result.failure(IllegalArgumentException("送信形式が非対応です"))
            val language = SpeechLanguage.forSettings(languageProvider()).code
            val body =
                if (voice != null) {
                    MultipartBody
                        .Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "media",
                            if (mime ==
                                MIME_MP4
                            ) {
                                "capture.mp4"
                            } else {
                                "capture.jpg"
                            },
                            bytes.toRequestBody(mime.toMediaType()),
                        ).addFormDataPart("audio", "voice.wav", voice.toRequestBody("audio/wav".toMediaType()))
                        .addFormDataPart("comment", comment)
                        .addFormDataPart("language", language)
                        .build()
                } else {
                    bytes.toRequestBody(mime.toMediaType())
                }
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("X-Device", deviceName)
                    // 未設定なら付けない。トークンを要求しないサーバ相手でも動かせるようにする。
                    .apply { tokenProvider().takeIf { it.isNotBlank() }?.let { header("X-Auth-Token", it) } }
                    .header("X-Comment", encodeHeader(comment))
                    .header("X-Language", language)
                    .apply { if (!analyzeOnServer) header("X-Skip-Analysis", "true") }
                    .post(body)
                    .build()

            val startedAt = SystemClock.elapsedRealtime()
            try {
                client.newCall(request).execute().use { res ->
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        Log.w(TAG, "送信失敗 HTTP ${res.code}: ${body.take(200)}")
                        return@withContext Result.failure(IOException("HTTP ${res.code}"))
                    }
                    val result =
                        UploadResult(
                            id = JsonLite.string(body, "id").orEmpty(),
                            kind = JsonLite.string(body, "kind") ?: if (mime == MIME_MP4) "video" else "photo",
                            bytes = bytes.size,
                            elapsedMs = elapsed,
                        )
                    Log.i(TAG, "送信成功 id=${result.id} kind=${result.kind} ${bytes.size / 1024}KB ${elapsed}ms")
                    Result.success(result)
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "送信タイムアウト", e)
                Result.failure(IOException("タイムアウト"))
            } catch (e: IOException) {
                Log.w(TAG, "送信エラー", e)
                Result.failure(IOException(e.message?.take(40) ?: "通信エラー"))
            }
        }

    /** ヘッダは ASCII しか通らないので URL エンコードする（サーバ側は unquote するだけ）。 */
    private fun encodeHeader(value: String): String = if (value.isBlank()) "" else URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
