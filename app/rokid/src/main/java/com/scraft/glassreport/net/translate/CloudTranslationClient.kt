package com.scraft.glassreport.net.translate

import com.scraft.glassreport.BuildConfig
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.domain.text.HtmlText
import com.scraft.glassreport.domain.translate.TranslationException
import com.scraft.glassreport.domain.translate.Translator
import com.scraft.glassreport.net.AndroidAppIdentity
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

const val CLOUD_TRANSLATION_ENDPOINT = "https://translation.googleapis.com/language/translate/v2"

/** Cloud Translation Basic supports API keys. Source is omitted for automatic language detection. */
class CloudTranslationClient(
    private val apiKey: String = BuildConfig.GOOGLE_STT_TTS_TRANSLATE_API_KEY,
    private val endpoint: String = CLOUD_TRANSLATION_ENDPOINT,
    private val identity: AndroidAppIdentity? = null,
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build(),
) : Translator {
    override suspend fun toEnglish(text: String): Result<String> = translate(text, SpeechLanguage.EN)

    override suspend fun translate(
        text: String,
        targetLanguage: SpeechLanguage,
    ): Result<String> {
        if (apiKey.isBlank()) return Result.failure(TranslationException("Google翻訳キーが未設定"))
        val source = text.trim()
        if (source.isBlank()) return Result.failure(TranslationException("翻訳する文が空"))
        // Preserve the existing bounded-feed contract: never silently truncate a received message.
        if (source.length > 1000) return Result.failure(TranslationException("本文が翻訳上限を超えています"))
        val body = JSONObject().put("q", source).put("target", targetLanguage.code).put("format", "text")
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
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resume(Result.failure(TranslationException("Google翻訳の通信失敗・タイムアウト")))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            try {
                                response.use { res ->
                                    if (!res.isSuccessful) {
                                        Result.failure(TranslationException(translationError(res.code), res.code))
                                    } else {
                                        parseCloudTranslation(res.body?.string().orEmpty())
                                    }
                                }
                            } catch (_: Exception) {
                                Result.failure(TranslationException("Google翻訳の応答を読み取れません"))
                            }
                        if (continuation.isActive) continuation.resume(result)
                    }
                },
            )
        }
    }
}

internal fun translationError(code: Int): String =
    when (code) {
        400 -> "Google翻訳キー・言語設定を確認してください"
        401, 403 -> "Google翻訳キー・API権限・課金設定を確認してください"
        429 -> "Google翻訳の利用上限です。後で再試行してください"
        else -> "Google翻訳エラー HTTP $code"
    }

internal fun parseCloudTranslation(body: String): Result<String> =
    runCatching {
        val json = JSONObject(body)
        require(!json.has("error"))
        val translations = json.getJSONObject("data").getJSONArray("translations")
        require(translations.length() == 1)
        // Decode entities without interpreting literal tags or collapsing line breaks.
        HtmlText.unescapeEntities(translations.getJSONObject(0).getString("translatedText")).trim().also { require(it.isNotEmpty()) }
    }.recoverCatching { throw TranslationException("Google翻訳の応答を解釈できません") }
