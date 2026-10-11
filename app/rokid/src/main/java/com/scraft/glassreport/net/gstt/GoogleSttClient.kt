package com.scraft.glassreport.net.gstt

import com.scraft.glassreport.BuildConfig
import com.scraft.glassreport.capture.asr.WAV_HEADER_BYTES
import com.scraft.glassreport.capture.asr.WavTranscriber
import com.scraft.glassreport.domain.text.TranscriptText
import com.scraft.glassreport.net.AndroidAppIdentity
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

const val GOOGLE_STT_ENDPOINT = "https://speech.googleapis.com/v1/speech:recognize"

/** Direct Google REST call: no relay, audio/transcript/key are never logged. */
class GoogleSttClient(
    private val apiKey: String = BuildConfig.GOOGLE_STT_TTS_TRANSLATE_API_KEY,
    private val endpoint: String = GOOGLE_STT_ENDPOINT,
    private val identity: AndroidAppIdentity? = null,
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build(),
) : WavTranscriber {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    override val available: Boolean get() = apiKey.isNotBlank()
    override val unavailableReason = "Google STT/TTSキーが未設定"

    override suspend fun transcribe(wav: ByteArray): Result<String> {
        if (!available) return Result.failure(IOException(unavailableReason))
        if (wav.size <= WAV_HEADER_BYTES) return Result.failure(IOException("認識する音声が空です"))
        val config =
            JSONObject()
                .put("encoding", "LINEAR16")
                .put("sampleRateHertz", 16000)
                .put("languageCode", "ja-JP")
                .put("alternativeLanguageCodes", JSONArray(listOf("en-US", "cmn-Hans-CN", "ko-KR")))
                .put("model", "command_and_search")
                .put("enableAutomaticPunctuation", true)
        val body = JSONObject().put("config", config).put("audio", JSONObject().put("content", Base64.getEncoder().encodeToString(wav)))
        val request =
            Request
                .Builder()
                .url(endpoint)
                .header("x-goog-api-key", apiKey)
                .apply {
                    identity?.let {
                        header("X-Android-Package", it.packageName)
                        header("X-Android-Cert", it.certSha1)
                    }
                }.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            calls += call
            continuation.invokeOnCancellation {
                call.cancel()
                calls -= call
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        calls -= call
                        if (continuation.isActive) continuation.resume(Result.failure(IOException("Google STT通信失敗・タイムアウト")))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            try {
                                response.use { res ->
                                    if (!res.isSuccessful) {
                                        Result.failure(IOException(sttError(res.code)))
                                    } else {
                                        parseSttResponse(res.body?.string().orEmpty())
                                    }
                                }
                            } catch (_: Exception) {
                                Result.failure(IOException("Google STT応答を読み取れません"))
                            }
                        calls -= call
                        if (continuation.isActive) continuation.resume(result)
                    }
                },
            )
        }
    }

    override fun close() {
        calls.forEach { it.cancel() }
        calls.clear()
    }
}

internal fun sttError(code: Int): String =
    when (code) {
        401, 403 -> "Google STTキー・API権限・課金設定を確認してください"
        429 -> "Google STT利用上限です。後で再試行してください"
        400 -> "Google STT音声形式・設定エラー"
        else -> "Google STTエラー HTTP $code"
    }

internal fun parseSttResponse(body: String): Result<String> =
    runCatching {
        val response = JSONObject(body)
        require(!response.has("error")) { "Google STT応答エラー" }
        val results = response.optJSONArray("results") ?: return@runCatching ""
        // A pause splits one comment into several results; Japanese/Chinese must not gain spaces there.
        TranscriptText.join(
            (0 until results.length()).mapNotNull { index ->
                results
                    .optJSONObject(index)
                    ?.optJSONArray("alternatives")
                    ?.optJSONObject(0)
                    ?.optString("transcript")
            },
        )
    }.recoverCatching { throw IOException("Google STT応答を解釈できません") }
