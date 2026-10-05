package com.scraft.glassreport.feature.readback

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme

private const val PREVIEW_WIDTH_DP = 480
private const val PREVIEW_HEIGHT_DP = 640

@Preview(
    name = "待機中",
    group = "Rokid Readback",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun IdlePreview() {
    ReadbackPreview(ReadbackUiState())
}

@Preview(
    name = "英訳して読み上げ中",
    group = "Rokid Readback",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun SpeakingEnglishPreview() {
    ReadbackPreview(
        ReadbackUiState(
            source = SAMPLE_REPLIES[1],
            translated = "The water leak has been patched. The part arrives tomorrow.",
            phase = ReadbackPhase.SPEAKING,
            status = "読み上げ中",
            detail = "英語",
        ),
        selected = 1,
    )
}

@Preview(
    name = "翻訳が混雑中",
    group = "Rokid Readback",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun RateLimitedPreview() {
    ReadbackPreview(
        ReadbackUiState(
            phase = ReadbackPhase.FAILED,
            status = "英訳失敗",
            detail = "翻訳が混雑中。約27秒後に再試行",
        ),
        selected = 1,
    )
}

@Composable
private fun ReadbackPreview(
    state: ReadbackUiState,
    selected: Int = 0,
) {
    ScraftGlassTheme {
        ReadbackScreenContent(ui = state, selected = selected, onSelect = {})
    }
}
