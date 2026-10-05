package com.scraft.glassreport.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme
import com.scraft.glassreport.domain.model.FeedPost

// 実機 480×640px / 240dpi ≒ 320×427dp。
private const val PREVIEW_WIDTH_DP = 320
private const val PREVIEW_HEIGHT_DP = 427

@Preview(
    name = "Teams会話ロビー",
    group = "Rokid Home",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun HomePreview() {
    ScraftGlassTheme {
        HomeChatContent(
            posts =
                listOf(
                    FeedPost("1", "田中", "状況を確認してください", null),
                    FeedPost("2", "山田", "確認しました", null, isOwn = true),
                    FeedPost("3", "田中", "", null, attachmentCount = 1, imageCount = 1),
                ),
            status = "接続中",
            connected = true,
            onPhoto = {},
            onFeed = {},
            onSettings = {},
            onHide = {},
            onExit = {},
            onBack = {},
        )
    }
}

@Preview(
    name = "Teams未接続のロビー",
    group = "Rokid Home",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun DisconnectedPreview() {
    ScraftGlassTheme {
        HomeChatContent(
            posts = emptyList(),
            status = "サインインが必要",
            connected = false,
            onPhoto = {},
            onFeed = {},
            onSettings = {},
            onHide = {},
            onExit = {},
            onBack = {},
            signInRequired = true,
        )
    }
}

@Preview(
    name = "接続エラーと再試行",
    group = "Rokid Home",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun ConnectionErrorPreview() {
    ScraftGlassTheme {
        HomeChatContent(
            posts = emptyList(),
            status = "ネットワークに接続できません",
            connected = false,
            onPhoto = {},
            onFeed = {},
            onSettings = {},
            onHide = {},
            onExit = {},
            onBack = {},
            connectionError = true,
        )
    }
}

@Preview(
    name = "右側の操作パネル",
    group = "Rokid Home",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun ActionPanelPreview() {
    ScraftGlassTheme {
        HomeChatContent(
            posts =
                listOf(
                    FeedPost("1", "田中", "現場を確認しています", null),
                    FeedPost("2", "山田", "こちらからの長い返信です。最後までスクロールできるように、吹き出しの本文を省略せず表示します。", null),
                ),
            status = "接続中",
            connected = true,
            onPhoto = {},
            onFeed = {},
            onSettings = {},
            onHide = {},
            onExit = {},
            onBack = {},
            menuInitiallyOpen = true,
        )
    }
}
