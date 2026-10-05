package com.scraft.glassreport.net.devmonitor

import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.domain.model.FeedPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/** PCのローカル返信キューを読む。Microsoft Graph/Teamsには接続しない。 */
class LocalReplyClient(
    private val endpointProvider: () -> String,
    private val tokenProvider: () -> String,
    private val deviceName: String = "rokid-glasses",
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build(),
) {
    suspend fun latest(): Result<List<FeedPost>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = endpointProvider().trim()
                require(endpoint.isNotBlank()) { "受信先が未設定" }
                val base = endpoint.substringBeforeLast("/upload", endpoint.trimEnd('/'))
                val url = "$base/api/replies?device=${URLEncoder.encode(deviceName, "UTF-8")}"
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .apply {
                            tokenProvider().takeIf { it.isNotBlank() }?.let { header("X-Auth-Token", it) }
                        }.build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val array = JSONArray(body)
                    List(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        FeedPost(
                            id = item.getString("id"),
                            author = "PC",
                            text = item.optString("translatedText"),
                            createdAtMs = runCatching { Instant.parse(item.optString("createdAt")).toEpochMilli() }.getOrNull(),
                            language = SpeechLanguage.fromCode(item.optString("language")),
                            canSpeak = SpeechLanguage.fromCodeOrNull(item.optString("language")) != null,
                        )
                    }
                }
            }
        }
}
