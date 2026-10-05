package com.scraft.glassreport.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.core.ui.GlassScreen
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme
import com.scraft.glassreport.domain.model.CaptureQualities
import com.scraft.glassreport.domain.model.PhotoQuality
import com.scraft.glassreport.domain.model.VideoQuality

private const val PREVIEW_WIDTH_DP = 480
private const val PREVIEW_HEIGHT_DP = 640

@Preview(
    name = "設定・言語選択",
    group = "Rokid Settings",
    widthDp = 320,
    heightDp = 427,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun LanguageSelectionPreview() {
    ScraftGlassTheme {
        GlassScreen(contentAlignment = Alignment.TopCenter) {
            LanguageSelectionPanel(SpeechLanguage.ZH_TW, onConfirm = {}, onCancel = {})
        }
    }
}

@Preview(
    name = "設定・既定",
    group = "Rokid Settings",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun SettingsDefaultPreview() {
    SettingsPreview(CaptureQualities())
}

@Preview(
    name = "設定・Teams / 無加工 / SD",
    group = "Rokid Settings",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun SettingsOriginalPreview() {
    SettingsPreview(
        qualities = CaptureQualities(photo = PhotoQuality.ORIGINAL, video = VideoQuality.SD),
    )
}

@Composable
private fun SettingsPreview(qualities: CaptureQualities = CaptureQualities()) {
    ScraftGlassTheme {
        SettingsScreenContent(
            qualities = qualities,
            onCyclePhoto = {},
            onCycleVideo = {},
            onBack = {},
            onLanguageSelected = {},
        )
    }
}
