package com.scraft.glassreport.feature.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scraft.glassreport.capture.tts.NoopSpeaker
import com.scraft.glassreport.capture.tts.Speaker
import com.scraft.glassreport.core.sound.Chime
import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.data.repo.SeenPostCursor
import com.scraft.glassreport.data.repo.SeenPosts
import com.scraft.glassreport.data.repo.refreshes
import com.scraft.glassreport.di.rememberContainer
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 通知は Feed の30秒更新より早く気づけるよう、アプリ前面中は10秒ごとに確認する。 */
const val CHAT_NOTIFICATION_POLL_MS = 10_000L

/** 読み切れる長さを確保しつつ、作業画面を塞ぎ続けない表示時間。 */
const val CHAT_NOTIFICATION_HOLD_MS = 8_000L

data class ChatNotificationUiState(
    val post: FeedPost? = null,
    val signInRequired: Boolean = false,
)

/**
 * APK 固定の Teams チャネルをアプリ前面中だけ監視し、新着を一時的な HUD カードへする。
 * 初回取得は既読の基準作りだけに使い、過去メッセージを起動のたびに通知しない。
 */
class TeamsChatNotificationViewModel(
    private val repository: FeedRepository,
    private val chime: Chime,
    private val speaker: Speaker = NoopSpeaker,
    private val seenPosts: SeenPosts = SeenPostCursor(),
    private val canClaim: () -> Boolean = { true },
    private val pollMs: Long = CHAT_NOTIFICATION_POLL_MS,
    private val holdMs: Long = CHAT_NOTIFICATION_HOLD_MS,
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatNotificationUiState())
    val ui: StateFlow<ChatNotificationUiState> = _ui.asStateFlow()

    private var hideJob: Job? = null

    suspend fun poll(notify: Boolean = true) {
        repository.refreshes(pollMs).collect {
            refresh(notify)
        }
    }

    suspend fun refresh(notify: Boolean = true) {
        repository.latest().fold(
            onSuccess = { posts ->
                if (!canClaim()) return@fold
                // Re-render an existing card in the new language without playing a second alert.
                _ui.value.post
                    ?.id
                    ?.let { id ->
                        posts.firstOrNull { it.id == id }?.let { post ->
                            if (!post.canSpeak || post.language != _ui.value.post?.language) speaker.stop()
                            _ui.value = _ui.value.copy(post = post)
                        }
                    }
                val newest = seenPosts.claimNewest(posts)
                if (newest != null && notify) show(newest)
            },
            onFailure = { error ->
                if (error is SignInRequiredException) {
                    _ui.value = _ui.value.copy(signInRequired = true)
                }
            },
        )
    }

    fun onSignInHandled() {
        _ui.value = _ui.value.copy(signInRequired = false)
    }

    fun dismiss() {
        hideJob?.cancel()
        _ui.value = _ui.value.copy(post = null)
    }

    private fun show(post: FeedPost) {
        chime.play()
        val spoken =
            if (post.imageCount > 0 && (post.text.isBlank() || post.text.trim() == "(コメントなし)")) {
                "画像"
            } else {
                post.text.ifBlank { "新着メッセージ" }
            }
        if (post.canSpeak && post.text.isNotBlank()) speaker.speak(spoken, post.language)
        hideJob?.cancel()
        _ui.value = _ui.value.copy(post = post)
        hideJob =
            viewModelScope.launch {
                delay(holdMs)
                if (_ui.value.post?.id == post.id) _ui.value = _ui.value.copy(post = null)
            }
    }

    override fun onCleared() {
        chime.close()
        speaker.close()
    }
}

/** ホーム・撮影・設定などの上端へ重ねる、操作を奪わない通知カード。 */
@Composable
fun TeamsChatNotificationHost(suppressed: Boolean) {
    val container = rememberContainer()
    val viewModel: TeamsChatNotificationViewModel =
        viewModel(
            key = "notification-Slack",
            factory =
                viewModelFactory {
                    initializer {
                        TeamsChatNotificationViewModel(
                            repository = container.channelNotificationRepository,
                            chime = container.newChime(),
                            speaker = container.newSpeaker(),
                            seenPosts = container.seenPosts,
                            canClaim = { AppVisibility.activityVisible },
                        )
                    }
                },
        )
    val ui by viewModel.ui.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, suppressed, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.poll(notify = !suppressed) }
    }
    LaunchedEffect(suppressed) { if (suppressed) viewModel.dismiss() }
    if (!suppressed) ui.post?.let { ChatNotificationCard(it) }
}

@Composable
internal fun ChatNotificationCard(post: FeedPost) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.background)
                .border(2.dp, scheme.primary, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = notificationTitle(post),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = scheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text =
                if (post.imageCount > 0 && (post.text.isBlank() || post.text.trim() == "(コメントなし)")) {
                    "[画像]"
                } else {
                    post.displayText.ifBlank { "(本文なし)" }
                },
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.primary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 通知は Teams 投稿者名を見出しにする。 */
internal fun notificationTitle(post: FeedPost): String = post.author.ifBlank { "(不明な送信者)" }
