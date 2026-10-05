package com.scraft.glassreport.net

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAppIdentityTest {
    @Test
    fun sha1Hex_isUppercaseWithoutColons() {
        // RFC 3174 の既知値。Google コンソールが要求する「コロン無し・大文字」の形。
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", sha1Hex("abc".toByteArray()))
        assertEquals(40, sha1Hex("abc".toByteArray()).length)
    }

    @Test
    fun sha1Hex_padsSingleDigitBytes() {
        assertEquals("DA39A3EE5E6B4B0D3255BFEF95601890AFD80709", sha1Hex(byteArrayOf()))
    }
}
