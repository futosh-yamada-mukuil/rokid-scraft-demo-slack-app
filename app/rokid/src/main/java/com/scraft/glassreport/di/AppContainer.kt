package com.scraft.glassreport.di

import android.content.Context
import com.scraft.glassreport.BuildConfig
import com.scraft.glassreport.capture.asr.OfflineWavTranscriber
import com.scraft.glassreport.capture.asr.RecordedSpeechSource
import com.scraft.glassreport.capture.asr.SpeechEngine
import com.scraft.glassreport.capture.asr.SpeechSource
import com.scraft.glassreport.capture.asr.WavTranscriber
import com.scraft.glassreport.capture.tts.GoogleTtsSpeaker
import com.scraft.glassreport.capture.tts.NoopSpeaker
import com.scraft.glassreport.capture.tts.Speaker
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.core.sound.Chime
import com.scraft.glassreport.core.sound.ToneChime
import com.scraft.glassreport.data.prefs.CaptureSettings
import com.scraft.glassreport.data.prefs.GlassConfigStore
import com.scraft.glassreport.data.prefs.TokenStore
import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.data.repo.FeedTranslationCache
import com.scraft.glassreport.data.repo.PersistentSeenPosts
import com.scraft.glassreport.data.repo.TranslatedFeedRepository
import com.scraft.glassreport.domain.translate.Translator
import com.scraft.glassreport.net.AndroidAppIdentity
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.androidAppIdentity
import com.scraft.glassreport.net.gstt.GoogleSttClient
import com.scraft.glassreport.net.gtts.GoogleTtsClient
import com.scraft.glassreport.net.slack.SlackAuth
import com.scraft.glassreport.net.slack.SlackRelayClient
import com.scraft.glassreport.net.teams.SignInFlow
import com.scraft.glassreport.net.translate.CloudTranslationClient
import com.scraft.glassreport.preview.UiPreviewServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

/**
 * 手動 DI コンテナ。アプリ全体の依存をここで1か所に配線する（specs: 入口は配線のみ）。
 * 画面はインターフェース(MediaSink / Speaker / Translator / SpeechSource など)経由で受け取り、
 * テスト時はフェイクを注入する。
 *
 * - MediaSink … 撮影物の送信先（Slack のみ）
 * - Speaker … Google Cloud TTS 実装（実機に内蔵 TTS が無いためクラウド合成が唯一の手段）
 * - Translator … Cloud Translation Basic（元言語自動判定→設定言語）
 * - SpeechSource … Google / Offline 実装（実機に標準音声認識エンジンが無い）
 */
