package com.scraft.glassreport.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.scraft.glassreport.core.input.glassControls
import com.scraft.glassreport.core.ui.GlassScreen
import com.scraft.glassreport.core.ui.glassAction
import com.scraft.glassreport.core.ui.glassActionStyle
import com.scraft.glassreport.data.repo.refreshes
import com.scraft.glassreport.di.rememberContainer
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.text.RelativeTime
import com.scraft.glassreport.feature.menu.MenuEntry
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.launch

private const val LOBBY_POLL_MS = 10_000L

/** Teams の開発用チャネルを眺めながら、右側の操作パネルを開けるロビー。 */
@Composable
fun HomeScreen(
    onPhoto: () -> Unit,
    onFeed: () -> Unit,
    onSettings: () -> Unit,
    onHide: () -> Unit,
    onExit: () -> Unit,
    onBack: () -> Unit = onExit,
    onSignIn: () -> Unit = {},
) {
    val container = rememberContainer()
    val repository = container.lobbyRepository
    val lifecycleOwner = LocalLifecycleOwner.current
    var posts by remember { mutableStateOf<List<FeedPost>>(emptyList()) }
    var status by remember { mutableStateOf("接続中…") }
    var connected by remember { mutableStateOf(false) }
    var signInRequired by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf(false) }
    var signInPrompted by rememberSaveable { mutableStateOf(false) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    // 経過時間（「3分前」）の基準。投稿が増えなくても時間は進むので、更新のたびに進めて表示を追従させる。
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val retryNow = {
        status = "接続中…"
        connectionError = false
        retryGeneration++
        Unit
    }

    // 送信先の設定とは独立して Teams チャネルを読む。画面が前面にある間だけ更新する。
    LaunchedEffect(lifecycleOwner, repository, retryGeneration) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            repository.refreshes(LOBBY_POLL_MS).collect {
                nowMs = System.currentTimeMillis()
                if (!container.channelConfigured) {
                    connected = false
                    signInRequired = false
                    connectionError = false
                    status = "Slack未設定"
                } else {
                    repository.latest().fold(
                        onSuccess = { latest ->
                            posts = latest.asReversed()
                            connected = true
                            signInRequired = false
                            connectionError = false
                            signInPrompted = false
                            status = "接続中"
                        },
                        onFailure = { error ->
                            connected = false
                            signInRequired = error is SignInRequiredException
                            connectionError = !signInRequired
                            status =
                                if (signInRequired) {
                                    "サインインが必要"
                                } else {
                                    error.message
                                        ?.take(24)
                                        .orEmpty()
                                        .ifBlank { "接続できません" }
                                }
                            if (signInRequired && !signInPrompted) {
                                signInPrompted = true
                                onSignIn()
                            }
                        },
                    )
                }
            }
        }
    }

    HomeChatContent(
        posts = posts,
        status = status,
        connected = connected,
        signInRequired = signInRequired,
        connectionError = connectionError,
        onSignIn = onSignIn,
        onRetry = retryNow,
        onPhoto = onPhoto,
        onFeed = onFeed,
        onSettings = onSettings,
        onHide = onHide,
        onExit = onExit,
        onBack = onBack,
        nowMs = nowMs,
    )
}

