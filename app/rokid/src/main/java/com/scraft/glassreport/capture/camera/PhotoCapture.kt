package com.scraft.glassreport.capture.camera

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private const val TAG = "PhotoCapture"

/**
 * 長辺の既定上限。設定画面（[com.scraft.glassreport.domain.model.PhotoQuality]）で
 * 1280 / 1920 / 無加工 から選べる。1280 では現場の欠陥（傷など）が潰れるため既定は 1920。
 */
const val PHOTO_MAX_EDGE = 1_920

/**
 * Teams へ送るときの長辺上限。
 *
 * `hostedContents` は**メッセージ本文に base64 で埋め込む**方式で、base64 は約1.33倍に膨らむ。
 * 18KB での投稿成功は確認済みだが数MBは未検証のため、**設定が「無加工」でもここまで落とす**。
 * 受信モニタ（`app/api`）へは設定どおりのサイズで送ってよい。
 */
const val PHOTO_TEAMS_MAX_EDGE = 1_920

/** JPEG 品質。1920px なら 80 で 200〜400KB 程度に収まる。 */
const val PHOTO_JPEG_QUALITY = 80

/**
 * 送信確認ポップアップに出すサムネイルの長辺（px）。
 * 表示枠 72dp × 240dpi ≒ 173px なので、少し上を確保しておく。
 */
const val THUMBNAIL_MAX_EDGE = 192

/**
 * 撮影できた1枚。送信するバイト列と、確認ポップアップ用の見た目・実測値をまとめて返す。
 * [thumbnail] は端末側の都合で作れなければ null（呼び出し側は無くても動くこと）。
 */
data class CapturedPhoto(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val thumbnail: Bitmap?,
)

/**
 * CameraX の [ImageCapture] を使った静止画撮影。
 *
 * **向きについて（Rokid Glasses 固有）**
 * この端末は背面カメラのセンサーが 270° 回転して載っている（`android.sensor.orientation = 270`）。
 * `OnImageCapturedCallback` が返すバッファは回転されておらず、必要な回転量は
 * [ImageProxy.getImageInfo] の `rotationDegrees` にだけ入っている。
 * EXIF の Orientation タグに頼ると、それを尊重しない表示側（受信モニタの `<img>` など）で
 * 横倒しに見えるため、**ここでピクセルを実際に回転させ、EXIF を持たない正立 JPEG を作る**。
 */
