package com.scraft.glassreport.net

import java.io.IOException

/** 写真の Content-Type。 */
const val MIME_JPEG = "image/jpeg"

/**
 * 動画の Content-Type。**Teams へは送れない**（`hostedContents` は画像専用）ため、
 * 受信モニタ専用。宛先が受け取れるかは [MediaSink.accepts] で判定する。
 */
const val MIME_MP4 = "video/mp4"

/** 送信できたときの結果。[elapsedMs] は送信開始から応答までの実測値。 */
data class SendOutcome(
    val elapsedMs: Long,
)

/**
 * 撮影物（写真／動画）の送信先。
 *
 * 撮影画面は宛先の実体（開発用の受信モニタか Teams か）を知らずに [send] を呼ぶ。
 * 宛先を増やすときはこのインターフェースを実装して `AppContainer.sinks` に足すだけでよい。
 */
interface MediaSink {
    /** HUD 上段に出す短い宛先名（「モニタ」「Teams」）。 */
    val label: String

    /** HUD 下段に出す宛先の詳細（受信モニタのホスト名など）。 */
    val detail: String

    /** この宛先が受け取れる Content-Type か。動画を受けない宛先はここで弾く。 */
    fun accepts(mime: String): Boolean

    /**
     * [bytes] を [mime] として送る。
     * **例外は投げず** [Result] に畳んで返す（失敗理由は HUD にそのまま出せる短い日本語にする）。
     */
    suspend fun send(
        bytes: ByteArray,
        mime: String,
        comment: String = "",
        voice: ByteArray? = null,
    ): Result<SendOutcome>
}

/**
 * 宛先が「先に人のサインインが必要」と言っている状態。
 * 撮影画面はこれを見て認証画面へ送るため、宛先固有の文言に依存しないよう型で表す。
 */
class SignInRequiredException(
    message: String,
) : IOException(message)

/** 送信先が1つも設定されていないときの空実装。UI に分岐を持ち込まないために置く。 */
object NoMediaSink : MediaSink {
    private const val REASON = "送信先が未設定"

    override val label: String = "未設定"
    override val detail: String = REASON

    override fun accepts(mime: String): Boolean = false

    override suspend fun send(
        bytes: ByteArray,
        mime: String,
        comment: String,
        voice: ByteArray?,
    ): Result<SendOutcome> = Result.failure(IllegalStateException(REASON))
}
