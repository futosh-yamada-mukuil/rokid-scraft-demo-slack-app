package com.scraft.glassreport.capture.camera

internal data class PhotoDecodeBand(
    val top: Int,
    val bottom: Int,
    val decode: PhotoCrop,
)

/** Bound each decoded strip to 128 rows (+ filter overlap), independent of the full photo height. */
internal fun photoDecodeBands(
    crop: PhotoCrop,
    sample: Int,
): List<PhotoDecodeBand> {
    require(sample > 0)
    val bottom = crop.top + crop.height
    return (crop.top until bottom step 128 * sample).map { top ->
        val end = minOf(top + 128 * sample, bottom)
        val decodeTop = maxOf(crop.top, top - sample)
        val decodeBottom = minOf(bottom, end + sample)
        PhotoDecodeBand(top, end, PhotoCrop(crop.left, decodeTop, crop.width, decodeBottom - decodeTop))
    }
}
