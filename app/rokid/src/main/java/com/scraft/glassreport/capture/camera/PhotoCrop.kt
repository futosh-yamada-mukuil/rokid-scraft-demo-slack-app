package com.scraft.glassreport.capture.camera

internal data class PhotoCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** Prefer CameraX's shared Preview/ImageCapture viewport without applying a second center crop. */
internal fun capturePhotoCrop(
    width: Int,
    height: Int,
    rotationDegrees: Int,
    landscape: Boolean,
    viewport: PhotoCrop?,
): PhotoCrop {
    val crop = viewport ?: photoCrop(width, height, rotationDegrees, landscape)
    require(crop.width > 0 && crop.height > 0 && crop.left >= 0 && crop.top >= 0)
    require(crop.width <= width - crop.left && crop.height <= height - crop.top)
    return crop
}

/** Sensor-space bounds whose upright result is a centered landscape 4:3 image. */
internal fun photoCrop(
    width: Int,
    height: Int,
    rotationDegrees: Int,
    landscape: Boolean,
): PhotoCrop {
    require(width > 0 && height > 0)
    if (!landscape) return PhotoCrop(0, 0, width, height)
    require(rotationDegrees % 90 == 0)
    val swapAxes = rotationDegrees % 180 != 0
    val widthUnits = if (swapAxes) 3 else 4
    val heightUnits = if (swapAxes) 4 else 3
    val unit = minOf(width / widthUnits, height / heightUnits)
    require(unit > 0) { "画像が小さすぎます" }
    val cropWidth = unit * widthUnits
    val cropHeight = unit * heightUnits
    return PhotoCrop((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight)
}
