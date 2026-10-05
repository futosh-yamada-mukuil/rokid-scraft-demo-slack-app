package com.scraft.glassreport.net.devmonitor

import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MIME_MP4
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploadClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun host_extractsAuthorityFromEndpoint() {
        assertEquals("192.168.1.5:8787", UploadClient("http://192.168.1.5:8787/upload").host)
        assertNull(UploadClient("").host)
    }

    @Test
    fun blankEndpoint_returnsFailureWithoutRequest() =
        runTest {
            val result = UploadClient("").upload(byteArrayOf(1), MIME_JPEG)

            assertTrue(result.isFailure)
            assertEquals("送信先が未設定", result.exceptionOrNull()?.message)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun jpegUpload_sendsBodyAndHeadersAndParsesResponse() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"id":"p-1","kind":"photo"}"""))
            val bytes = byteArrayOf(1, 2, 3, 4)
            val client = UploadClient(server.url("/upload").toString(), deviceName = "test-glass")

            val result = client.upload(bytes, MIME_JPEG, "配管 異常").getOrThrow()
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/upload", request.path)
            assertEquals(MIME_JPEG, request.getHeader("Content-Type"))
            assertEquals("test-glass", request.getHeader("X-Device"))
            assertEquals("%E9%85%8D%E7%AE%A1%20%E7%95%B0%E5%B8%B8", request.getHeader("X-Comment"))
            assertArrayEquals(bytes, request.body.readByteArray())
            assertEquals("p-1", result.id)
            assertEquals("photo", result.kind)
            assertEquals(bytes.size, result.bytes)
        }

    @Test
    fun missingKind_fallsBackToMimeType() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"id":"v-1"}"""))

            val result = UploadClient(server.url("/upload").toString()).upload(byteArrayOf(9), MIME_MP4).getOrThrow()

            assertEquals("video", result.kind)
            assertEquals(MIME_MP4, server.takeRequest().getHeader("Content-Type"))
        }

    @Test
    fun voiceUpload_usesMultipartAndIncludesTheReplyLanguage() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"id":"p-voice","kind":"photo"}"""))
            val url = server.url("/upload").toString()
            val client =
                UploadClient(
                    endpointProvider = { url },
                    deviceName = "android-phone",
                    languageProvider = { "zh-CN" },
                )

            client.upload(byteArrayOf(1, 2), MIME_JPEG, voice = byteArrayOf(3, 4)).getOrThrow()
            val request = server.takeRequest()

            assertTrue(request.getHeader("Content-Type")?.startsWith("multipart/form-data") == true)
            assertEquals("zh-CN", request.getHeader("X-Language"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"media\""))
            assertTrue(body.contains("name=\"audio\""))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("zh-CN"))
        }

    @Test
    fun invalidEndpoint_returnsFailureInsteadOfThrowing() =
        runTest {
            listOf("http://", "https://", "not a URL").forEach { endpoint ->
                assertEquals("送信先URLが不正です", UploadClient(endpoint).upload(byteArrayOf(1), MIME_JPEG).exceptionOrNull()?.message)
            }
        }

    @Test
    fun allFourReplyLanguagesArePreservedInUploadHeaders() =
        runTest {
            listOf("ja", "zh-CN", "en", "ko").forEach { language ->
                server.enqueue(MockResponse().setBody("{}"))
                UploadClient(endpointProvider = { server.url("/upload").toString() }, languageProvider = { language })
                    .upload(byteArrayOf(1), MIME_JPEG)
                    .getOrThrow()
                assertEquals(language, server.takeRequest().getHeader("X-Language"))
            }
        }

    @Test
    fun sink_acceptsPhotoAndVideoOnly() {
        val client = UploadClient("http://192.168.1.5:8787/upload")

        assertEquals("モニタ", client.label)
        assertEquals("192.168.1.5:8787", client.detail)
        assertTrue(client.accepts(MIME_JPEG))
        assertTrue(client.accepts(MIME_MP4))
        assertFalse(client.accepts("audio/wav"))
        assertEquals("宛先未設定", UploadClient("").detail)
    }

    @Test
    fun sink_sendGoesThroughTheSameUploadPath() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"id":"p-9","kind":"photo"}"""))

            val outcome = UploadClient(server.url("/upload").toString()).send(byteArrayOf(7), MIME_JPEG, "配管").getOrThrow()
            val request = server.takeRequest()

            assertEquals(MIME_JPEG, request.getHeader("Content-Type"))
            assertEquals("%E9%85%8D%E7%AE%A1", request.getHeader("X-Comment"))
            assertTrue(outcome.elapsedMs >= 0)
        }

    @Test
    fun httpError_isReturnedAsFailure() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(415).setBody("unsupported"))

            val result = UploadClient(server.url("/upload").toString()).upload(byteArrayOf(1), MIME_JPEG)

            assertTrue(result.isFailure)
            assertEquals("HTTP 415", result.exceptionOrNull()?.message)
        }

    /**
     * 設定QR を読んだ直後から新しい宛先へ飛ぶこと。
     * 起動時の値で固定していると、会場で QR を読ませても再起動するまで前の宛先に送り続ける。
     */
    @Test
    fun endpointIsReReadOnEverySend() =
        runTest {
            var endpoint = "http://old.example:8787/upload"
            val client = UploadClient(endpointProvider = { endpoint })
            assertEquals("old.example:8787", client.host)

            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"1\"}"))
                server.start()
                // QR を読んだ想定で宛先だけ差し替える。client は作り直さない。
                endpoint = server.url("/upload").toString()

                val result = client.upload(byteArrayOf(1), MIME_JPEG)

                assertTrue(result.isSuccess)
                assertEquals(server.hostName + ":" + server.port, client.host)
            }
        }

    /** 設定QR のトークンを送ること。無いサーバ相手では付けないこと。 */
    @Test
    fun sendsTheConfiguredTokenHeader() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"1\"}"))
                server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"2\"}"))
                server.start()
                val url = server.url("/upload").toString()

                UploadClient(endpointProvider = { url }, tokenProvider = { "secret-1" })
                    .upload(byteArrayOf(1), MIME_JPEG)
                assertEquals("secret-1", server.takeRequest().getHeader("X-Auth-Token"))

                UploadClient(endpointProvider = { url }, tokenProvider = { "" })
                    .upload(byteArrayOf(1), MIME_JPEG)
                assertNull(server.takeRequest().getHeader("X-Auth-Token"))
            }
        }
}
