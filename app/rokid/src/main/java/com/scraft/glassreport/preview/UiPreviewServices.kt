package com.scraft.glassreport.preview

import com.scraft.glassreport.capture.asr.RecordedSpeechSource
import com.scraft.glassreport.capture.asr.RecordedVoice
import com.scraft.glassreport.capture.asr.SpeechSource
import com.scraft.glassreport.capture.asr.VOICE_SAMPLE_RATE
import com.scraft.glassreport.capture.asr.VoiceCapture
import com.scraft.glassreport.capture.asr.VoiceRecorder
import com.scraft.glassreport.capture.asr.VoiceStopSignal
import com.scraft.glassreport.capture.asr.WavTranscriber
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.translate.Translator
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.SendOutcome
import com.scraft.glassreport.net.teams.DeviceCode
import com.scraft.glassreport.net.teams.SignInFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** UI操作だけを再現する。HTTPクライアント・本物の認証情報・マイクは使用しない。 */
class UiPreviewServices(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val posts =
        MutableStateFlow(
            listOf(
                FeedPost("preview-reply", "遠隔担当者", "確認しました。そのまま作業を進めてください。", now() - 60_000),
                FeedPost("preview-photo", "現場担当者", "こちらの設備をご確認ください。", now() - 120_000, imageCount = 1, isOwn = true),
                FeedPost("preview-start", "遠隔担当者", "現場の写真とコメントを送ってください。", now() - 180_000),
            ),
        )
    private var sequence = 0

    val repository: FeedRepository =
        object : FeedRepository {
            override val updates = posts.map { Unit }

            override suspend fun latest(): Result<List<FeedPost>> = Result.success(posts.value)
        }

    val sink: MediaSink =
        object : MediaSink {
            override val label = "Slack"
            override val detail = "UIサンプル（外部送信なし）"

            override fun accepts(mime: String): Boolean = mime.startsWith("image/")

            override suspend fun send(
                bytes: ByteArray,
                mime: String,
                comment: String,
                voice: ByteArray?,
            ): Result<SendOutcome> {
                if (bytes.isEmpty() || !accepts(mime)) return Result.failure(IllegalArgumentException("写真がありません"))
                delay(600)
                addPost("現場担当者", comment.ifBlank { "(コメントなし)" }, own = true, images = 1)
                return Result.success(SendOutcome(600))
            }
        }

    /** 認証画面の見た目を確認するため待機を続ける。サインインは行わない。 */
    val auth: SignInFlow =
        object : SignInFlow {
            override val configured = true

            override fun signedIn() = false

            override suspend fun accessToken(forceRefresh: Boolean): String? = null

            override suspend fun requestDeviceCode(): Result<DeviceCode> =
                Result.success(DeviceCode("DEMO1234", "https://example.invalid/ui-preview", "", 5_000, now() + 900_000))

            override suspend fun awaitSignIn(code: DeviceCode): Result<Unit> = awaitCancellation()
        }

    val translator: Translator =
        object : Translator {
            override suspend fun toEnglish(text: String): Result<String> =
                Result.success("[UI sample] Please check the equipment. You may continue working.")

            override suspend fun translate(
                text: String,
                targetLanguage: SpeechLanguage,
            ): Result<String> =
                when (targetLanguage) {
                    SpeechLanguage.JA -> Result.success(text)
                    SpeechLanguage.EN -> toEnglish(text)
                    SpeechLanguage.ZH_CN -> Result.success("[UI示例] 请检查设备。请继续作业。")
                    SpeechLanguage.KO -> Result.success("[UI 예시] 설비를 확인해 주세요. 작업을 계속해 주세요.")
                    else -> Result.failure(UnsupportedOperationException("UIサンプルがありません"))
                }
        }

    fun addReply() = addPost("遠隔担当者", "受信サンプル：設備を確認しました。次の作業へ進んでください。")

    private fun addPost(
        author: String,
        text: String,
        own: Boolean = false,
        images: Int = 0,
    ) {
        val post = FeedPost("preview-${now()}-${++sequence}", author, text, now(), imageCount = images, isOwn = own)
        posts.value = (listOf(post) + posts.value).take(20)
    }

    /** 既存の録音・一時停止・確認UIを、実録音の代わりに固定PCMと固定コメントで動かす。 */
    fun newSpeechSource(): SpeechSource =
        RecordedSpeechSource(
            recorder =
                object : VoiceCapture {
                    override suspend fun record(durationMs: Long): RecordedVoice = record(durationMs, VoiceStopSignal())

                    override suspend fun record(
                        durationMs: Long,
                        stop: VoiceStopSignal,
                    ): RecordedVoice {
                        withTimeoutOrNull(durationMs) { stop.awaitStop() }
                        val pcm = ByteArray((VOICE_SAMPLE_RATE * 2 * durationMs / 1_000).toInt())
                        return RecordedVoice(VoiceRecorder.toWav(pcm), durationMs, peak = 1_000)
                    }
                },
            transcriber =
                object : WavTranscriber {
                    override val available = true

                    override suspend fun transcribe(wav: ByteArray): Result<String> {
                        delay(300)
                        return Result.success("UIサンプル：こちらの設備をご確認ください。")
                    }

                    override fun close() {}
                },
            durationMs = 5_000,
        )
}