object PhotoCapture {
    /**
     * 1枚撮影し、正立・長辺 [maxEdge]px・品質 [PHOTO_JPEG_QUALITY] の JPEG と
     * 確認ポップアップ用のサムネイルを返す。失敗時は [ImageCaptureException] などをそのまま投げる。
     * [landscape] は正立画像の中央を横長4:3に切り抜く。被写体の向きは変えない。
     *
     * JPEGをコピーしたらCameraXの画像を先に返却し、帯状デコードで大きなビットマップの同時保持を避ける。
     * Javaヒープの割り当て失敗時は既存の [PHOTO_MAX_EDGE] フォールバックを使う。
     */
    suspend fun capture(
        imageCapture: ImageCapture,
        executor: Executor,
        maxEdge: Int = PHOTO_MAX_EDGE,
        landscape: Boolean = false,
        resources: Resources,
    ): CapturedPhoto {
        val captureStarted = SystemClock.elapsedRealtime()
        Log.i(TAG, "撮影要求")
        val proxy = takePicture(imageCapture, executor)
        val rotation: Int
        val crop: Rect
        val jpeg =
            try {
                rotation = proxy.imageInfo.rotationDegrees
                crop = Rect(proxy.cropRect)
                withContext(Dispatchers.Default) { proxy.toJpegBytes() }
            } finally {
                proxy.close()
                Log.i(TAG, "CameraX画像バッファ返却 ${SystemClock.elapsedRealtime() - captureStarted}ms")
            }
        return withContext(Dispatchers.Default) {
            try {
                toUprightPhoto(jpeg, rotation, maxEdge, landscape = landscape, sourceCrop = crop, watermarkResources = resources)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "長辺${maxEdge}px を確保できないので ${PHOTO_MAX_EDGE}px に落とします", e)
                toUprightPhoto(jpeg, rotation, PHOTO_MAX_EDGE, landscape = landscape, sourceCrop = crop, watermarkResources = resources)
            }
        }
    }

    /**
     * すでに正立している JPEG を、長辺 [maxEdge]px 以下に落とし直す（Teams 送信用）。
     * 収まっていれば**そのまま返す**。
     *
     * 呼ぶのは宛先を組み立てる [com.scraft.glassreport.di.mediaSinks]
     * （Teams を [com.scraft.glassreport.net.DownscalingSink] で包むときの縮小処理として渡す）。
     * 撮影画面や `MediaSink` の実装からは直接呼ばない。
     */
    fun downscale(
        jpeg: ByteArray,
        maxEdge: Int = PHOTO_TEAMS_MAX_EDGE,
        quality: Int = PHOTO_JPEG_QUALITY,
    ): ByteArray {
        val bounds =
            BitmapFactory
                .Options()
                .apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, it) }
        if (maxOf(bounds.outWidth, bounds.outHeight) <= maxEdge) return jpeg
        return runCatching {
            toUprightPhoto(jpeg, rotationDegrees = 0, maxEdge = maxEdge, quality = quality).let {
                it.thumbnail?.recycle()
                it.jpeg
            }
        }.onFailure { Log.w(TAG, "縮小できないので元のまま送ります", it) }
            .getOrDefault(jpeg)
    }

    /** takePicture のコールバックを suspend 関数に畳む。 */
    private suspend fun takePicture(
        imageCapture: ImageCapture,
        executor: Executor,
    ): ImageProxy =
        suspendCancellableCoroutine { cont ->
            val requestedAt = SystemClock.elapsedRealtime()
            imageCapture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        Log.i(TAG, "JPEG受領 ${image.width}x${image.height} ${SystemClock.elapsedRealtime() - requestedAt}ms")
                        cont.resume(image) { _, resource, _ -> resource.close() }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                },
            )
        }

    /** ImageProxy（JPEG 形式）から生バイトを取り出す。 */
    private fun ImageProxy.toJpegBytes(): ByteArray {
        val buffer = planes[0].buffer
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    /**
     * 撮って出しの JPEG を、[rotationDegrees] だけ回転させ長辺 [maxEdge]px 以下に縮小して再エンコードする。
     * [landscape] のときは正立後に中央4:3となる範囲を使い、切り抜き後の長辺に上限を適用する。
     * [sourceCrop] があれば CameraX ViewPort のセンサー座標範囲を優先し、二重クロップしない。
     * [watermarkResources] は撮影時だけ指定する。再縮小時は既存ロゴも一緒に縮小し、重ね描きしない。
     * ついでに（デコード済みビットマップがある間に）サムネイルと寸法も作る。
     *
     * 元画像は数千 px あり、そのままデコードすると数十 MB になるので
     * `inSampleSize` で粗く間引いてから Matrix で正確な倍率に合わせる。
     */
    fun toUprightPhoto(
        jpeg: ByteArray,
        rotationDegrees: Int,
        maxEdge: Int = PHOTO_MAX_EDGE,
        quality: Int = PHOTO_JPEG_QUALITY,
        landscape: Boolean = false,
        sourceCrop: Rect? = null,
        watermarkResources: Resources? = null,
    ): CapturedPhoto {
        val processingStarted = SystemClock.elapsedRealtime()
        require(maxEdge > 0)
        val bounds =
            BitmapFactory
                .Options()
                .apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, it) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "JPEG のデコードに失敗しました" }
        // CameraX ViewPort owns the crop when supplied, including portrait. Never crop it a second time.
        val originalCrop =
            capturePhotoCrop(
                bounds.outWidth,
                bounds.outHeight,
                rotationDegrees,
                landscape,
                sourceCrop?.let { PhotoCrop(it.left, it.top, it.width(), it.height()) },
            )
        val longEdge = maxOf(originalCrop.width, originalCrop.height)

        // 長辺が maxEdge を下回らない範囲で 1/2 ずつ間引く。
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2

        val crop =
            RectF(
                originalCrop.left.toFloat(),
                originalCrop.top.toFloat(),
                (originalCrop.left + originalCrop.width).toFloat(),
                (originalCrop.top + originalCrop.height).toFloat(),
            )
        val scale = (maxEdge.toFloat() / maxOf(crop.width(), crop.height())).coerceAtMost(1f)
        var output: Bitmap? = null
        val decoder =
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
                ?: throw IllegalStateException("JPEG のデコードに失敗しました")
        try {
            val matrix = Matrix().apply { setRotate(rotationDegrees.toFloat()) }
            val rotatedBounds = RectF().also { matrix.mapRect(it, crop) }
            matrix.postTranslate(-rotatedBounds.left, -rotatedBounds.top)
            matrix.postScale(scale, scale)
            val upright =
                Bitmap
                    .createBitmap(
                        (rotatedBounds.width() * scale).roundToInt().coerceAtLeast(1),
                        (rotatedBounds.height() * scale).roundToInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    ).also { output = it }
            Log.i(TAG, "帯状画像処理開始 ${upright.width}x${upright.height}")
            val canvas = Canvas(upright)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            canvas.save()
            canvas.concat(matrix)
            // Only one small decoded band coexists with the final bitmap. One sampled row of overlap
            // provides filtering neighbours; clip to the band's exact boundary to avoid seams.
            for (band in photoDecodeBands(originalCrop, sample)) {
                val region =
                    Rect(
                        band.decode.left,
                        band.decode.top,
                        band.decode.left + band.decode.width,
                        band.decode.top + band.decode.height,
                    )
                val tile = decoder.decodeRegion(region, options) ?: throw IllegalStateException("JPEG の部分デコードに失敗しました")
                try {
                    canvas.save()
                    canvas.clipRect(crop.left, band.top.toFloat(), crop.right, band.bottom.toFloat())
                    canvas.drawBitmap(tile, null, RectF(region), paint)
                    canvas.restore()
                } finally {
                    tile.recycle()
                }
            }
            canvas.restore()
            decoder.recycle()
            // Only capture supplies resources; downscale operates on the stamped JPEG without stamping again.
            watermarkResources?.let { stampPhotoWatermark(upright, it) }
            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val thumbnail = thumbnailOf(upright)
            val result =
                CapturedPhoto(
                    jpeg = out.toByteArray(),
                    width = upright.width,
                    height = upright.height,
                    thumbnail = thumbnail,
                )
            Log.i(TAG, "画像処理完了 ${upright.width}x${upright.height} ${SystemClock.elapsedRealtime() - processingStarted}ms")
            return result
        } finally {
            output?.recycle()
            if (!decoder.isRecycled) decoder.recycle()
        }
    }

    /**
     * 確認ポップアップ用の小さなコピー。**必ず元とは別のビットマップを返す**
     * （呼び出し元が元を `recycle()` するため。`createScaledBitmap` は等倍だと元をそのまま返す）。
     */
    private fun thumbnailOf(source: Bitmap): Bitmap? {
        val scale = (THUMBNAIL_MAX_EDGE.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            if (scaled === source) source.copy(Bitmap.Config.ARGB_8888, false) else scaled
        }.getOrNull()
    }
}
