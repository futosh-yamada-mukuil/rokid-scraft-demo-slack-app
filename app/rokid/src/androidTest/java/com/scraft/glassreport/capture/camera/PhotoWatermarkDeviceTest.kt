package com.scraft.glassreport.capture.camera

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import java.io.ByteArrayOutputStream

/** Offline pixel checks using generated images only. Never opens an Activity or calls the network. */
class PhotoWatermarkDeviceTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            testViewportRotationWatermarkAndThumbnail()
            testMaximumPhotoAndDownscalePreserveSingleStamp()
            testBandBoundariesMatchWholeImageTransform()
            result.putString("stream", "OK (3 generated-image pixel tests)")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    fun testViewportRotationWatermarkAndThumbnail() {
        val photo =
            PhotoCapture.toUprightPhoto(
                testJpeg(),
                rotationDegrees = 90,
                maxEdge = Int.MAX_VALUE,
                sourceCrop = Rect(200, 150, 600, 450),
                watermarkResources = targetContext.resources,
            )
        assertEquals(300, photo.width)
        assertEquals(400, photo.height)
        val image = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size)
        try {
            // A clockwise rotation: bottom-left -> top-left, top-left -> top-right.
            assertColorNear(Color.YELLOW, image.getPixel(5, 5))
            assertColorNear(Color.RED, image.getPixel(image.width - 6, 5))
            // Sample above the logo: JPEG chroma can bleed into the adjacent corner pixels.
            assertColorNear(Color.WHITE, image.getPixel(5, image.height / 2 + 10))
            assertColorNear(Color.BLUE, image.getPixel(image.width - 6, image.height - 6))
            assertSingleBottomLeftLogo(image)
            val thumbnail = photo.thumbnail ?: error("Thumbnail missing")
            assertEquals(144, thumbnail.width)
            assertEquals(192, thumbnail.height)
            assertSingleBottomLeftLogo(thumbnail)
        } finally {
            image.recycle()
            photo.thumbnail?.recycle()
        }
    }

    fun testMaximumPhotoAndDownscalePreserveSingleStamp() {
        val photo =
            PhotoCapture.toUprightPhoto(
                testJpeg(),
                rotationDegrees = 0,
                maxEdge = Int.MAX_VALUE,
                watermarkResources = targetContext.resources,
            )
        assertEquals(800, photo.width)
        assertEquals(600, photo.height)
        val original = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size)
        try {
            assertSingleBottomLeftLogo(original)
            assertSame(photo.jpeg, PhotoCapture.downscale(photo.jpeg, maxEdge = 800))
            val expected = PhotoCapture.toUprightPhoto(photo.jpeg, rotationDegrees = 0, maxEdge = 200)
            try {
                val scaled = PhotoCapture.downscale(photo.jpeg, maxEdge = 200)
                assertTrue("Downscale must only resize the existing stamp", expected.jpeg.contentEquals(scaled))
                val image = BitmapFactory.decodeByteArray(scaled, 0, scaled.size)
                try {
                    assertEquals(200, image.width)
                    assertEquals(150, image.height)
                    assertSingleBottomLeftLogo(image)
                } finally {
                    image.recycle()
                }
            } finally {
                expected.thumbnail?.recycle()
            }
        } finally {
            original.recycle()
            photo.thumbnail?.recycle()
        }
    }

    fun testBandBoundariesMatchWholeImageTransform() {
        val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(800 * 600) { index -> Color.rgb((index % 800) * 255 / 799, (index / 800) * 255 / 599, 80) }
        source.setPixels(pixels, 0, 800, 0, 0, 800, 600)
        val jpeg = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
        source.recycle()
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        try {
            // Odd offsets exercise decoder sampling alignment; the second crop is exactly landscape 4:3
            // after 90/270-degree rotation. Every output row is checked, including strip boundaries.
            for (crop in listOf(Rect(37, 29, 773, 587), Rect(200, 100, 500, 500))) {
                for (rotation in listOf(0, 90, 270)) {
                    for (maxEdge in listOf(Int.MAX_VALUE, 190)) {
                        val photo = PhotoCapture.toUprightPhoto(jpeg, rotation, maxEdge, sourceCrop = crop)
                        val actual = BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size)
                        val reference = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
                        try {
                            val scale = (maxEdge.toFloat() / maxOf(crop.width(), crop.height())).coerceAtMost(1f)
                            val matrix = Matrix().apply { setRotate(rotation.toFloat()) }
                            val rotated = RectF().also { matrix.mapRect(it, RectF(crop)) }
                            matrix.postTranslate(-rotated.left, -rotated.top)
                            matrix.postScale(scale, scale)
                            Canvas(reference).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                            for (y in 0 until photo.height) {
                                for (x in 0 until photo.width) {
                                    val expected = reference.getPixel(x, y)
                                    val observed = actual.getPixel(x, y)
                                    val delta =
                                        maxOf(
                                            kotlin.math.abs(Color.red(expected) - Color.red(observed)),
                                            kotlin.math.abs(Color.green(expected) - Color.green(observed)),
                                            kotlin.math.abs(Color.blue(expected) - Color.blue(observed)),
                                        )
                                    assertTrue(
                                        "Band seam/transform mismatch rotation=$rotation edge=$maxEdge pixel=$x,$y delta=$delta",
                                        delta < 14,
                                    )
                                }
                            }
                        } finally {
                            actual.recycle()
                            reference.recycle()
                            photo.thumbnail?.recycle()
                        }
                    }
                }
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun testJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            listOf(Color.RED, Color.BLUE, Color.YELLOW, Color.WHITE).forEachIndexed { index, color ->
                paint.color = color
                val left = (index % 2) * 400f
                val top = (index / 2) * 300f
                canvas.drawRect(left, top, left + 400, top + 300, paint)
            }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertSingleBottomLeftLogo(image: Bitmap) {
        val logo = PhotoWatermarkGeometry.bounds(image.width, image.height)
        val allowed = Rect(logo.left - 4, logo.top - 4, logo.left + logo.width + 4, logo.top + logo.height + 4)
        var greenPixels = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = image.getPixel(x, y)
                if (Color.green(color) > Color.red(color) + 60 && Color.green(color) > Color.blue(color) + 60) {
                    assertTrue("Logo pixel outside bottom-left bounds: $x,$y", allowed.contains(x, y))
                    greenPixels++
                }
            }
        }
        assertTrue("Logo must be visible in image and thumbnail", greenPixels > 3)
    }

    private fun assertColorNear(
        expected: Int,
        actual: Int,
    ) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 30)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) < 30)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 30)
    }

    private fun assertTrue(condition: Boolean) = check(condition)

    private fun assertTrue(
        message: String,
        condition: Boolean,
    ) = check(condition) { message }

    private fun assertEquals(
        expected: Int,
        actual: Int,
    ) = check(expected == actual) { "Expected $expected, got $actual" }

    private fun assertSame(
        expected: Any,
        actual: Any,
    ) = check(expected === actual)
}
