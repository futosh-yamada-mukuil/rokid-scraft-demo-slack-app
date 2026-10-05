package com.scraft.glassreport.net.slack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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

internal suspend fun relayRequest(
    http: OkHttpClient,
    request: Request,
): RelayResponse =
    withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("Slack中継サーバの応答がありません")
            val source = body.source()
            source.request(2L * 1024 * 1024 + 1)
            val bytes = source.buffer.readByteArray()
            if (bytes.size > 2 * 1024 * 1024) throw IOException("Slack中継サーバの応答が大きすぎます")
            val json =
                runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
                    .getOrElse { throw IOException("Slack中継サーバの応答形式を確認してください") }
            RelayResponse(response.code, json)
        }
    }

internal fun RelayResponse.error(): IOException =
    IOException(json.optString("error").take(140).ifBlank { "Slack中継サーバに接続できません (HTTP $code)" })
