package com.scraft.glassreport.capture.camera

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import com.scraft.glassreport.R

/** Paint directly on the final mutable bitmap: no second full-size watermark bitmap. */
internal fun stampPhotoWatermark(
    bitmap: Bitmap,
    resources: Resources,
) {
    check(bitmap.isMutable)
    val bounds = PhotoWatermarkGeometry.bounds(bitmap.width, bitmap.height)
    val logo = resources.getDrawable(R.drawable.logo_mukuil, null).mutate()
    logo.setTint(PhotoWatermarkGeometry.TINT_ARGB.toInt())
    logo.setBounds(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
    logo.draw(Canvas(bitmap))
}