/** 通信に依存しない表示部。グラスの前後スワイプで過去の会話もたどれる。 */
@Composable
internal fun HomeChatContent(
    posts: List<FeedPost>,
    status: String,
    connected: Boolean,
    onPhoto: () -> Unit,
    onFeed: () -> Unit,
    onSettings: () -> Unit,
    onHide: () -> Unit,
    onExit: () -> Unit,
    onBack: () -> Unit,
    signInRequired: Boolean = false,
    connectionError: Boolean = false,
    onSignIn: () -> Unit = {},
    onRetry: () -> Unit = {},
    menuInitiallyOpen: Boolean = false,
    /** 経過時間の基準時刻。呼び出し側が進めないと、吹き出しは再描画されず「たった今」のまま残る。 */
    nowMs: Long = System.currentTimeMillis(),
) {
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val hasRecovery = signInRequired || connectionError
    var focus by remember { mutableStateOf(LobbyFocus.MENU) }
    var menuOpen by remember { mutableStateOf(menuInitiallyOpen) }
    val menuVisibility = remember { MutableTransitionState(menuInitiallyOpen) }
    menuVisibility.targetState = menuOpen
    val menuActive = menuOpen || menuVisibility.currentState || !menuVisibility.isIdle
    val menuSlide = tween<IntOffset>(280, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))
    var selectedMenuEntry by remember { mutableIntStateOf(0) }
    val menuEntries =
        listOf(
            MenuEntry("写真送信", onSelect = onPhoto),
            MenuEntry("動画送信", note = "準備中", enabled = false, onSelect = {}),
            MenuEntry("記録を見る", onSelect = onFeed),
            MenuEntry("設定", onSelect = onSettings),
            MenuEntry("画面非表示", onSelect = onHide),
            MenuEntry("終了", onSelect = onExit),
        )
    val latestId = posts.lastOrNull()?.id
    LaunchedEffect(latestId) {
        if (posts.isNotEmpty()) {
            listState.scrollToItem(posts.lastIndex)
            listState.scrollBy(100_000f)
        }
    }
    LaunchedEffect(hasRecovery) {
        if (hasRecovery) {
            focus = LobbyFocus.RECOVERY
        } else if (focus == LobbyFocus.RECOVERY) {
            focus = LobbyFocus.MENU
        }
    }
    LaunchedEffect(posts.isEmpty()) {
        if (posts.isEmpty() && focus == LobbyFocus.CHAT) {
            focus = if (hasRecovery) LobbyFocus.RECOVERY else LobbyFocus.MENU
        }
    }

    BackHandler { if (menuActive) menuOpen = false else onBack() }
    GlassScreen(
        modifier =
            glassControls(
                onUp = {
                    if (menuOpen) {
                        selectedMenuEntry = nextHomeEntry(menuEntries, selectedMenuEntry, -1)
                    } else if (!menuActive) {
                        val canScrollOlder = listState.canScrollBackward
                        if (focus == LobbyFocus.CHAT && canScrollOlder) {
                            scope.launch { listState.animateScrollBy(with(density) { -80.dp.toPx() }) }
                        } else {
                            focus = moveLobbyFocus(focus, LobbyDirection.UP, hasRecovery, posts.isNotEmpty(), canScrollOlder)
                        }
                    }
                },
                onDown = {
                    if (menuOpen) {
                        selectedMenuEntry = nextHomeEntry(menuEntries, selectedMenuEntry, 1)
                    } else if (!menuActive) {
                        if (focus == LobbyFocus.CHAT && listState.canScrollForward) {
                            scope.launch { listState.animateScrollBy(with(density) { 80.dp.toPx() }) }
                        } else {
                            focus =
                                moveLobbyFocus(
                                    focus,
                                    LobbyDirection.DOWN,
                                    hasRecovery,
                                    posts.isNotEmpty(),
                                    canScrollNewer = listState.canScrollForward,
                                )
                        }
                    }
                },
                onSelect = {
                    if (menuOpen) {
                        menuEntries.getOrNull(selectedMenuEntry)?.takeIf { it.enabled }?.let {
                            menuOpen = false
                            it.onSelect()
                        }
                    } else if (!menuActive) {
                        when (selectedLobbyAction(focus, signInRequired, connectionError)) {
                            LobbyAction.MENU -> {
                                selectedMenuEntry = 0
                                menuOpen = true
                            }
                            LobbyAction.SIGN_IN -> onSignIn()
                            LobbyAction.RETRY -> onRetry()
                            LobbyAction.NONE -> Unit
                        }
                    }
                },
                onDoubleSelect = { if (menuActive) menuOpen = false else onBack() },
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "開発用チャネル",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (connected) scheme.primary else scheme.secondary.copy(alpha = 0.5f)),
                        )
                        Text(
                            text =
                                " " +
                                    when {
                                        signInRequired -> "要ログイン"
                                        connectionError -> "接続エラー"
                                        else -> status
                                    },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connected) scheme.primary else scheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (posts.isNotEmpty()) {
                        val chatStyle = glassActionStyle(focus == LobbyFocus.CHAT && !menuActive)
                        Text(
                            if (focus == LobbyFocus.CHAT && !menuActive) "会話を移動中" else "会話",
                            modifier =
                                Modifier
                                    .glassAction(chatStyle) { focus = LobbyFocus.CHAT }
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = chatStyle.contentColor,
                            fontWeight = chatStyle.fontWeight,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    val menuStyle = glassActionStyle(focus == LobbyFocus.MENU && !menuActive)
                    Text(
                        "操作メニュー ›",
                        modifier =
                            Modifier
                                .glassAction(menuStyle) {
                                    focus = LobbyFocus.MENU
                                    selectedMenuEntry = 0
                                    menuOpen = true
                                }.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = menuStyle.contentColor,
                        fontWeight = menuStyle.fontWeight,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (signInRequired || connectionError) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val recoveryStyle = glassActionStyle(focus == LobbyFocus.RECOVERY && !menuActive)
                        Text(
                            if (signInRequired) "Slackへのサインインが必要" else status,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (signInRequired) "サインイン ›" else "再試行 ›",
                            modifier =
                                Modifier
                                    .glassAction(recoveryStyle) {
                                        focus = LobbyFocus.RECOVERY
                                        if (signInRequired) onSignIn() else onRetry()
                                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = recoveryStyle.contentColor,
                            fontWeight = recoveryStyle.fontWeight,
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                ) {
                    if (posts.isEmpty()) {
                        Text(
                            text =
                                if (connected) {
                                    "まだ投稿はありません"
                                } else if (signInRequired || connectionError) {
                                    "会話を表示できません"
                                } else {
                                    status
                                },
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.secondary,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                        ) {
                            items(posts, key = { it.id }) { post ->
                                // 文言にしてから渡す。表示が変わる吹き出し（分の境目を越えたもの）だけが再描画される。
                                TeamBubble(post, elapsed = post.createdAtMs?.let { RelativeTime.format(it, nowMs) })
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visibleState = menuVisibility,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.67f)
                        .fillMaxHeight()
                        .clipToBounds(),
                enter = slideInHorizontally(animationSpec = menuSlide, initialOffsetX = { it }),
                exit = slideOutHorizontally(animationSpec = menuSlide, targetOffsetX = { it }),
            ) {
                HomeActionPanel(
                    entries = menuEntries,
                    selected = selectedMenuEntry,
                    modifier = Modifier.fillMaxSize(),
                    onSelect = { index ->
                        menuEntries.getOrNull(index)?.takeIf { menuOpen && it.enabled }?.let {
                            selectedMenuEntry = index
                            menuOpen = false
                            it.onSelect()
                        }
                    },
                )
            }
            if (!menuOpen && menuActive) {
                // Keep closing gestures from reaching the lobby until the panel has left.
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** 会話を隠さず右側に重ねる半透明パネル。終了の下には何も置かない。 */
@Composable
private fun HomeActionPanel(
    entries: List<MenuEntry>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.86f))
                .border(1.dp, scheme.primary.copy(alpha = 0.8f))
                .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("操作", style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
        entries.forEachIndexed { index, entry ->
            if (index == entries.lastIndex) Spacer(Modifier.weight(1f))
            val actionStyle = glassActionStyle(selected == index, entry.enabled)
            Text(
                text = if (entry.enabled) entry.label else "${entry.label}（準備中）",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .glassAction(actionStyle) { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = actionStyle.contentColor,
                fontWeight = actionStyle.fontWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun nextHomeEntry(
    entries: List<MenuEntry>,
    current: Int,
    step: Int,
): Int {
    if (entries.isEmpty()) return current
    for (offset in 1..entries.size) {
        val index = (current + offset * step + entries.size * 2) % entries.size
        if (entries[index].enabled) return index
    }
    return current
}

@Composable
private fun TeamBubble(
    post: FeedPost,
    elapsed: String?,
) {
    val scheme = MaterialTheme.colorScheme
    val own = post.isOwn == true
    val shape =
        RoundedCornerShape(
            topStart = 12.dp,
            topEnd = 12.dp,
            bottomEnd = if (own) 2.dp else 12.dp,
            bottomStart = if (own) 12.dp else 2.dp,
        )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (own) 0.dp else 35.dp, end = if (own) 35.dp else 0.dp),
            horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                post.author,
                modifier = Modifier.widthIn(max = 170.dp),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            elapsed?.let { label ->
                Text(
                    "  $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.secondary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!own) AuthorAvatar(post.author)
            Column(
                modifier =
                    Modifier
                        // Reserve the avatar's width before measuring either side's bubble.
                        .weight(1f, fill = false)
                        .widthIn(max = 280.dp)
                        .clip(shape)
                        .background(if (own) scheme.primary.copy(alpha = 0.22f) else scheme.secondary.copy(alpha = 0.16f))
                        .border(1.dp, if (own) scheme.primary else scheme.secondary, shape)
                        .padding(horizontal = 9.dp, vertical = 6.dp),
            ) {
                val visibleText = post.displayText.takeUnless { post.imageCount > 0 && post.text.trim() == "(コメントなし)" }.orEmpty()
                if (visibleText.isNotBlank()) {
                    Text(
                        visibleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }
                if (post.imageCount > 0) {
                    Text("[画像]", style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
                } else if (post.attachmentCount > 0) {
                    Text("[添付]", style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
                } else if (visibleText.isBlank()) {
                    Text("(本文なし)", style = MaterialTheme.typography.bodyMedium, color = scheme.secondary)
                }
            }
            if (own) AuthorAvatar(post.author)
        }
    }
}

@Composable
private fun AuthorAvatar(author: String) {
    val scheme = MaterialTheme.colorScheme
    val initial =
        author
            .trim()
            .firstOrNull()
            ?.uppercaseChar()
            ?.toString() ?: "?"
    Box(
        modifier =
            Modifier
                .padding(horizontal = 5.dp)
                .size(25.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.3f))
                .border(1.dp, scheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = scheme.primary)
    }
}
