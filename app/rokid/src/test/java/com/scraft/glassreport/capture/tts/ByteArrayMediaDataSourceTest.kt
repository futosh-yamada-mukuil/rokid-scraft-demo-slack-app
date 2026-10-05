package com.scraft.glassreport.capture.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 一時ファイルを作らずに MP3 を再生するための読み出し規約（MediaPlayer が呼ぶ）を確かめる。 */
class ByteArrayMediaDataSourceTest {
    private val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

    @Test
    fun size_isByteCount() {
        assertEquals(10L, ByteArrayMediaDataSource(data).size)
        assertEquals(0L, ByteArrayMediaDataSource(byteArrayOf()).size)
    }

    @Test
    fun readAt_copiesRequestedRangeIntoBufferOffset() {
        val buffer = ByteArray(6) { -1 }

        val read = ByteArrayMediaDataSource(data).readAt(position = 4, buffer = buffer, offset = 2, size = 3)

        assertEquals(3, read)
        assertArrayEquals(byteArrayOf(-1, -1, 4, 5, 6, -1), buffer)
    }

    @Test
    fun readAt_clampsToRemainingBytes() {
        val buffer = ByteArray(8)

        val read = ByteArrayMediaDataSource(data).readAt(position = 7, buffer = buffer, offset = 0, size = 8)

        assertEquals(3, read)
        assertArrayEquals(byteArrayOf(7, 8, 9, 0, 0, 0, 0, 0), buffer)
    }

    @Test
    fun readAt_returnsMinusOneAtEndOfStream() {
        val source = ByteArrayMediaDataSource(data)

        assertEquals(-1, source.readAt(position = 10, buffer = ByteArray(4), offset = 0, size = 4))
        assertEquals(-1, source.readAt(position = 99, buffer = ByteArray(4), offset = 0, size = 4))
        assertEquals(-1, ByteArrayMediaDataSource(byteArrayOf()).readAt(0, ByteArray(4), 0, 4))
    }

    @Test
    fun readAt_zeroSizeReadsNothing() {
        assertEquals(0, ByteArrayMediaDataSource(data).readAt(position = 0, buffer = ByteArray(4), offset = 0, size = 0))
    }
}
