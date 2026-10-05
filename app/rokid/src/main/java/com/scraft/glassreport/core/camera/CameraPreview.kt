package com.scraft.glassreport.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Rational
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scraft.glassreport.domain.model.VideoQuality
import java.util.concurrent.Executors

/** CameraPreview が現在バインドする撮影 use case。端末負荷を抑えるため同時バインドはしない。 */
enum class CameraMode { PHOTO, VIDEO }

/**
 * グラス共通の CameraX プレビュー。旧 RecordScreen の実装を救い出したもの
 * （実機で動作確認済みの配線。カメラ制御はこのリポジトリの実装が正）。
 *
 * - [zoomRatio] は広角カメラ対策のデジタルズーム。前後スワイプで増減させる想定。
 *   バインド可能な最大倍率は [onMaxZoom] で返す。
 * - [onFrame] を渡したときだけ ImageAnalysis をバインドする（不要なら CPU を使わない）。
 * - [onReady] で静止画撮影用の [ImageCapture] を呼び出し側へ渡す（シャッター実装は各画面）。
 * - [photoAspectRatio] は正立画像の比率。Preview/JPEG を同じ ViewPort に束ね、解像度が違っても画角を揃える。
 * - [mode] が [CameraMode.VIDEO] のときは [videoQuality] 優先の [VideoCapture] をバインドし、
 *   [onVideoReady] で返す（画質は設定画面で変えられる。実機は 4K まで出せるが選択肢に入れない）。
 *
 * 呼び出し側は CAMERA 権限が付与済みであることを保証すること。
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    zoomRatio: Float = 1f,
    mode: CameraMode = CameraMode.PHOTO,
    videoQuality: VideoQuality = VideoQuality.DEFAULT,
    captureRotation: Int? = null,
    maximumQuality: Boolean = false,
    photoAspectRatio: Rational? = null,
    onMaxZoom: (Float) -> Unit = {},
    onFrame: ((ImageProxy) -> Unit)? = null,
    onReady: (ImageCapture) -> Unit = {},
    onVideoReady: (VideoCapture<Recorder>) -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    val ctx = LocalContext.current
    // Compose 1.12 で LocalLifecycleOwner は compose.ui.platform から
    // androidx.lifecycle.compose（lifecycle-runtime-compose）へ移った。
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember(photoAspectRatio) {
            PreviewView(ctx).apply {
                scaleType = if (photoAspectRatio != null) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
            }
        }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val cameraControl = remember { mutableStateOf<CameraControl?>(null) }

    // 画面は portrait 固定（ROTATION_0 が正位置）。この端末はセンサーが 270° 回って載っているので、
    // targetRotation を画面の向きに合わせておくと ImageProxy.imageInfo.rotationDegrees に
    // 「正立させるのに必要な回転量」が入る。実際のピクセル回転は PhotoCapture 側で行う。
    val displayRotation = captureRotation ?: remember { ContextCompat.getDisplayOrDefault(ctx).rotation }
    // 画質を変えたら Recorder ごと作り直す（QualitySelector は生成時にしか渡せない）。
    val videoCapture =
        remember(mode, videoQuality, displayRotation, maximumQuality) {
            if (mode != CameraMode.VIDEO) return@remember null
            val target = videoQuality.toCameraX()
            val qualitySelector =
                if (maximumQuality) {
                    QualitySelector.from(Quality.HIGHEST)
                } else {
                    QualitySelector.from(target, FallbackStrategy.lowerQualityOrHigherThan(target))
                }
            val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
            VideoCapture
                .Builder(recorder)
                .setTargetRotation(displayRotation)
                .build()
        }

    // FHD 録画ではエンコーダが最大のメモリ利用者になる。Rokid は RAM が少なく、
    // SurfaceView のプレビュー用バッファまで同時に確保すると LMK が録画アプリを落とした。
    // 動画モードではライブプレビューを出さず VideoCapture だけを bind して、その分を解放する。
    // （静止画モードは構図確認が重要なので従来どおりプレビューを使う。）
    if (mode == CameraMode.PHOTO) {
        AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
    }

    DisposableEffect(previewView, mode, videoCapture, displayRotation, maximumQuality, photoAspectRatio) {
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        var ownedUseCases = emptyList<UseCase>()
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (disposed) return@addListener
            val provider = future.get()
            val imageCapture =
                ImageCapture
                    .Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(if (maximumQuality) 100 else 95)
                    .setResolutionSelector(
                        ResolutionSelector
                            .Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            // A post-capture resize cannot prevent HAL low-memory kills. Never fall
                            // back upward: an unsupported safe stream must fail binding instead.
                            .setResolutionFilter { sizes, _ -> sizes.filter { isSafePhotoResolution(it.width, it.height) } }
                            .build(),
                    ).setTargetRotation(displayRotation)
                    .apply {
                        // Query the exact camera selected by CameraX, not the first CameraManager id.
                        runCatching {
                            val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).first()
                            val manager = ctx.getSystemService(CameraManager::class.java)
                            val characteristics = manager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId)
                            reduceStillCaptureProcessing(characteristics)
                        }.onFailure { Log.w("CameraPreview", "撮影の軽量設定を適用できません", it) }
                    }.build()
            val preview =
                if (mode == CameraMode.PHOTO) {
                    Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(displayRotation).build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                } else {
                    null
                }
            val analysis =
                onFrame?.let { callback ->
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor) { proxy -> callback(proxy) } }
                }
            val captureUseCase: UseCase = if (mode == CameraMode.PHOTO) imageCapture else requireNotNull(videoCapture)
            val useCases: List<UseCase> = listOfNotNull(preview, captureUseCase, analysis)
            runCatching {
                boundProvider = provider
                ownedUseCases = useCases
                val camera =
                    if (mode == CameraMode.PHOTO && photoAspectRatio != null) {
                        // Preview and JPEG can have different resolutions; share the same sensor crop.
                        // PhotoCapture must honor ImageProxy.cropRect for this viewport.
                        val group =
                            UseCaseGroup
                                .Builder()
                                .setViewPort(
                                    ViewPort
                                        .Builder(photoAspectRatio, displayRotation)
                                        .setScaleType(ViewPort.FILL_CENTER)
                                        .build(),
                                ).apply { useCases.forEach { addUseCase(it) } }
                                .build()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                    } else {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            *useCases.toTypedArray(),
                        )
                    }
                cameraControl.value = camera.cameraControl
                onMaxZoom(
                    camera.cameraInfo.zoomState.value
                        ?.maxZoomRatio ?: 1f,
                )
                camera.cameraControl.setZoomRatio(zoomRatio)
                if (mode == CameraMode.PHOTO) onReady(imageCapture) else onVideoReady(requireNotNull(videoCapture))
            }.onFailure(onError)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            disposed = true
            boundProvider?.unbind(*ownedUseCases.toTypedArray())
            cameraControl.value = null
        }
    }

    // ズーム倍率の変更を反映。
    LaunchedEffect(zoomRatio) {
        cameraControl.value?.setZoomRatio(zoomRatio)
    }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
}

/** Preserve resolution while avoiding optional HAL ZSL/high-quality processing buffers. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun ImageCapture.Builder.reduceStillCaptureProcessing(characteristics: CameraCharacteristics) {
    val keys = characteristics.availableCaptureRequestKeys.orEmpty()
    val interop = Camera2Interop.Extender(this)
    val zsl = CaptureRequest.CONTROL_ENABLE_ZSL in keys
    val noise =
        CaptureRequest.NOISE_REDUCTION_MODE in keys &&
            characteristics
                .get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) == true
    val edge =
        CaptureRequest.EDGE_MODE in keys &&
            characteristics
                .get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?.contains(CaptureRequest.EDGE_MODE_FAST) == true
    if (zsl) interop.setCaptureRequestOption(CaptureRequest.CONTROL_ENABLE_ZSL, false)
    if (noise) interop.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
    if (edge) interop.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
    Log.i("CameraPreview", "撮影HAL軽量設定 ZSL無効=$zsl NR_FAST=$noise EDGE_FAST=$edge")
}

/** 設定値（ドメイン）から CameraX の [Quality] へ。CameraX 型はこのファイルの外へ出さない。 */
private fun VideoQuality.toCameraX(): Quality =
    when (this) {
        VideoQuality.SD -> Quality.SD
        VideoQuality.HD -> Quality.HD
        VideoQuality.FHD -> Quality.FHD
    }
