package com.scraft.glassreport.capture.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.scraft.glassreport.net.gtts.GoogleTtsClient
import com.scraft.glassreport.net.gtts.TtsVoice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

private const val TAG = "GoogleTtsSpeaker"

/**
 * Google Cloud TTS で合成した MP3 を `MediaPlayer` で鳴らす [Speaker]。
 *
 * - 合成 → [GoogleTtsClient]（base64 → MP3 のバイト列）
 * - 再生 → [ByteArrayMediaDataSource] 経由。**一時ファイルを作らない**
 * - 音の属性は `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`（読み上げとして扱われる）
 *
 * 例外は外に出さず、失敗はすべて [status] の [SpeakPhase.FAILED] と短い日本語メッセージで表す。
 * `MediaPlayer` の生成とコールバックは [dispatcher]（既定はメインスレッド）で行う。
 * コールバックは生成したスレッドの `Looper` に届くため、ここを勝手に IO へ変えないこと。
 */
class GoogleTtsSpeaker(
    private val client: GoogleTtsClient = GoogleTtsClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val speakingRate: () -> Float = { 1.2f },
) : Speaker {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _status = MutableStateFlow(SpeakStatus())
    override val status: StateFlow<SpeakStatus> = _status.asStateFlow()

    private var player: MediaPlayer? = null
    private var job: Job? = null

    override fun speak(
        text: String,
        language: SpeechLanguage,
    ) {
        stop()
        val body = text.trim()
        if (body.isEmpty()) {
            _status.value = SpeakStatus(SpeakPhase.FAILED, language, "読み上げる文がありません")
            return
        }
        _status.value = SpeakStatus(SpeakPhase.SYNTHESIZING, language)
        job =
            scope.launch {
                client.synthesize(body, language.voice(), speakingRate()).fold(
                    onSuccess = { audio -> play(audio.mp3, language) },
                    onFailure = { error ->
                        Log.w(TAG, "合成に失敗", error)
                        _status.value = SpeakStatus(SpeakPhase.FAILED, language, error.message ?: "読み上げに失敗")
                    },
                )
            }
    }

    override fun stop() {
        job?.cancel()
        job = null
        releasePlayer()
        if (_status.value.phase != SpeakPhase.IDLE) _status.value = SpeakStatus()
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private fun play(
        mp3: ByteArray,
        language: SpeechLanguage,
    ) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mediaPlayer.setDataSource(ByteArrayMediaDataSource(mp3))
            mediaPlayer.setOnPreparedListener { prepared ->
                _status.value = SpeakStatus(SpeakPhase.PLAYING, language)
                prepared.start()
            }
            mediaPlayer.setOnCompletionListener {
                releasePlayer()
                _status.value = SpeakStatus(SpeakPhase.IDLE, language)
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "再生に失敗 what=$what extra=$extra")
                fail(language)
                true // ここで処理済みにする（OnCompletion を呼ばせない）
            }
            // prepare() はメインスレッドを止めるので使わない。
            mediaPlayer.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "再生の準備に失敗", e)
            fail(language)
        } catch (e: RuntimeException) {
            // IllegalStateException / IllegalArgumentException / SecurityException
            Log.w(TAG, "再生を開始できない", e)
            fail(language)
        }
    }

    private fun fail(language: SpeechLanguage) {
        releasePlayer()
        _status.value = SpeakStatus(SpeakPhase.FAILED, language, "音声の再生に失敗")
    }

    private fun releasePlayer() {
        player?.let { old ->
            runCatching { old.reset() }
            runCatching { old.release() }
        }
        player = null
    }

    private fun SpeechLanguage.voice(): TtsVoice =
        when (this) {
            SpeechLanguage.JA -> TtsVoice.JA
            SpeechLanguage.EN -> TtsVoice.EN
            SpeechLanguage.NL -> TtsVoice.NL
            SpeechLanguage.ZH_CN -> TtsVoice.ZH_CN
            SpeechLanguage.ZH_TW -> TtsVoice.ZH_TW
            SpeechLanguage.KO -> TtsVoice.KO
            SpeechLanguage.ES -> TtsVoice.ES
            SpeechLanguage.FR -> TtsVoice.FR
            SpeechLanguage.DE -> TtsVoice.DE
            SpeechLanguage.IT -> TtsVoice.IT
            SpeechLanguage.PT -> TtsVoice.PT
            SpeechLanguage.RU -> TtsVoice.RU
            SpeechLanguage.AR -> TtsVoice.AR
            SpeechLanguage.HI -> TtsVoice.HI
            SpeechLanguage.ID -> TtsVoice.ID
            SpeechLanguage.VI -> TtsVoice.VI
            SpeechLanguage.TH -> TtsVoice.TH
            SpeechLanguage.TR -> TtsVoice.TR
            SpeechLanguage.PL -> TtsVoice.PL
            SpeechLanguage.UK -> TtsVoice.UK
        }
}
