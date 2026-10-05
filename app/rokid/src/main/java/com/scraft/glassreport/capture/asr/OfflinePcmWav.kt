package com.scraft.glassreport.capture.asr

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Strict RIFF reader: accepts only the bounded 16 kHz mono PCM16 recording contract. */
internal object OfflinePcmWav {
    fun samples(wav: ByteArray): FloatArray {
        require(wav.size in 44..(16000 * 2 * 6 + 4096)) { "録音データのサイズが不正です" }
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        fun tag(offset: Int) = String(wav, offset, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE") { "WAV形式ではありません" }
        require(buffer.getInt(4).toLong() + 8 == wav.size.toLong()) { "WAVが途中で切れています" }
        var offset = 12
        var formatValid = false
        var pcmStart = -1
        var pcmLength = 0
        while (offset + 8 <= wav.size) {
            val length = buffer.getInt(offset + 4)
            require(length >= 0 && offset.toLong() + 8 + length <= wav.size) { "WAVチャンクが不正です" }
            when (tag(offset)) {
                "fmt " -> {
                    require(length >= 16) { "WAV形式情報が不正です" }
                    require(
                        buffer.getShort(offset + 8).toInt() == 1 &&
                            buffer.getShort(offset + 10).toInt() == 1 &&
                            buffer.getInt(offset + 12) == 16000 &&
                            buffer.getInt(offset + 16) == 32000 &&
                            buffer.getShort(offset + 20).toInt() == 2 &&
                            buffer.getShort(offset + 22).toInt() == 16,
                    ) { "16kHz・モノラル・PCM16の録音が必要です" }
                    formatValid = true
                }
                "data" -> {
                    require(pcmStart < 0) { "WAV音声チャンクが重複しています" }
                    pcmStart = offset + 8
                    pcmLength = length
                }
            }
            offset += 8 + length + (length and 1)
        }
        require(formatValid && pcmStart >= 0 && pcmLength % 2 == 0 && pcmLength <= 16000 * 2 * 6) {
            "録音データの形式または長さが不正です"
        }
        return FloatArray(pcmLength / 2) { buffer.getShort(pcmStart + it * 2) / 32768.0f }
    }
}
