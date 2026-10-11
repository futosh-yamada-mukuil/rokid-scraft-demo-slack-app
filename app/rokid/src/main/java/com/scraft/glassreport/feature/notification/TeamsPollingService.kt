package com.scraft.glassreport.feature.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.scraft.glassreport.MainActivity
import com.scraft.glassreport.R
import com.scraft.glassreport.SCRAFTApp
import com.scraft.glassreport.capture.tts.Speaker
import com.scraft.glassreport.core.sound.Chime
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SlackPollingService"
private const val CHANNEL_ID = "teams-background-status"
private const val NOTIFICATION_ID = 5042
private const val POLL_MS = 30_000L
private const val ERROR_POLL_MS = 120_000L

/** Activity が閉じた間もTeamsチャネルをGETする。Androidの常駐通知は1件だけ使う。 */
class TeamsPollingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as SCRAFTApp).container
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var pollJob: Job? = null
    private var speaker: Speaker? = null
    private var chime: Chime? = null
    private var lastStatus = "Slack新着を確認中"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        createChannel()
        if (runCatching { startForeground(NOTIFICATION_ID, statusNotification(lastStatus)) }.isFailure) {
            Log.w(TAG, "常駐通知を開始できません")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!container.channelConfigured || !container.signInFlow.signedIn()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pollJob == null) {
            chime = container.newChime()
            speaker = container.newSpeaker()
            pollJob =
                scope.launch {
                    // Activity 前面中は既存HUDが担当。背景になった瞬間に1回確認する。
                    AppVisibility.visible.collectLatest { visible ->
                        if (!visible) {
                            coroutineScope {
                                // 中継サーバの変化通知や翻訳の完了が届いたら、次の定期確認を待たずに確かめる。
                                // 届かない間は従来どおり POLL_MS / ERROR_POLL_MS の間隔で確認する。
                                val wake = Channel<Unit>(Channel.CONFLATED)
                                launch { container.channelNotificationRepository.updates.collect { wake.trySend(Unit) } }
                                while (isActive) {
                                    val retryMs = refresh()
                                    withTimeoutOrNull(retryMs) { wake.receive() }
                                }
                            }
                        }
                    }
                }
        }
        return START_STICKY
    }

    private suspend fun refresh(): Long {
        val result =
            try {
                container.channelNotificationRepository.latest()
            } catch (cancelled: CancellationException) {
                // 前面復帰やサービス終了による取り消しを「接続できません」と通知へ書かない。
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        // 通信中にActivityが前面へ戻ったら既読を奪わず、HUD側に引き継ぐ。
        if (AppVisibility.activityVisible) return POLL_MS
        return result.fold(
            onSuccess = { posts ->
                val newest = container.seenPosts.claimNewest(posts)
                if (newest != null) {
                    notifyNewPost(newest)
                } else {
                    updateStatus("Slack新着を確認中")
                }
                POLL_MS
            },
            onFailure = { error ->
                if (error is SignInRequiredException) {
                    updateStatus("Slackへ再サインインしてください")
                } else {
                    updateStatus("Slackに接続できません。再試行します")
                }
                ERROR_POLL_MS
            },
        )
    }

    private fun notifyNewPost(post: FeedPost) {
        val text =
            if (post.imageCount > 0 && (post.text.isBlank() || post.text.trim() == "(コメントなし)")) {
                "[画像]"
            } else {
                post.displayText.ifBlank { "新着メッセージ" }
            }
        runCatching { chime?.play() }.onFailure { Log.w(TAG, "通知音を再生できません", it) }
        if (post.canSpeak && post.text.isNotBlank()) {
            runCatching { speaker?.speak(post.text, post.language) }.onFailure { Log.w(TAG, "読み上げを開始できません", it) }
        }
        updateStatus("${post.author}: ${text.take(60)}")
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Slack新着監視", NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
    }

    private fun statusNotification(message: String): Notification {
        val openApp = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Slack通知を監視中")
            .setContentText(message)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun updateStatus(message: String) {
        if (message == lastStatus) return
        lastStatus = message
        runCatching { notificationManager.notify(NOTIFICATION_ID, statusNotification(message)) }
            .onFailure { Log.w(TAG, "通知表示に失敗", it) }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        chime?.close()
        speaker?.close()
        super.onDestroy()
    }

    companion object {
        /** Android 12では背景から新規起動できないため、Activityが見えている間だけ呼ぶ。 */
        fun startIfReady(context: Context) {
            val app = context.applicationContext as SCRAFTApp
            if (!app.container.channelConfigured || !app.container.signInFlow.signedIn()) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TeamsPollingService::class.java)) }
                .onFailure { Log.w(TAG, "Slack監視サービスを開始できません", it) }
        }
    }
}
