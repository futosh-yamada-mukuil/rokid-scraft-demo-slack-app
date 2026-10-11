package com.scraft.glassreport.net.slack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class RelayResponse(
    val code: Int,
    val json: JSONObject,
)

/** The debug AVD can reach the PC over adb reverse. Deployed relay addresses require HTTPS. */
internal fun validRelayUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.username.isEmpty() &&
        url.password.isEmpty() &&
        url.query == null &&
        url.fragment == null &&
        (url.isHttps || url.host in setOf("127.0.0.1", "localhost", "::1"))
}

internal fun relayHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(130, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/**
 * [cancellable] は中継サーバが応答を保留する取得（変化待ち）用。呼び出し側が取り消したら、
 * 最大数十秒の待ちが終わるのを待たずに接続を閉じる。
 * 写真の送信など通常の要求は従来どおり、画面を離れても最後まで実行する。
 */
internal suspend fun relayRequest(
    http: OkHttpClient,
    request: Request,
    cancellable: Boolean = false,
): RelayResponse {
    if (!cancellable) return withContext(Dispatchers.IO) { http.newCall(request).execute().use(::readRelayResponse) }
    return suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val result = runCatching { response.use(::readRelayResponse) }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            },
        )
    }
}

private fun readRelayResponse(response: Response): RelayResponse {
    val source = response.body.source()
    source.request(2L * 1024 * 1024 + 1)
    val bytes = source.buffer.readByteArray()
    if (bytes.size > 2 * 1024 * 1024) throw IOException("Slack中継サーバの応答が大きすぎます")
    val json =
        runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw IOException("Slack中継サーバの応答形式を確認してください") }
    return RelayResponse(response.code, json)
}

internal fun RelayResponse.error(): IOException =
    IOException(json.optString("error").take(140).ifBlank { "Slack中継サーバに接続できません (HTTP $code)" })
