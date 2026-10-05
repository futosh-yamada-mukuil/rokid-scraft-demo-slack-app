package com.scraft.glassreport.feature.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme

private const val PREVIEW_WIDTH_DP = 480
private const val PREVIEW_HEIGHT_DP = 640

@Preview(
    name = "コード表示（待機中）",
    group = "Rokid Auth",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun WaitingPreview() {
    // 実際の user_code は 9 文字。単眼ディスプレイで読み違えないか、この幅で確認する。
    AuthPreview(
        AuthUiState(
            phase = AuthPhase.WAITING,
            userCode = "F7KQ2M9XZ",
            verificationUri = "https://microsoft.com/devicelogin",
            message = "PC・スマホで入力してください",
        ),
    )
}

@Preview(
    name = "取得失敗",
    group = "Rokid Auth",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun FailedPreview() {
    AuthPreview(
        AuthUiState(
            phase = AuthPhase.FAILED,
            message = "AADSTS7000218: 設定が不正（戻って入り直してください）",
        ),
    )
}

@Preview(
    name = "Teams 未設定",
    group = "Rokid Auth",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun NotConfiguredPreview() {
    AuthPreview(
        AuthUiState(
            phase = AuthPhase.NOT_CONFIGURED,
            message = "Teams の設定がありません。ダブルタップで戻る",
        ),
    )
}

@Composable
private fun AuthPreview(state: AuthUiState) {
    ScraftGlassTheme {
        AuthScreenContent(ui = state)
    }
}
