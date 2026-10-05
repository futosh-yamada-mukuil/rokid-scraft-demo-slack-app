package com.scraft.glassreport.feature.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCameraLifecycleTest {
    @Test
    fun nativeRecognitionCleanupBlocksCameraRebindEvenAfterBack() {
        CapturePhase.entries.forEach { assertFalse(it.requiresCamera(recognitionBusy = true)) }
        assertTrue(CapturePhase.IDLE.requiresCamera(recognitionBusy = false))
    }

    @Test fun cameraRemainsUntilCaptureCallbackButIsReleasedForCommentAndRecognition() {
        assertTrue(CapturePhase.CAPTURING.requiresCamera())
        assertTrue(CapturePhase.RECORDING.requiresCamera())
        assertTrue(CapturePhase.IDLE.requiresCamera())
        listOf(
            CapturePhase.COMMENT_CHOICE,
            CapturePhase.LISTENING,
            CapturePhase.COMMENT_REVIEW,
            CapturePhase.COMMENT_FAILED,
            CapturePhase.TRANSCRIBING,
            CapturePhase.CONFIRM,
            CapturePhase.DRAFT_REVIEW,
        ).forEach { assertFalse(it.requiresCamera()) }
    }
}
