package com.scraft.glassreport.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MIME_MP4

private const val PREVIEW_WIDTH_DP = 480
private const val PREVIEW_HEIGHT_DP = 640

@Preview(
    name = "写真→モニタ・待機中",
    group = "Rokid Capture",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun PhotoIdlePreview() {
    CapturePreview(
        state =
            CaptureUiState(
                mode = CaptureMode.PHOTO,
                target = "写真 → モニタ",
                phase = CapturePhase.IDLE,
                status = "待機中",
                host = "192.168.1.20:8787",
            ),
    )
}

@Preview(
    name = "音声コメント・録音中",
    group = "Rokid Capture",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun ListeningPreview() {
    CapturePreview(
        state =
            CaptureUiState(
                mode = CaptureMode.PHOTO,
                target = "写真 → Teams",
                phase = CapturePhase.LISTENING,
                status = "録音中 3秒…",
                detail = "コメントをどうぞ",
                host = "Teams チャネル",
            ),
    )
}

@Preview(
    name = "録画中（手動停止）",
    group = "Rokid Capture",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun VideoRecordingPreview() {
    CapturePreview(
        state =
            CaptureUiState(
                mode = CaptureMode.VIDEO,
                target = "動画 → モニタ",
                phase = CapturePhase.RECORDING,
                status = "録画中 12秒",
                detail = "タップで停止（上限60秒）",
                host = "192.168.1.20:8787",
            ),
        audioGranted = false,
        quality = "FHD",
    )
}

@Preview(
    name = "送信確認・写真＋文字起こし",
    group = "Rokid Capture",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun ConfirmPhotoPreview() {
    CapturePreview(
        state =
            CaptureUiState(
                mode = CaptureMode.PHOTO,
                target = "写真 → Teams",
                phase = CapturePhase.CONFIRM,
                status = "送信の確認",
                detail = "認識 0.8秒",
                host = "Teams チャネル",
                draft =
                    CaptureDraft(
                        mode = CaptureMode.PHOTO,
                        bytes = ByteArray(0),
                        mime = MIME_JPEG,
                        measure = "960x1280 / 103KB",
                        comment = "配管の継ぎ手から水漏れがあります",
                    ),
            ),
    )
}

@Preview(
    name = "送信確認・動画（コメントなし）",
    group = "Rokid Capture",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun ConfirmVideoPreview() {
    CapturePreview(
        state =
            CaptureUiState(
                mode = CaptureMode.VIDEO,
                target = "動画 → モニタ",
                phase = CapturePhase.CONFIRM,
                status = "送信の確認",
                detail = "音声あり",
                confirm = ConfirmChoice.YES,
                host = "192.168.1.20:8787",
                draft =
                    CaptureDraft(
                        mode = CaptureMode.VIDEO,
                        bytes = ByteArray(0),
                        mime = MIME_MP4,
                        measure = "1分5秒 / 1920x1080 / 12.4MB",
                    ),
            ),
        quality = "FHD",
    )
}

@Preview(
    name = "送信失敗・再送の確認",
    group = "Rokid Capture",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun ConfirmRetryPreview() {
    CapturePreview(
        state =
            CaptureUiState(
                mode = CaptureMode.PHOTO,
                target = "写真 → Teams",
                phase = CapturePhase.CONFIRM,
                status = "送信に失敗しました",
                detail = "960x1280 / 103KB",
                alert = "送信に失敗しました: タイムアウト",
                host = "Teams チャネル",
                draft =
                    CaptureDraft(
                        mode = CaptureMode.PHOTO,
                        bytes = ByteArray(0),
                        mime = MIME_JPEG,
                        measure = "960x1280 / 103KB",
                        comment = "配管の継ぎ手から水漏れがあります",
                    ),
            ),
    )
}

@Preview(
    name = "カメラ権限なし・撮影失敗",
    group = "Rokid Capture",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun CameraUnavailablePreview() {
    CapturePreview(
        state =
            CaptureUiState(
                mode = CaptureMode.PHOTO,
                target = "写真 → 未設定",
                phase = CapturePhase.FAILED,
                status = "撮影失敗: カメラ未準備",
                host = "送信先が未設定",
            ),
        cameraGranted = false,
    )
}

@Composable
private fun CapturePreview(
    state: CaptureUiState,
    cameraGranted: Boolean = true,
    audioGranted: Boolean = true,
    quality: String = "1920",
) {
    ScraftGlassTheme {
        CaptureScreenContent(
            ui = state,
            cameraGranted = cameraGranted,
            audioGranted = audioGranted,
            onTap = {},
            onDecide = {},
            quality = quality,
        ) {
            PreviewCameraBackdrop()
        }
    }
}

/** PreviewではCameraXを起動できないため、薄いダミー映像を背面に置く。 */
@Composable
private fun PreviewCameraBackdrop() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xFF071407)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "CAMERA PREVIEW",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
        )
    }
}
