package com.scraft.glassreport.net.translate

import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.domain.translate.TranslationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CloudTranslationClientTest {
    @Test fun usesSharedKeyHeaderAutoDetectionAndAllFourTargetCodes() =
        runTest {
            MockWebServer().use { server ->
                val client = CloudTranslationClient("dummy", server.url("/translate").toString())
                SpeechLanguage.selectable.forEach { language ->
                    server.enqueue(MockResponse().setBody(response("line one\n- line two &amp; &#39;quote&#39; &lt;b&gt;")))
                    assertEquals("line one\n- line two & 'quote' <b>", client.translate("请检查照片", language).getOrThrow())
                    val request = server.takeRequest()
                    assertEquals("POST", request.method)
                    assertEquals("dummy", request.getHeader("x-goog-api-key"))
                    assertEquals("/translate", request.path)
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals("请检查照片", body.getString("q"))
                    assertEquals(language.code, body.getString("target"))
                    assertEquals("text", body.getString("format"))
                    assertFalse(body.has("source"))
                }
            }
        }

    @Test fun englishCompatibilityAndEscapingArePreserved() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response("Checked.")))
                val client = CloudTranslationClient("dummy", server.url("/").toString())
                assertEquals("Checked.", client.toEnglish("確認\n\"写真\"").getOrThrow())
                val body = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals("確認\n\"写真\"", body.getString("q"))
                assertEquals("en", body.getString("target"))
            }
        }

    @Test fun localValidationNeverTruncatesOrSendsInvalidText() =
        runTest {
            MockWebServer().use { server ->
                val client = CloudTranslationClient("dummy", server.url("/").toString())
                assertTrue(client.toEnglish("x".repeat(1001)).isFailure)
                assertTrue(client.toEnglish(" \n ").isFailure)
                assertTrue(CloudTranslationClient("", server.url("/").toString()).toEnglish("hello").isFailure)
                assertEquals(0, server.requestCount)
            }
        }

    @Test fun httpErrorsAreRedactedAndRetainBackoffClassification() =
        runTest {
            MockWebServer().use { server ->
                val client = CloudTranslationClient("SECRET_KEY", server.url("/").toString())
                listOf(400, 403, 429, 503).forEach { code ->
                    server.enqueue(MockResponse().setResponseCode(code).setBody("SECRET_SERVER_DETAIL"))
                    val error = client.toEnglish("hello").exceptionOrNull() as TranslationException
                    assertEquals(code, error.code)
                    assertFalse(error.toString().contains("SECRET"))
                }
            }
            listOf("{}", "invalid", """{"data":{"translations":[]}}""", response("")).forEach {
                assertTrue(parseCloudTranslation(it).isFailure)
            }
        }

    @Test fun cancelledTranslationCancelsHttp() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val client = CloudTranslationClient("dummy", server.url("/").toString())
                val job = async(Dispatchers.IO) { client.toEnglish("hello") }
                withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                job.cancel()
                job.join()
                assertTrue(job.isCancelled)
            }
        }

    private fun response(text: String): String =
        JSONObject()
            .put(
                "data",
                JSONObject().put(
                    "translations",
                    org.json.JSONArray().put(JSONObject().put("translatedText", text)),
                ),
            ).toString()
}
