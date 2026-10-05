package com.scraft.glassreport.feature.capture

import android.Manifest
import android.content.pm.PackageManager
import android.util.Rational
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scraft.glassreport.R
import com.scraft.glassreport.capture.asr.OfflineWavTranscriber
import com.scraft.glassreport.capture.camera.PhotoCapture
import com.scraft.glassreport.capture.camera.PhotoSource
import com.scraft.glassreport.capture.camera.VideoRecording
import com.scraft.glassreport.capture.camera.VideoSource
import com.scraft.glassreport.core.camera.CameraMode
import com.scraft.glassreport.core.camera.CameraPreview
import com.scraft.glassreport.core.input.glassControls
import com.scraft.glassreport.core.ui.GlassVisibleArea
import com.scraft.glassreport.core.ui.glassAction
import com.scraft.glassreport.core.ui.glassActionStyle
import com.scraft.glassreport.di.rememberContainer
import com.scraft.glassreport.domain.model.PHOTO_EDGE_UNLIMITED
import com.scraft.glassreport.net.MIME_MP4

/**
 * カメラ全面表示。操作はタップ／前スワイプ／後スワイプの3つだけで、意味は状態で変わる。
 *
 * - 待機中: スワイプ＝「撮影モード × 送信先」の巡回、タップ＝撮影（録画は2回目のタップで停止）
 * - 確認中: スワイプ＝はい／いいえ、タップ＝決定、BACK＝撮影内容を保持
 * - それ以外の BACK は前の画面へ
 */
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit = {},
    onSettings: () -> Unit = {},
    mode: CaptureMode = CaptureMode.PHOTO,
) {
    val context = LocalContext.current
    val container = rememberContainer()
    val selectedSink = "Slack"
    // Slack実装は写真のみ。動画の送信先を暗黙にモニタへ切り替えない。
    if (mode == CaptureMode.VIDEO && container.sinks.none { it.label == selectedSink && it.accepts(MIME_MP4) }) {
        UnsupportedVideoDestination(destination = selectedSink, onSettings = onSettings, onBack = onBack)
        return
    }
    // 端末登録画面から戻ったときも、撮影済みの写真と同じ向きを保持する。
    var orientation by rememberSaveable { mutableStateOf<Int?>(null) }
    if (orientation == null) {
        OrientationChoice(mode = mode, destination = selectedSink, onSelected = { orientation = it }, onBack = onBack)
        return
    }
    val landscapePhoto = mode == CaptureMode.PHOTO && orientation == Surface.ROTATION_90
    val viewModel: CaptureViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        CaptureViewModel(
                            sinks = container.sinks,
                            speech = container.newSpeechSource(),
                            defaultSink = selectedSink,
                            fixedMode = mode,
                            askCommentAfterCapture = true,
                        )
                    }
                },
        )
    val ui by viewModel.ui.collectAsState()
    val offlineBusy by OfflineWavTranscriber.busy.collectAsState()
    // 設定画面での変更が即座に効く（同じ StateFlow を両画面で見ている）。

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    val callbackExecutor = remember { ContextCompat.getMainExecutor(context) }

    var cameraGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    var audioGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }
    val askPermissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            cameraGranted = result[Manifest.permission.CAMERA] ?: context.hasPermission(Manifest.permission.CAMERA)
            audioGranted = result[Manifest.permission.RECORD_AUDIO] ?: context.hasPermission(Manifest.permission.RECORD_AUDIO)
        }
    LaunchedEffect(Unit) {
        val missing =
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                .filterNot(context::hasPermission)
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }

    LaunchedEffect(ui.mode) {
        imageCapture = null
        videoCapture = null
    }
    // Slackの端末登録が必要なら、撮影内容を残したまま接続画面へ送る。
    LaunchedEffect(ui.signInRequired) {
        if (ui.signInRequired) {
            viewModel.onSignInHandled()
            onSignIn()
        }
    }
    // 撮影後の BACK は ViewModel が扱い、撮影内容を勝手に破棄しない。
    BackHandler { if (!viewModel.onBackPressed()) onBack() }

    // CameraX の use case を「撮る／録る」だけの形に包んで ViewModel へ渡す
    // （ViewModel は CameraX を知らない ＝ 状態遷移を JVM のテストで固められる）。
    val photoSource =
        remember(imageCapture, callbackExecutor, landscapePhoto) {
            imageCapture?.let { capture ->
                PhotoSource {
                    PhotoCapture.capture(
                        capture,
                        callbackExecutor,
                        PHOTO_EDGE_UNLIMITED,
                        landscape = landscapePhoto,
                        resources = context.resources,
                    )
                }
            }
        }
    val videoSource =
        remember(videoCapture, container) {
            videoCapture?.let { capture -> VideoSource { VideoRecording.start(container.appContext, capture, includeAudio = false) } }
        }

    // A delayed single from the recording phase must never operate the next confirmation screen.
    val inputPhase = ui.phase
    val tap = {
        if (viewModel.ui.value.phase == inputPhase) viewModel.onTap(photoSource, videoSource, audioGranted)
    }
    val doubleTap = {
        if (viewModel.ui.value.phase == inputPhase) {
            when (inputPhase) {
                CapturePhase.LISTENING -> viewModel.reviewCommentRecording()
                CapturePhase.COMMENT_REVIEW -> if (!viewModel.onBackPressed()) onBack()
                else -> if (!viewModel.onBackPressed()) onBack()
            }
        }
    }
    val controls =
        glassControls(
            onUp = viewModel::previousTarget,
            onDown = viewModel::nextTarget,
            onSelect = tap,
            onDoubleSelect = doubleTap,
            inputKey = inputPhase,
        )

    CaptureScreenContent(
        ui = ui,
        cameraGranted = cameraGranted,
        audioGranted = audioGranted,
        onTap = tap,
        onDecide = viewModel::decide,
        modifier = controls,
        quality = "安定画質",
        onCommentDecide = viewModel::decideComment,
        onRecordingDoubleTap = doubleTap,
        onCommentReviewDecide = viewModel::decideCommentReview,
        onDraftReviewDecide = viewModel::decideDraftReview,
        onCommentFailureDecide = viewModel::decideCommentFailure,
        cameraBusy = offlineBusy,
    ) {
        val preview: @Composable () -> Unit = {
            DisposableEffect(Unit) {
                onDispose {
                    imageCapture = null
                    videoCapture = null
                }
            }
            CameraPreview(
                mode = if (ui.mode == CaptureMode.PHOTO) CameraMode.PHOTO else CameraMode.VIDEO,
                // Photo orientation selects a crop, not a rotation of the scene.
                captureRotation = if (ui.mode == CaptureMode.PHOTO) Surface.ROTATION_0 else orientation,
                maximumQuality = true,
                photoAspectRatio = if (landscapePhoto) Rational(4, 3) else Rational(3, 4),
                onReady = { imageCapture = it },
                onVideoReady = { videoCapture = it },
                onError = viewModel::onCameraError,
            )
        }
        if (ui.mode == CaptureMode.PHOTO) {
            PhotoPreviewFrame(
                landscape = landscapePhoto,
                showLogo = !ui.confirming && ui.phase != CapturePhase.COMMENT_CHOICE && ui.phase != CapturePhase.COMMENT_REVIEW,
                content = preview,
            )
        } else {
            preview()
        }
    }
}

