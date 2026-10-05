package com.scraft.glassreport.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassConfigLanguageTest {
    @Test
    fun storedLegacyDestinationIsIgnoredAndRemovedOnNextSave() {
        val prefs = org.mockito.Mockito.mock(android.content.SharedPreferences::class.java)
        val editor = org.mockito.Mockito.mock(android.content.SharedPreferences.Editor::class.java)
        val legacy =
            """{
                "v":1,"t":"other-team","c":"other-channel","u":"http://192.168.1.2/upload",
                "k":"legacy-token","l":"en","a":"offline"
            }"""
        org.mockito.Mockito
            .`when`(prefs.getString("config", ""))
            .thenReturn(legacy)
        org.mockito.Mockito
            .`when`(prefs.edit())
            .thenReturn(editor)
        org.mockito.Mockito
            .`when`(editor.putString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(editor)
        val store = GlassConfigStore(prefs)
        assertEquals(GlassConfig(language = "en", asrEngine = "offline"), store.load())
        store.merge(GlassConfig(language = "ko"))
        val jsonArgument = org.mockito.ArgumentCaptor.forClass(String::class.java)
        org.mockito.Mockito
            .verify(editor)
            .putString(org.mockito.ArgumentMatchers.eq("config"), jsonArgument.capture())
        val json = org.json.JSONObject(jsonArgument.value)
        listOf("t", "c", "u", "k").forEach { org.junit.Assert.assertFalse(json.has(it)) }
        assertEquals("ko", json.getString("l"))
        assertEquals("offline", json.getString("a"))
    }

    @Test
    fun legacyChannelAndMonitorValuesAreIgnored() {
        val migrated =
            GlassConfig
                .fromSavedJson(
                    """{"v":1,"t":"other-team","c":"other-channel","u":"http://","k":"legacy-token","l":"en","a":"offline"}""",
                ).getOrThrow()
        assertEquals(GlassConfig(language = "en", asrEngine = "offline"), migrated)
    }

    @Test
    fun oldPersistedOrQrLanguagesAreNormalizedOnRead() {
        listOf("nl", "fr-FR", "zh-TW", "unknown").forEach { code ->
            assertEquals("ja", GlassConfig.fromSavedJson("""{"v":1,"l":"$code"}""").getOrThrow().language)
        }
        assertEquals("zh-CN", GlassConfig.fromSavedJson("""{"v":1,"l":"zh-Hans"}""").getOrThrow().language)
        assertEquals("ko", GlassConfig.fromSavedJson("""{"v":1,"l":"ko-KR"}""").getOrThrow().language)
    }

    @Test
    fun absentQrLanguagePreservesCurrentChoiceAndMergeNormalizesRetiredSettings() {
        val withoutLanguage = GlassConfig.fromSavedJson("""{"v":1}""").getOrThrow()
        assertEquals("", withoutLanguage.language)
        assertEquals("en", GlassConfig(language = "en").merge(withoutLanguage).language)
        assertEquals("ja", GlassConfig(language = "nl").merge(withoutLanguage).language)
        assertEquals("ja", GlassConfig(language = "en").merge(GlassConfig(language = "zh-TW")).language)
    }
}
