package com.scraft.glassreport.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JPEG を長辺 [maxEdge]px 以下に落とす処理。
 *
 * 実装は `android.graphics`（`PhotoCapture.downscale`）に寄るので、
 * 宛先の組み立て側は**関数として受け取る**（ローカル JVM テストでは差し替える）。
 *
 * 契約: **長辺が [maxEdge] に収まっていれば元をそのまま返す**（無駄な再エンコードをしない）。
 */
fun interface JpegDownscaler {
    fun downscale(
        jpeg: ByteArray,
        maxEdge: Int,
    ): ByteArray
}

/**
 * 宛先固有の上限を掛ける [MediaSink] の decorator。
 *
 * 撮影画面は「設定どおりに撮って [MediaSink] へ渡す」だけで、宛先ごとの都合を知らない。
 * 宛先の側に上限があるなら、その宛先を**これで包んでから** `AppContainer.sinks` に載せる
 * （[com.scraft.glassreport.di.mediaSinks]）。
 *
 * 写真（[MIME_JPEG]）だけを縮める。動画やそれ以外の Content-Type は素通しする
 * （このアプリが作る画像は JPEG だけで、縮小結果も JPEG になるため）。
 */
class DownscalingSink(
    private val delegate: MediaSink,
    private val maxEdge: Int,
    private val downscaler: JpegDownscaler,
) : MediaSink by delegate {
    override suspend fun send(
        bytes: ByteArray,
        mime: String,
        comment: String,
        voice: ByteArray?,
    ): Result<SendOutcome> {
        // 縮小は JPEG のデコードと再エンコードを伴う。送信はメインスレッドから呼ばれるので、
        // ここで逃がさないと「送信中」の表示が出る前に画面が固まる。
        val payload = if (mime == MIME_JPEG) withContext(Dispatchers.Default) { downscaler.downscale(bytes, maxEdge) } else bytes
        return delegate.send(payload, mime, comment, voice)
    }
}

/** `teamsClient.limitedToLongEdge(PHOTO_TEAMS_MAX_EDGE, downscaler)` と書けるようにするだけ。 */
fun MediaSink.limitedToLongEdge(
    maxEdge: Int,
    downscaler: JpegDownscaler,
): MediaSink = DownscalingSink(this, maxEdge, downscaler)
