package com.scraft.glassreport.test

import com.scraft.glassreport.net.JpegDownscaler

/**
 * ローカル JVM テスト用の JPEG 生成・寸法読み取り・縮小。
 *
 * **なぜ本物を使わないか**: 本番の縮小は `PhotoCapture.downscale`（`android.graphics`）だが、
 * `unitTests.returnDefaultValues = true` の JVM では `BitmapFactory` がスタブで何もしない。
 * かといって ImageIO も使えない（Android のユニットテストは `android.jar` に対して
 * コンパイルされるので `java.awt` / `javax.imageio` が存在しない）。
 *
 * そこで**寸法だけ本物の JPEG**を組む。SOI → SOF0（縦横が入るセグメント）→ SOS → EOI の
 * 最小構成で、[longEdge] は実際に SOF0 を読んで長辺を返す。ピクセル処理そのものは実機の担当で、
 * ここで固めたいのは**宛先ごとに縮小が掛かる／掛からないという配線**。
 */
object Jpegs {
    private const val MARKER = 0xFF.toByte()
    private const val SOI = 0xD8.toByte()
    private const val SOF0 = 0xC0.toByte()
    private const val SOS = 0xDA.toByte()
    private const val EOI = 0xD9.toByte()

    /** [width]x[height] の JPEG。中身は見ないので画素データはダミーで埋める。 */
    fun jpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val header =
            byteArrayOf(
                MARKER,
                SOI,
                // SOF0: 長さ 17 = 8 + 3 成分 × 3 バイト、精度 8bit、縦、横、成分数 3
                MARKER,
                SOF0,
                0x00,
                0x11,
                0x08,
                (height shr 8).toByte(),
                height.toByte(),
                (width shr 8).toByte(),
                width.toByte(),
                0x03,
                0x01,
                0x22,
                0x00,
                0x02,
                0x11,
                0x01,
                0x03,
                0x11,
                0x01,
                // SOS 以降は画素データ。寸法に比例した長さにして「縮めば軽くなる」を再現する。
                MARKER,
                SOS,
                0x00,
                0x08,
                0x01,
                0x01,
                0x00,
                0x00,
                0x3F,
                0x00,
            )
        val scan = ByteArray(width * height / 500)
        return header + scan + byteArrayOf(MARKER, EOI)
    }

    /** JPEG の長辺（px）。SOF0 セグメントを実際に読む。 */
    fun longEdge(jpeg: ByteArray): Int = size(jpeg).let { (width, height) -> maxOf(width, height) }

    /** `PhotoCapture.downscale` と同じ契約。**収まっていれば元をそのまま返す**。 */
    val downscaler =
        JpegDownscaler { source, maxEdge ->
            val (width, height) = size(source)
            val longEdge = maxOf(width, height)
            if (longEdge <= maxEdge) {
                source
            } else {
                val scale = maxEdge.toDouble() / longEdge
                jpeg(
                    width = (width * scale).toInt().coerceAtLeast(1),
                    height = (height * scale).toInt().coerceAtLeast(1),
                )
            }
        }

    /** SOI の次のセグメントが SOF0 である前提（[jpeg] が組む形）。 */
    private fun size(jpeg: ByteArray): Pair<Int, Int> {
        check(jpeg.size > 11 && jpeg[0] == MARKER && jpeg[1] == SOI && jpeg[2] == MARKER && jpeg[3] == SOF0) {
            "JPEG（SOI + SOF0）ではありません"
        }
        val height = word(jpeg, 7)
        val width = word(jpeg, 9)
        return width to height
    }

    private fun word(
        bytes: ByteArray,
        at: Int,
    ): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}
