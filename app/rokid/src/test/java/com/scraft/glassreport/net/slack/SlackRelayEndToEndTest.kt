package com.scraft.glassreport.net.slack

import com.scraft.glassreport.capture.tts.NoopSpeaker
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.core.sound.NoopChime
import com.scraft.glassreport.data.prefs.InMemoryTokenStore
import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.data.repo.FeedTranslationCache
import com.scraft.glassreport.data.repo.SeenPostCursor
import com.scraft.glassreport.data.repo.TranslatedFeedRepository
import com.scraft.glassreport.domain.translate.Translator
import com.scraft.glassreport.feature.notification.CHAT_NOTIFICATION_POLL_MS
import com.scraft.glassreport.feature.notification.TeamsChatNotificationViewModel
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.teams.FEED_TOP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 別プロセスで動く**実物の中継サーバ**に、端末側の本物の通信・翻訳キャッシュ・通知ロジックを繋ぐ通し確認。
 *
 * 端末登録 → 写真送信 → Slack のスレッド返信 → 画面の通知、までを実時間で流し、
 * 返信が通知に出るまでの時間を測る。Slack 側は模擬（`app/api/test/support/fake-slack.ts`）で、
 * **実際のワークスペースへは接続しない**。
 *
 * 起動は `bash scripts/verify-slack-relay-e2e.sh`。宛先は環境変数で受け取り、
 * 無ければ何もしない（通常のユニットテスト実行を外部プロセスに依存させない）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlackRelayEndToEndTest {
    private val http = OkHttpClient()

    @Test
    fun photoIsPostedAndThreadRepliesReachTheNotification() {
        val relay = System.getenv("SLACK_RELAY_E2E_URL")
        val control = System.getenv("SLACK_RELAY_E2E_CONTROL")
        assumeTrue("中継サーバの宛先が無いので通し確認を行わない", !relay.isNullOrBlank() && !control.isNullOrBlank())
        val samples = System.getenv("SLACK_RELAY_E2E_SAMPLES")?.toIntOrNull() ?: 5
        val photos = System.getenv("SLACK_RELAY_E2E_PHOTOS")?.toIntOrNull() ?: 3
        // 0 は変化待ちを使わない従来の動き（10秒ごとの定期取得のみ）。改善前との比較用。
        val changeWait = System.getenv("SLACK_RELAY_E2E_CHANGE_WAIT")?.toIntOrNull() ?: 25
        val limitMs = System.getenv("SLACK_RELAY_E2E_LIMIT_MS")?.toLongOrNull() ?: 8_000L
        val maxGapMs = System.getenv("SLACK_RELAY_E2E_MAX_GAP_MS")?.toLongOrNull() ?: 4_700L

        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Dispatchers.setMain(main)
        try {
            runBlocking(main) {
                // 端末登録。PC の登録ページでコードを承認する操作だけを制御用の口で代行する。
                val auth = SlackAuth(InMemoryTokenStore(), relay!!, deviceLabel = "E2E")
                val code = auth.requestDeviceCode().getOrThrow()
                control("$control/approve?code=${code.userCode}")
                auth.awaitSignIn(code).getOrThrow()
                assertTrue(auth.signedIn())

                val client = SlackRelayClient(auth, scope = scope, changeWaitSeconds = changeWait)
                val jpeg = byteArrayOf(-1, -40, -1, 0) + ByteArray(20_000) { it.toByte() }
                val sendMs =
                    (1..photos).map { n ->
                        client.send(jpeg, MIME_JPEG, "E2E 写真 $n", null).getOrThrow().elapsedMs
                    }

                // AppContainer と同じ組み立て（翻訳は即時に原文を返すだけの代役）。
                val translated =
                    TranslatedFeedRepository(
                        source = client,
                        cache = FeedTranslationCache(EchoTranslator, scope),
                        channel = { "e2e" },
                        target = { SpeechLanguage.JA },
                        configUpdates = emptyFlow(),
                    )
                val notifications =
                    object : FeedRepository {
                        override val updates = translated.updates

                        override suspend fun latest() = translated.latest().map { it.take(FEED_TOP) }
                    }
                val vm =
                    TeamsChatNotificationViewModel(
                        repository = notifications,
                        chime = NoopChime,
                        speaker = NoopSpeaker,
                        seenPosts = SeenPostCursor(),
                        pollMs = CHAT_NOTIFICATION_POLL_MS,
                    )
                val polling = launch { vm.poll() }
                // 初回取得は既読の基準作り。送った写真が通知されないことも合わせて確かめる。
                delay(4_000)
                assertEquals(null, vm.ui.value.post)

                val latencies =
                    (1..samples).map { n ->
                        // 中継サーバの確認周期・端末の定期取得と位相が揃わないよう、送る時刻をずらす。
                        delay(Random.nextLong(300, maxGapMs))
                        val text = "E2E 返信 $n"
                        val repliedAt = System.nanoTime()
                        control("$control/reply?text=${URLEncoder.encode(text, "UTF-8")}")
                        withTimeout(120_000) { vm.ui.first { it.post?.text == text } }
                        (System.nanoTime() - repliedAt) / 1_000_000
                    }
                polling.cancel()

                // 結果はテストレポートの標準出力に残す（検証スクリプトが拾う）。
                println("E2E_SEND_MS=${sendMs.joinToString(",")}")
                println("E2E_REPLY_TO_NOTIFICATION_MS=${latencies.joinToString(",")}")
                println("E2E_SLACK_CALLS=${control("$control/stats", post = false)}")
                assertTrue("返信が通知に出るまで ${latencies.max()}ms（上限 ${limitMs}ms）", latencies.max() <= limitMs)
            }
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
            main.close()
        }
    }

    private suspend fun control(
        url: String,
        post: Boolean = true,
    ): String =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply { if (post) post(ByteArray(0).toRequestBody()) }
                    .build()
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                check(response.isSuccessful) { "制御用の口が ${response.code} を返しました: $body" }
                body
            }
        }

    private object EchoTranslator : Translator {
        override suspend fun toEnglish(text: String): Result<String> = Result.success(text)

        override suspend fun translate(
            text: String,
            targetLanguage: SpeechLanguage,
        ): Result<String> = Result.success(text)
    }
}
