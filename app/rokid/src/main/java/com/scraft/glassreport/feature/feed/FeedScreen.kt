package com.scraft.glassreport.feature.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scraft.glassreport.core.input.glassControls
import com.scraft.glassreport.core.ui.GlassScreen
import com.scraft.glassreport.di.rememberContainer
import com.scraft.glassreport.feature.menu.MenuEntry
import com.scraft.glassreport.feature.menu.MenuScreen
import kotlinx.coroutines.launch

/** 投稿本文はカード内をスクロールして読む。 */
private const val BODY_MAX_LINES = 30

/** 画面下に常時出す操作ヒント（外部設計書 4 章）。 */
private const val FEED_HINT = "スワイプ＝本文/投稿 ・ タップ＝メニュー"

/**
 * チャネルの最新投稿をグラスに出す画面（PDF 8 ステップの「06 返信通知」）。
 *
 * - 前後スワイプ … 投稿を 1 件ずつ送る
 * - タップ … アクションメニュー（読み上げ中なら停止）
 * - 自動更新 … **画面が見えている間だけ** 30 秒ごと。
 *   [Lifecycle.State.STARTED] の中で回すので、近接センサーで画面が消えれば止まる。
 *
 * @param onCapture メニューの「写真を撮る」。渡さなければ項目自体を出さない。
 * @param onSignIn サインインが切れていたときの行き先（認証画面）。
 */
@Composable
fun FeedScreen(
    onBack: () -> Unit,
    onCapture: (() -> Unit)? = null,
    onSignIn: () -> Unit = {},
) {
    val container = rememberContainer()
    val viewModel: FeedViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer {
                        FeedViewModel(
                            repository = container.feedRepository,
                            speaker = container.newSpeaker(),
                            translator = container.translator,
                            chime = container.newChime(),
                        )
                    }
                },
        )
    val ui by viewModel.ui.collectAsState()
    val replyFontSize by container.captureSettings.replyFontSize.collectAsState()
    val bodyScroll = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    LaunchedEffect(ui.current?.id) { bodyScroll.scrollTo(0) }

    // 画面が見えている間だけポーリングする。装着していない（画面 OFF）間は止まる。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.poll() }
    }
    LaunchedEffect(ui.signInRequired) {
        if (ui.signInRequired) {
            viewModel.onSignInHandled()
            onSignIn()
        }
    }

    if (ui.menuOpen) {
        MenuScreen(
            title = "メニュー",
            entries = menuEntries(viewModel, onCapture),
            hint = "スワイプで移動・タップで決定",
            onBack = viewModel::closeMenu,
        )
    } else {
        BackHandler { onBack() }
        FeedScreenContent(
            ui = ui,
            replyFontSize = replyFontSize,
            bodyScroll = bodyScroll,
            onTap = viewModel::onTap,
            modifier =
                glassControls(
                    onUp = {
                        if (bodyScroll.value >
                            0
                        ) {
                            scrollScope.launch { bodyScroll.animateScrollTo((bodyScroll.value - 100).coerceAtLeast(0)) }
                        } else {
                            viewModel.previousPost()
                        }
                    },
                    onDown = {
                        if (bodyScroll.value <
                            bodyScroll.maxValue
                        ) {
                            scrollScope.launch {
                                bodyScroll.animateScrollTo(
                                    (bodyScroll.value + 100).coerceAtMost(bodyScroll.maxValue),
                                )
                            }
                        } else {
                            viewModel.nextPost()
                        }
                    },
                    onSelect = viewModel::onTap,
                    onDoubleSelect = onBack,
                ),
        )
    }
}

/** アクションメニューの並び（外部設計書 4 章）。項目は 4 つまでに抑える。 */
private fun menuEntries(
    viewModel: FeedViewModel,
    onCapture: (() -> Unit)?,
): List<MenuEntry> =
    listOfNotNull(
        onCapture?.let { capture ->
            MenuEntry(label = "写真を撮る", onSelect = {
                viewModel.closeMenu()
                capture()
            })
        },
        MenuEntry(label = "読み上げ", onSelect = viewModel::speakJapanese),
        MenuEntry(label = "戻る", onSelect = viewModel::closeMenu),
    )

/** 実機依存を持たない見た目。debug の Preview と本番で同じものを使う。 */
@Composable
internal fun FeedScreenContent(
    ui: FeedUiState,
    replyFontSize: Int = com.scraft.glassreport.data.prefs.DEFAULT_REPLY_FONT_SIZE,
    onTap: () -> Unit = {},
    bodyScroll: ScrollState? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val visibleScroll = bodyScroll ?: rememberScrollState()

    GlassScreen(modifier = modifier, contentAlignment = Alignment.TopStart) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = interaction, indication = null, onClick = onTap),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PostCard(ui = ui, replyFontSize = replyFontSize, bodyScroll = visibleScroll, modifier = Modifier.weight(1f))

            // 読み上げ・翻訳の状況（無いときは行ごと出さない）。
            if (ui.speechMessage.isNotBlank()) {
                Text(
                    text = ui.speechMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 取得の失敗理由。表示中の投稿は消さずに理由だけ添える。
            if (ui.error.isNotBlank()) {
                Text(
                    text = ui.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = FEED_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 投稿 1 件。新着のときだけ枠と淡い塗りで強調する。 */
@Composable
private fun PostCard(
    ui: FeedUiState,
    replyFontSize: Int,
    bodyScroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val post = ui.current

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (ui.highlighted) scheme.primary.copy(alpha = 0.18f) else scheme.background)
                .border(
                    width = 2.dp,
                    color = if (ui.highlighted) scheme.primary else scheme.secondary,
                    shape = RoundedCornerShape(10.dp),
                ).verticalScroll(bodyScroll)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (post == null) {
            Text(
                // 取得できていない理由は下の行に出るので、ここは状態だけを短く出す。
                text =
                    when {
                        ui.loaded -> "投稿はありません"
                        ui.error.isNotBlank() -> "取得できません"
                        else -> "読み込み中…"
                    },
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.primary,
            )
            return@Column
        }

        // 1 行目: 投稿者と経過時間（お客様仕様の表示項目）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = post.author,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = if (ui.highlighted) "新着 ・ ${ui.elapsed}" else ui.elapsed,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.secondary,
                maxLines = 1,
            )
        }

        // 2 行目以降: 本文。画像は出さず件数だけ添える。
        Text(
            text = post.displayText.ifBlank { "(本文なし)" },
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = replyFontSize.sp),
            color = scheme.primary,
            maxLines = BODY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = ui.attachments,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.secondary,
                maxLines = 1,
            )
            Text(
                text = ui.position,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.secondary,
                maxLines = 1,
            )
        }
    }
}
