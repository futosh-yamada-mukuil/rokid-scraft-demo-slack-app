package com.scraft.glassreport.feature.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.feature.menu.MenuEntry
import com.scraft.glassreport.feature.menu.MenuScreen

private const val PREVIEW_WIDTH_DP = 480
private const val PREVIEW_HEIGHT_DP = 640

/** 実機の時計に依存しないよう、Preview では時刻を固定する。 */
private const val NOW_MS = 1_700_000_000_000L

private fun post(
    id: String,
    author: String,
    text: String,
    minutesAgo: Long,
    attachments: Int = 0,
) = FeedPost(
    id = id,
    author = author,
    text = text,
    createdAtMs = NOW_MS - minutesAgo * 60_000L,
    attachmentCount = attachments,
)

private val SAMPLE_POSTS =
    listOf(
        post("3", "佐藤 花子", "写真を確認しました。バルブを閉じてから作業してください。", minutesAgo = 3),
        post("2", "田中 一郎", "配管の水漏れは応急処置しました。部品の到着は明日です。", minutesAgo = 42, attachments = 1),
        post("1", "山田 太郎", "了解しました。すぐに向かいます。", minutesAgo = 180),
    )

@Preview(
    name = "投稿を表示",
    group = "Rokid Feed",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun FeedPostPreview() {
    FeedPreview(FeedUiState(posts = SAMPLE_POSTS, index = 1, nowMs = NOW_MS, loaded = true))
}

@Preview(
    name = "新着（強調表示）",
    group = "Rokid Feed",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun FeedNewArrivalPreview() {
    FeedPreview(
        FeedUiState(posts = SAMPLE_POSTS, nowMs = NOW_MS, loaded = true, highlightedId = "3"),
    )
}

@Preview(
    name = "読み上げ中",
    group = "Rokid Feed",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun FeedSpeakingPreview() {
    FeedPreview(
        FeedUiState(
            posts = SAMPLE_POSTS,
            nowMs = NOW_MS,
            loaded = true,
            speech = FeedSpeech.SPEAKING,
            speechMessage = "読み上げ中…",
        ),
    )
}

@Preview(
    name = "通信断（表示は残す）",
    group = "Rokid Feed",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun FeedOfflinePreview() {
    FeedPreview(
        FeedUiState(
            posts = SAMPLE_POSTS,
            nowMs = NOW_MS,
            loaded = true,
            error = "ネットワークに接続できません",
        ),
    )
}

@Preview(
    name = "投稿なし",
    group = "Rokid Feed",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun FeedEmptyPreview() {
    FeedPreview(FeedUiState(nowMs = NOW_MS, loaded = true))
}

@Preview(
    name = "アクションメニュー",
    group = "Rokid Feed",
    widthDp = PREVIEW_WIDTH_DP,
    heightDp = PREVIEW_HEIGHT_DP,
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun FeedMenuPreview() {
    ScraftGlassTheme {
        MenuScreen(
            title = "メニュー",
            entries =
                listOf(
                    MenuEntry(label = "写真を撮る", onSelect = {}),
                    MenuEntry(label = "読み上げ", onSelect = {}),
                    MenuEntry(label = "英語で読み上げ", onSelect = {}),
                    MenuEntry(label = "戻る", onSelect = {}),
                ),
            hint = "スワイプで移動・タップで決定",
            initialSelected = 1,
            onBack = {},
        )
    }
}

@Composable
private fun FeedPreview(state: FeedUiState) {
    ScraftGlassTheme {
        FeedScreenContent(ui = state)
    }
}
