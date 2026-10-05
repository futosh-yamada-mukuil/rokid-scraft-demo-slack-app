package com.scraft.glassreport.capture.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEngineTest {
    @Test
    fun unselectedEngineUsesAvailableDefault() {
        assertEquals(SpeechEngine.OFFLINE, SpeechEngine.fromCode("", default = SpeechEngine.OFFLINE))
        assertEquals(SpeechEngine.GOOGLE, SpeechEngine.fromCode("", default = SpeechEngine.GOOGLE))
    }

    @Test
    fun explicitChoiceIsPreservedRegardlessOfDefault() {
        assertEquals(SpeechEngine.GOOGLE, SpeechEngine.fromCode("google", default = SpeechEngine.OFFLINE))
        assertEquals(SpeechEngine.OFFLINE, SpeechEngine.fromCode("offline", default = SpeechEngine.GOOGLE))
    }
}
