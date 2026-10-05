package com.scraft.glassreport.di

import com.scraft.glassreport.capture.camera.PHOTO_TEAMS_MAX_EDGE
import com.scraft.glassreport.capture.camera.PhotoCapture
import com.scraft.glassreport.net.JpegDownscaler
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.SendOutcome
import com.scraft.glassreport.net.limitedToLongEdge

/**
 * 撮影画面に出す宛先の一覧を組む。**先頭が既定**。
 *
 * ここが「宛先ごとの都合」を足す唯一の場所。
 * 写真は長辺 [PHOTO_TEAMS_MAX_EDGE]px（従来と同じ1920px）に落としてから渡す。
 * [teams] は既存テストとの互換性のため残した引数名で、現在はSlack送信先を渡す。
 * null（中継URL未設定）なら候補に出さない。
 * [downscaler] は `android.graphics` に依るので、ローカル JVM テストでは差し替える。
 */
fun mediaSinks(
    teams: MediaSink?,
    downscaler: JpegDownscaler = JpegDownscaler { jpeg, maxEdge -> PhotoCapture.downscale(jpeg, maxEdge) },
): List<MediaSink> = listOfNotNull(teams?.limitedToLongEdge(PHOTO_TEAMS_MAX_EDGE, downscaler))

/** WAV文字起こしを、写真の送信前に端末側で補う。 */
fun MediaSink.withVoiceComment(recognize: suspend (ByteArray) -> Result<String>): MediaSink {
    val delegate = this
    return object : MediaSink {
        override val label: String get() = delegate.label
        override val detail: String get() = delegate.detail

        override fun accepts(mime: String): Boolean = delegate.accepts(mime)

        override suspend fun send(
            bytes: ByteArray,
            mime: String,
            comment: String,
            voice: ByteArray?,
        ): Result<SendOutcome> {
            val resolved =
                if (comment.isBlank() && voice != null) {
                    recognize(voice).getOrElse { return Result.failure(it) }.trim()
                } else {
                    comment
                }
            return delegate.send(bytes, mime, resolved, voice)
        }
    }
}
