package com.scraft.glassreport.capture.asr

/** 1回の文字起こし結果。実測値も持たせ、HUD とログで所要時間を出せるようにする。 */
data class SpeechResult(
    /** 確定テキスト。認識できなかった場合は空文字（エラーではない）。 */
    val text: String,
    val recordMs: Long,
    val recognizeMs: Long,
    /** マイクが音を拾えていなかった（無音）。「聞き取れなかった」と区別して HUD に出す。 */
    val silent: Boolean = false,
    /** PC側のAUTO音声認識へ渡すWAV。文字起こしが空でも保持する。 */
    val wav: ByteArray? = null,
)

/**
 * 音声認識（ASR）の抽象。実装を隔離し、差し替え・テストを可能にする。
 * 実機（中国国内版 Rokid Glasses）は GMS 非搭載で `SpeechRecognizer` が1つも登録されていないため、
 * 実装はクラウド（AmiVoice）＝[AmiVoiceSpeechSource]。
 */
interface SpeechSource {
    /** この ASR が使えるか（鍵が焼き込まれているか）。マイク権限の有無は呼び出し側が見る。 */
    val available: Boolean
    val unavailableReason: String get() = "音声認識を利用できません"

    /**
     * 1発話ぶん録音して文字起こしする。**例外は投げず** 失敗理由を [Result] に畳んで返す
     * （HUD にそのまま出せる短い日本語）。
     */
    suspend fun listenOnce(): Result<SpeechResult>

    /** Optional split recording API. A session owns its stop signal and keeps the recorded WAV local. */
    fun beginRecording(durationMs: Long? = null): SpeechRecordingSession? = null

    suspend fun transcribe(voice: RecordedVoice): Result<SpeechResult> = Result.failure(UnsupportedOperationException("録音済み音声の認識に対応していません"))

    fun close()
}

interface SpeechRecordingSession {
    suspend fun await(): Result<RecordedVoice>

    fun stop()
}

/** ASR が使えないとき用の no-op（鍵未設定のクローンや、音声コメント無効時）。 */
object NoopSpeechSource : SpeechSource {
    override val available = false

    override suspend fun listenOnce(): Result<SpeechResult> = Result.failure(UnsupportedOperationException("音声認識が利用できません"))

    override fun close() {}
}
