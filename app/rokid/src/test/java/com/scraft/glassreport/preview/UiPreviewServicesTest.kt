package com.scraft.glassreport.preview

import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MIME_MP4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPreviewServicesTest {
    @Test
    fun sendingPhotoOnlyUpdatesLocalConversation() =
        runTest {
            val preview = UiPreviewServices { 1_000_000 }
            val original = preview.repository.latest().getOrThrow()
            assertTrue(preview.sink.send(byteArrayOf(1, 2), MIME_JPEG, "UI確認", null).isSuccess)
            val posts = preview.repository.latest().getOrThrow()
            assertEquals(original.size + 1, posts.size)
            assertEquals("UI確認", posts.first().text)
            assertEquals(1, posts.first().imageCount)
            assertEquals(true, posts.first().isOwn)
            preview.addReply()
            assertEquals(
                "遠隔担当者",
                preview.repository
                    .latest()
                    .getOrThrow()
                    .first()
                    .author,
            )
        }

    @Test
    fun unsupportedOrEmptyMediaDoesNotChangeLocalConversation() =
        runTest {
            val preview = UiPreviewServices()
            val original = preview.repository.latest().getOrThrow()
            assertTrue(preview.sink.send(byteArrayOf(), MIME_JPEG, "", null).isFailure)
            assertTrue(preview.sink.send(byteArrayOf(1), MIME_MP4, "", null).isFailure)
            assertEquals(original, preview.repository.latest().getOrThrow())
        }

    @Test
    fun authenticationSampleCannotYieldRealCredentials() =
        runTest {
            val auth = UiPreviewServices().auth
            assertFalse(auth.signedIn())
            assertNull(auth.accessToken())
            val code = auth.requestDeviceCode().getOrThrow()
            assertEquals("DEMO1234", code.userCode)
            assertEquals("https://example.invalid/ui-preview", code.verificationUri)
            assertEquals("", code.deviceCode)
        }

    @Test
    fun commentPreviewWorksWithoutMicrophoneOrRecognitionService() =
        runTest {
            val speech = UiPreviewServices().newSpeechSource()
            val session = speech.beginRecording()!!
            session.stop()
            val result = speech.transcribe(session.await().getOrThrow()).getOrThrow()
            assertTrue(result.text.startsWith("UIサンプル："))
            assertFalse(result.silent)
            speech.close()
        }
}
