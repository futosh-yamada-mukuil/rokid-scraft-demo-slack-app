package com.scraft.glassreport.capture.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCropTest {
    @Test
    fun portraitSceneKeepsItsCenterAndMaximumLandscapePixels() {
        assertEquals(PhotoCrop(0, 882, 3024, 2268), photoCrop(3024, 4032, 0, landscape = true))
        assertEquals(PhotoCrop(0, 882, 3024, 2268), photoCrop(3024, 4032, 180, landscape = true))
    }

    @Test
    fun sidewaysSensorCropBecomesLandscapeAfterUprightRotation() {
        for (rotation in listOf(90, 270, -90)) {
            val crop = photoCrop(4032, 3024, rotation, landscape = true)
            assertEquals(PhotoCrop(882, 0, 2268, 3024), crop)
            assertEquals(4 * crop.width, 3 * crop.height)
        }
    }

    @Test
    fun alreadyLandscapeSourceIsNotCropped() {
        assertEquals(PhotoCrop(0, 0, 4032, 3024), photoCrop(4032, 3024, 0, landscape = true))
    }

    @Test
    fun portraitSelectionAndDownscalingKeepFullSource() {
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(PhotoCrop(0, 0, 4032, 3024), photoCrop(4032, 3024, rotation, landscape = false))
        }
    }

    @Test
    fun oddDimensionsStayCenteredAndExactlyFourByThree() {
        val crop = photoCrop(3025, 4033, 0, landscape = true)
        assertEquals(PhotoCrop(0, 882, 3024, 2268), crop)
        assertEquals(3 * crop.width, 4 * crop.height)
    }
}
