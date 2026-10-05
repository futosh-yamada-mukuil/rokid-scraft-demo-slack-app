package com.scraft.glassreport.capture.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDecodeBandsTest {
    @Test
    fun fullResolutionBandsCoverCropWithoutGapsAndKeepDecodeMemoryBounded() {
        val crop = PhotoCrop(882, 0, 2268, 3024)
        checkBands(crop, 1)
    }

    @Test
    fun sampledOddCropKeepsOffsetsAndFilterNeighbours() {
        val crop = PhotoCrop(13, 29, 3011, 3999)
        for (sample in listOf(1, 2, 4, 8)) checkBands(crop, sample)
    }

    private fun checkBands(
        crop: PhotoCrop,
        sample: Int,
    ) {
        val bands = photoDecodeBands(crop, sample)
        assertEquals(crop.top, bands.first().top)
        assertEquals(crop.top + crop.height, bands.last().bottom)
        bands.zipWithNext().forEach { (left, right) -> assertEquals(left.bottom, right.top) }
        bands.forEach { band ->
            assertEquals(crop.left, band.decode.left)
            assertEquals(crop.width, band.decode.width)
            assertTrue(band.decode.height <= 130 * sample)
            assertTrue(band.decode.top <= band.top)
            assertTrue(band.decode.top + band.decode.height >= band.bottom)
            assertTrue(band.decode.top >= crop.top)
            assertTrue(band.decode.top + band.decode.height <= crop.top + crop.height)
        }
    }
}