/** 実機依存を持たない撮影画面の見た目。debug Preview も本番も同じUIを使う。 */
@Composable
internal fun CaptureScreenContent(
    ui: CaptureUiState,
    cameraGranted: Boolean,
    audioGranted: Boolean,
    onTap: () -> Unit,
    onDecide: (ConfirmChoice) -> Unit,
    onCommentDecide: (ConfirmChoice) -> Unit = {},
    onRecordingDoubleTap: () -> Unit = {},
    onCommentReviewDecide: (CommentReviewChoice) -> Unit = {},
    onDraftReviewDecide: (DraftReviewChoice) -> Unit = {},
    onCommentFailureDecide: (CommentFailureChoice) -> Unit = {},
    modifier: Modifier = Modifier,
    /** 今の画質（設定画面の値）。HUD に送信先と並べて出す。 */
    quality: String = "",
    cameraBusy: Boolean = false,
    cameraContent: @Composable () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val draft = ui.draft
    val touchControls =
        if (ui.phase == CapturePhase.LISTENING) {
            Modifier.combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
                onDoubleClick = onRecordingDoubleTap,
            )
        } else {
            Modifier.clickable(interactionSource = interaction, indication = null, onClick = onTap)
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(touchControls),
    ) {
        if (cameraGranted && ui.phase.requiresCamera(cameraBusy)) {
            cameraContent()
        } else if (!cameraGranted) {
            Text(
                text = stringResource(R.string.need_camera),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }

        // 確認中はポップアップだけを出す（480x640 に HUD と重ねると読めなくなるため）。
        if (ui.phase == CapturePhase.COMMENT_REVIEW && draft != null) {
            CommentReviewPanel(choice = ui.commentReviewChoice, onDecide = onCommentReviewDecide)
        } else if (ui.phase == CapturePhase.COMMENT_CHOICE && draft != null) {
            CaptureSummary(
                draft = draft,
                confirm = ui.commentChoice,
                onDecide = onCommentDecide,
                note = ui.detail,
                destination = ui.target.substringAfter("→").trim(),
                question = "コメントを残しますか？",
            )
        } else if (ui.phase == CapturePhase.COMMENT_FAILED && draft != null) {
            CaptureSummary(
                draft = draft,
                confirm = if (ui.commentFailureChoice == CommentFailureChoice.RETRY) ConfirmChoice.YES else ConfirmChoice.NO,
                onDecide = {
                    onCommentFailureDecide(if (it == ConfirmChoice.YES) CommentFailureChoice.RETRY else CommentFailureChoice.CONTINUE)
                },
                note = ui.detail,
                alert = ui.alert,
                question = "音声入力失敗",
                yesLabel = "録音やり直す",
                noLabel = "コメントなしで進む",
                verticalChoices = true,
            )
        } else if (ui.phase == CapturePhase.DRAFT_REVIEW && draft != null) {
            CaptureSummary(
                draft = draft,
                confirm = if (ui.draftReviewChoice == DraftReviewChoice.SEND) ConfirmChoice.YES else ConfirmChoice.NO,
                onDecide = {
                    onDraftReviewDecide(if (it == ConfirmChoice.YES) DraftReviewChoice.SEND else DraftReviewChoice.DISCARD)
                },
                note = ui.detail,
                alert = ui.alert,
                question = "撮影内容を保持しています",
                yesLabel = "送信確認へ",
                noLabel = "破棄する",
            )
        } else if (ui.confirming && draft != null) {
            CaptureSummary(
                draft = draft,
                confirm = ui.confirm,
                onDecide = onDecide,
                note = ui.detail,
                alert = ui.alert,
                destination = ui.target.substringAfter("→").trim(),
                sizeNotice = "画像を長辺1920pxへ縮小して送信します",
            )
        } else {
            GlassVisibleArea(contentAlignment = Alignment.TopCenter) {
                Hud(
                    status = ui.status,
                    detail = ui.detail,
                    audioMissing = false,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }
        GlassVisibleArea {
            Box(Modifier.fillMaxSize()) {
                Text(
                    text = ui.target.substringAfter("→").trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun Hud(
    status: String,
    detail: String,
    audioMissing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
        }
        if (audioMissing && detail != "音声なし") {
            Text(
                text = "音声なし",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

private fun android.content.Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun OrientationChoice(
    mode: CaptureMode,
    destination: String,
    onSelected: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf(0) }
    val rotations = listOf(Surface.ROTATION_0, Surface.ROTATION_90)
    val controls =
        glassControls(
            onUp = { selected = 1 - selected },
            onDown = { selected = 1 - selected },
            onSelect = { onSelected(rotations[selected]) },
            onDoubleSelect = onBack,
        )
    BackHandler(onBack = onBack)
    GlassVisibleArea(modifier = controls.background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            Text(
                text = destination,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${mode.label}の向きを選択", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                listOf("縦", if (mode == CaptureMode.PHOTO) "横 4:3" else "横（端末比率）").forEachIndexed { index, label ->
                    val actionStyle = glassActionStyle(selected == index)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleLarge,
                        color = actionStyle.contentColor,
                        fontWeight = actionStyle.fontWeight,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .glassAction(actionStyle) {
                                    selected = index
                                    onSelected(rotations[index])
                                }.padding(5.dp),
                    )
                }
                if (mode == CaptureMode.VIDEO) {
                    Text("動画の比率は端末の対応解像度に依存します", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun UnsupportedVideoDestination(
    destination: String,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val controls = glassControls(onUp = {}, onDown = {}, onSelect = onSettings, onDoubleSelect = onBack)
    BackHandler(onBack = onBack)
    GlassVisibleArea(modifier = controls.background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            Text(destination, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "動画は${destination}に送信できません",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text("動画送信は現在利用できません", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                val actionStyle = glassActionStyle(selected = true)
                Text(
                    "設定を開く",
                    style = MaterialTheme.typography.bodyLarge,
                    color = actionStyle.contentColor,
                    fontWeight = actionStyle.fontWeight,
                    modifier =
                        Modifier
                            .glassAction(actionStyle, onSettings)
                            .padding(12.dp),
                )
            }
        }
    }
}
