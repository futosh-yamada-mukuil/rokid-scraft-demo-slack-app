package com.scraft.glassreport.core.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCaptureResolutionTest {
    @Test
    fun acceptsSafeSizesInEitherOrientation() {
        listOf(2048 to 1536, 1536 to 2048, 2016 to 1512, 1600 to 1200, 640 to 480).forEach { (width, height) ->
            assertTrue(isSafePhotoResolution(width, height))
        }
    }

    @Test
    fun rejectsLargerStreamsRatherThanFallingBackUpward() {
        listOf(4032 to 3024, 3024 to 4032, 2049 to 1200, 2048 to 1537, 0 to 480).forEach { (width, height) ->
            assertFalse(isSafePhotoResolution(width, height))
        }
    }
}
