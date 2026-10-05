package com.scraft.glassreport.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonLiteTest {
    @Test
    fun string_readsPlainValue() {
        val json = """{"user_code":"F7KQ2M9XZ","verification_uri":"https://microsoft.com/devicelogin"}"""

        assertEquals("F7KQ2M9XZ", JsonLite.string(json, "user_code"))
        assertEquals("https://microsoft.com/devicelogin", JsonLite.string(json, "verification_uri"))
    }

    @Test
    fun string_unescapesQuotesAndNewlines() {
        val json = """{"error_description":"AADSTS70016: \"pending\"\r\nTrace ID: abc"}"""

        assertEquals("AADSTS70016: \"pending\"\r\nTrace ID: abc", JsonLite.string(json, "error_description"))
    }

    @Test
    fun string_unescapesUnicode() {
        // JSON 側は 6 文字の \u3042（Kotlin 文字列としては \\u3042）。
        assertEquals("あ", JsonLite.string("{\"m\":\"\\u3042\"}", "m"))
    }

    @Test
    fun string_stopsAtValueEndEvenWithEscapedQuote() {
        val json = """{"a":"x\"y","b":"z"}"""

        assertEquals("x\"y", JsonLite.string(json, "a"))
        assertEquals("z", JsonLite.string(json, "b"))
    }

    @Test
    fun string_returnsNullForMissingOrNonStringKey() {
        assertNull(JsonLite.string("""{"interval":5}""", "interval"))
        assertNull(JsonLite.string("""{"a":"1"}""", "b"))
    }

    @Test
    fun number_readsIntegerValue() {
        val json = """{"expires_in":900,"interval":5}"""

        assertEquals(900L, JsonLite.number(json, "expires_in"))
        assertEquals(5L, JsonLite.number(json, "interval"))
        assertNull(JsonLite.number(json, "missing"))
    }

    @Test
    fun escape_makesStringSafeForJsonLiteral() {
        // 引用符とバックスラッシュはエスケープし、日本語やスラッシュはそのまま通す。
        assertEquals("<div>\\\"配管\\\"</div>", JsonLite.escape("<div>\"配管\"</div>"))
        assertEquals("a\\\\b\\nc", JsonLite.escape("a\\b\nc"))
        // 制御文字は \\u00xx にする。
        assertEquals("\\u0001", JsonLite.escape("\u0001"))
    }

    @Test
    fun escape_thenString_roundTrips() {
        val original = "改行\nと \"引用符\" と \\ バックスラッシュ"

        val json = """{"content":"${JsonLite.escape(original)}"}"""

        assertEquals(original, JsonLite.string(json, "content"))
    }
}
