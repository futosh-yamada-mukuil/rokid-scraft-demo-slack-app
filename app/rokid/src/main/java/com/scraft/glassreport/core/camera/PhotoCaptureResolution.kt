package com.scraft.glassreport.core.camera

/** Rokid's 12 MP HAL capture repeatedly triggers LMK before JPEG delivery; cap the sensor stream. */
internal fun isSafePhotoResolution(
    width: Int,
    height: Int,
): Boolean = width > 0 && height > 0 && maxOf(width, height) <= 2048 && minOf(width, height) <= 1536
