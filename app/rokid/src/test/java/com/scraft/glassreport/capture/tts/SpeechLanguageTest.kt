package com.scraft.glassreport.capture.tts

import com.scraft.glassreport.net.gtts.TtsVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechLanguageTest {
    @Test
    fun settingsOfferOnlyJapaneseSimplifiedChineseEnglishAndKorean() {
        assertEquals(listOf(SpeechLanguage.JA, SpeechLanguage.ZH_CN, SpeechLanguage.EN, SpeechLanguage.KO), SpeechLanguage.selectable)
        SpeechLanguage.selectable.forEach { assertEquals(it, SpeechLanguage.forSettings(it.code)) }
        assertEquals(SpeechLanguage.EN, SpeechLanguage.forSettings("en-US"))
        assertEquals(SpeechLanguage.ZH_CN, SpeechLanguage.forSettings("zh-Hans"))
    }

    @Test
    fun retiredSettingsFallBackWithoutChangingLegacyLanguageDecoding() {
        (SpeechLanguage.entries - SpeechLanguage.selectable.toSet()).forEach {
            assertEquals(SpeechLanguage.JA, SpeechLanguage.forSettings(it.code))
            assertEquals(it, SpeechLanguage.fromCodeOrNull(it.code))
        }
        assertEquals(SpeechLanguage.JA, SpeechLanguage.forSettings("unknown"))
        assertEquals(SpeechLanguage.JA, SpeechLanguage.forSettings("zh-TW"))
    }

    @Test
    fun catalogHasUniqueRoundTrippableCodesAndMatchingVoices() {
        assertEquals(20, SpeechLanguage.entries.size)
        assertEquals(
            20,
            SpeechLanguage.entries
                .map { it.code }
                .distinct()
                .size,
        )
        SpeechLanguage.entries.forEach { language ->
            assertEquals(language, SpeechLanguage.fromCodeOrNull(language.code))
            assertEquals(language, SpeechLanguage.fromCodeOrNull(language.ttsLocale))
            assertEquals(language.ttsLocale, TtsVoice.valueOf(language.name).languageCode)
            assertTrue(language.label.isNotBlank())
            assertTrue(language.translationName.isNotBlank())
        }
    }

    @Test
    fun regionalCodesAreNormalizedWithoutLosingChineseScript() {
        assertEquals(SpeechLanguage.EN, SpeechLanguage.fromCodeOrNull(" EN_us "))
        assertEquals(SpeechLanguage.NL, SpeechLanguage.fromCodeOrNull("nl-NL"))
        assertEquals(SpeechLanguage.ZH_TW, SpeechLanguage.fromCodeOrNull("zh-Hant-TW"))
        assertEquals(SpeechLanguage.ZH_TW, SpeechLanguage.fromCodeOrNull("zh-HK"))
        assertEquals(SpeechLanguage.ZH_CN, SpeechLanguage.fromCodeOrNull("zh-Hans-CN"))
        assertEquals(SpeechLanguage.ZH_CN, SpeechLanguage.fromCodeOrNull("zh"))
    }

    @Test
    fun unknownCodeCanBeRejectedWhileLegacyLookupDefaultsToJapanese() {
        listOf("", "unknown", "xx-XX").forEach {
            assertNull(SpeechLanguage.fromCodeOrNull(it))
            assertEquals(SpeechLanguage.JA, SpeechLanguage.fromCode(it))
        }
    }

    @Test
    fun refreshedStandardVoiceNamesAreUsed() {
        assertEquals("nl-NL-Standard-F", TtsVoice.NL.voiceName)
        assertEquals("es-ES-Standard-E", TtsVoice.ES.voiceName)
        assertEquals("uk-UA-Standard-B", TtsVoice.UK.voiceName)
    }
}
