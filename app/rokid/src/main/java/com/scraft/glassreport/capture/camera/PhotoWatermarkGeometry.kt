package com.scraft.glassreport.capture.camera

import kotlin.math.roundToInt

data class PhotoWatermarkBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** Shared by the preview and the final upright JPEG; dimensions are image pixels, not screen dp. */
object PhotoWatermarkGeometry {
    const val WIDTH_FRACTION = 0.25f
    const val MARGIN_FRACTION = 0.02f
    const val ASPECT_RATIO = 176f / 34f
    const val TINT_ARGB: Long = 0xFF39FF14

    fun bounds(
        width: Int,
        height: Int,
    ): PhotoWatermarkBounds {
        require(width > 0 && height > 0)
        val margin = (minOf(width, height) * MARGIN_FRACTION).roundToInt()
        val logoWidth = (width * WIDTH_FRACTION).roundToInt().coerceAtLeast(1)
        val logoHeight = (logoWidth / ASPECT_RATIO).roundToInt().coerceIn(1, height)
        return PhotoWatermarkBounds(margin, (height - margin - logoHeight).coerceAtLeast(0), logoWidth, logoHeight)
    }
}
