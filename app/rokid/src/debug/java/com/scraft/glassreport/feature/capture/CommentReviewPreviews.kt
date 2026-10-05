package com.scraft.glassreport.feature.capture

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme

@Preview(name = "録音メニュー・確定選択", widthDp = 320, heightDp = 427, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CommentReviewConfirmPreview() {
    ScraftGlassTheme { CommentReviewPanel(CommentReviewChoice.CONFIRM) {} }
}

@Preview(name = "録音メニュー・取り直し選択", widthDp = 320, heightDp = 427, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CommentReviewRetakePreview() {
    ScraftGlassTheme { CommentReviewPanel(CommentReviewChoice.RETAKE) {} }
}

@Preview(name = "録音メニュー・キャンセル選択", widthDp = 320, heightDp = 427, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CommentReviewCancelPreview() {
    ScraftGlassTheme { CommentReviewPanel(CommentReviewChoice.CANCEL) {} }
}
