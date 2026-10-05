package com.scraft.glassreport.capture.tts

import android.media.MediaDataSource

/**
 * メモリ上の音声バイト列を `MediaPlayer` に読ませるためのデータソース。
 *
 * `MediaDataSource` は API 23 から使えるので（本アプリの minSdk は 28）、
 * クラウド TTS が返した MP3 を**一時ファイルに書き出さずに**そのまま再生できる。
 * 書き込み先の確保・後片付け・権限がすべて不要になる。
 */
internal class ByteArrayMediaDataSource(
    private val data: ByteArray,
) : MediaDataSource() {
    /** [position] から最大 [size] バイトを [buffer] の [offset] へ。終端なら -1。 */
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (position < 0 || position >= data.size) return -1
        if (size <= 0) return 0
        val from = position.toInt()
        val length = minOf(size, data.size - from)
        data.copyInto(buffer, destinationOffset = offset, startIndex = from, endIndex = from + length)
        return length
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {
        // メモリ上のバイト列を参照するだけなので解放するものは無い。
    }
}
