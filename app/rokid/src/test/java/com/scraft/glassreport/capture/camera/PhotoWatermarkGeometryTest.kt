package com.scraft.glassreport.capture.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoWatermarkGeometryTest {
    @Test
    fun landscapeUsesBottomLeftWithShortEdgeMargin() {
        val bounds = PhotoWatermarkGeometry.bounds(4000, 3000)
        assertEquals(PhotoWatermarkBounds(60, 2747, 1000, 193), bounds)
        assertEquals(60, 3000 - bounds.top - bounds.height)
    }

    @Test
    fun portraitUsesTheSameRelativeWidthAndAspect() {
        val bounds = PhotoWatermarkGeometry.bounds(3000, 4000)
        assertEquals(PhotoWatermarkBounds(60, 3795, 750, 145), bounds)
        assertEquals(60, 4000 - bounds.top - bounds.height)
    }

    @Test
    fun previewAndPhotoScaleTogether() {
        val preview = PhotoWatermarkGeometry.bounds(400, 300)
        val photo = PhotoWatermarkGeometry.bounds(4000, 3000)
        assertEquals(preview.left * 10, photo.left)
        // Integer pixel rounding may differ by up to half a preview pixel after scaling.
        assertEquals(preview.top * 10f, photo.top.toFloat(), 5f)
        assertEquals(preview.width * 10, photo.width)
        assertEquals(preview.height * 10f, photo.height.toFloat(), 5f)
    }

    @Test
    fun smallThumbnailStillHasNonEmptyBoundsWithinImage() {
        for ((width, height) in listOf(1 to 1, 192 to 144, 144 to 192)) {
            val bounds = PhotoWatermarkGeometry.bounds(width, height)
            assertTrue(bounds.width > 0 && bounds.height > 0)
            assertTrue(bounds.left >= 0 && bounds.top >= 0)
            assertTrue(bounds.left + bounds.width <= width)
            assertTrue(bounds.top + bounds.height <= height)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() {
        PhotoWatermarkGeometry.bounds(0, 100)
    }
}
