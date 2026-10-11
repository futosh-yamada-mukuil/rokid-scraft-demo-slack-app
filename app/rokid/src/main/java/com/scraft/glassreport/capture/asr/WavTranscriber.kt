package com.scraft.glassreport.capture.asr

/** Recognizes a local 16 kHz mono PCM16 WAV; recording ownership stays outside the engine. */
interface WavTranscriber {
    val available: Boolean
    val unavailableReason: String get() = "音声認識が利用できません"

    suspend fun transcribe(wav: ByteArray): Result<String>

    /**
     * 録音が始まった合図。認識の前準備（モデルの読み込みなど）を録音の裏で進めてよい。
     * 結果には影響しない。何もしない実装でも [transcribe] は同じ文字起こしを返す。
     */
    fun prepare() {}

    /** [prepare] したが認識しないことになった（無音・録音失敗・取り消し）。抱えた資源を手放す。 */
    fun cancelPrepared() {}

    fun close()
}