class AppContainer(
    context: Context,
) {
    val glassConfig = GlassConfigStore.create(context)

    /** 実装を差し替える際に必要になるアプリケーション Context。 */
    val appContext: Context = context.applicationContext

    /** エミュレーター専用ビルドだけ、既存UIへローカルのサンプルを渡す。 */
    val uiPreview: UiPreviewServices? = if (BuildConfig.UI_PREVIEW) UiPreviewServices() else null

    val channelConfigured: Boolean get() = uiPreview != null || slackClient.configured
    val signInFlow: SignInFlow get() = uiPreview?.auth ?: slackAuth

    /**
     * API キーに Android アプリ制限を掛けたときに必要になる 2 ヘッダの値。
     * 起動を遅くしないよう最初に使うときだけ署名を読む。
     */
    private val appIdentity: AndroidAppIdentity? by lazy { androidAppIdentity(appContext) }

    /** Slackの端末接続情報を暗号化保存する。Teamsの既存保存領域とは分ける。 */
    val tokenStore: TokenStore by lazy { TokenStore.create(appContext, "slack_relay_tokens") }

    /** PC中継サーバへの端末登録。SlackのBotトークンはAPKに入れない。 */
    val slackAuth: SlackAuth by lazy { SlackAuth(tokenStore) }

    val slackClient: SlackRelayClient by lazy { SlackRelayClient(slackAuth) }

    /** 送信先は Slack のみ。未設定でも HTTP モニターへフォールバックしない。 */
    val sinks: List<MediaSink> =
        uiPreview?.let { listOf(it.sink) } ?: mediaSinks(
            teams =
                slackClient
                    .takeIf { it.configured }
                    ?.withVoiceComment { wav ->
                        val transcriber = newWavTranscriber()
                        try {
                            transcriber.transcribe(wav)
                        } finally {
                            transcriber.close()
                        }
                    },
        )

    /**
     * 画質設定（写真の長辺・動画の解像度）。**アプリで1つだけ**持ち、設定画面と撮影画面が
     * 同じ StateFlow を見ることで変更が即座に反映される。
     */
    val captureSettings: CaptureSettings = CaptureSettings.create(appContext)

    /** Direct Cloud Translation Basic; feed cache handles bounded concurrency and service backoff. */
    val translator: Translator by lazy { uiPreview?.translator ?: CloudTranslationClient(identity = appIdentity) }

    private fun channelKey(): String =
        "slack/${BuildConfig.SLACK_RELAY_URL}/${BuildConfig.SLACK_WORKSPACE_ID}/${BuildConfig.SLACK_CHANNEL_ID}"

    private val translationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val translationCache by lazy { FeedTranslationCache(translator, translationScope) }
    val lobbyRepository: FeedRepository by lazy {
        uiPreview?.repository ?: TranslatedFeedRepository(
            source = slackClient,
            cache = translationCache,
            channel = ::channelKey,
            target = { SpeechLanguage.fromCode(glassConfig.load().language.ifBlank { "ja" }) },
            configUpdates = glassConfig.revisions.map { Unit },
        )
    }

    /** 受信・通知とも APK 固定の Slack チャネルだけを参照する。 */
    val feedRepository: FeedRepository get() = channelNotificationRepository
    val notificationRepository: FeedRepository get() = channelNotificationRepository

    /** ロビーは送信先の設定に関係なくSlackチャネルを表示・通知する。 */
    val channelNotificationRepository: FeedRepository by lazy {
        object : FeedRepository {
            override val updates = lobbyRepository.updates

            override suspend fun latest() = lobbyRepository.latest().map { it.take(com.scraft.glassreport.net.teams.FEED_TOP) }
        }
    }

    /** Activity と常駐サービスが共有するSlackチャネル別の既読ID。 */
    val seenPosts: PersistentSeenPosts by lazy {
        PersistentSeenPosts.create(appContext, ::channelKey)
    }

    /** 読み上げ（TTS）。画面ごとに生成・破棄する想定（画面を抜けるときに close する）。 */
    fun newSpeaker(): Speaker {
        if (uiPreview != null) return NoopSpeaker
        return GoogleTtsSpeaker(GoogleTtsClient(identity = appIdentity), speakingRate = { captureSettings.ttsSpeed.value })
    }

    /** 新着の通知音。画面ごとに生成・破棄する想定（LED が使えないため音で知らせる）。 */
    fun newChime(): Chime = ToneChime(appContext)

    /** Five-second local recording with the selected recognition engine; no relay. */
    fun newSpeechSource(): SpeechSource =
        uiPreview?.newSpeechSource() ?: RecordedSpeechSource(
            transcriber = newWavTranscriber(),
            durationMs = 5_000L,
        )

    /** 未選択でGoogleキーも無い場合は、同梱のオフライン認識を使用する。明示的な選択は保持。 */
    val speechEngine: SpeechEngine
        get() =
            SpeechEngine.fromCode(
                glassConfig.load().asrEngine,
                default = if (BuildConfig.GOOGLE_STT_TTS_TRANSLATE_API_KEY.isNotBlank()) SpeechEngine.GOOGLE else SpeechEngine.OFFLINE,
            )

    private fun newWavTranscriber(): WavTranscriber =
        when (speechEngine) {
            SpeechEngine.GOOGLE -> GoogleSttClient(identity = appIdentity)
            SpeechEngine.OFFLINE -> OfflineWavTranscriber(appContext)
        }
}
