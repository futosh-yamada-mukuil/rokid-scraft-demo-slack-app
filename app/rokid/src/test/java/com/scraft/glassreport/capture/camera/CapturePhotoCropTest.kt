package com.scraft.glassreport.capture.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePhotoCropTest {
    @Test
    fun preservesViewportSensorCropForBothOrientationsAndRotations() {
        val viewport = PhotoCrop(400, 600, 2400, 1800)
        for (rotation in listOf(0, 90, 180, 270)) {
            for (landscape in listOf(false, true)) {
                assertEquals(viewport, capturePhotoCrop(4000, 3000, rotation, landscape, viewport))
            }
        }
    }

    @Test
    fun missingViewportRetainsOriginalPortraitAndLandscapeFallback() {
        for (landscape in listOf(false, true)) {
            assertEquals(photoCrop(4000, 3000, 270, landscape), capturePhotoCrop(4000, 3000, 270, landscape, null))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsViewportOutsideJpeg() {
        capturePhotoCrop(4000, 3000, 0, false, PhotoCrop(3000, 0, 2000, 3000))
    }
}
