package com.scraft.glassreport.feature.capture

/** Release HAL buffers after the capture callback, before the optional recording/recognition flow. */
internal fun CapturePhase.requiresCamera(recognitionBusy: Boolean = false): Boolean =
    !recognitionBusy &&
        when (this) {
            CapturePhase.IDLE, CapturePhase.CAPTURING, CapturePhase.RECORDING, CapturePhase.DONE, CapturePhase.FAILED -> true
            else -> false
        }
