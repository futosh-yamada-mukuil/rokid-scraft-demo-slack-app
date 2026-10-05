package com.scraft.glassreport.data.prefs

import android.content.SharedPreferences
import com.scraft.glassreport.domain.model.PHOTO_EDGE_UNLIMITED
import com.scraft.glassreport.domain.model.PhotoQuality
import com.scraft.glassreport.domain.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

class CaptureSettingsTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        prefs = Mockito.mock(SharedPreferences::class.java)
        editor = Mockito.mock(SharedPreferences.Editor::class.java)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        Mockito.`when`(editor.putFloat(anyString(), anyFloat())).thenReturn(editor)
        Mockito.`when`(editor.remove(anyString())).thenReturn(editor)
    }

    @Test
    fun defaults_arePhoto1920AndVideoFhd() {
        val settings = CaptureSettings(prefs)

        assertEquals(PhotoQuality.EDGE_1920, settings.state.value.photo)
        assertEquals(VideoQuality.FHD, settings.state.value.video)
        assertEquals(1_920, settings.state.value.photo.maxEdge)
        assertEquals(1_920, settings.state.value.video.width)
        assertEquals(1_080, settings.state.value.video.height)
    }

    @Test
    fun savedValues_areRestored() {
        Mockito.`when`(prefs.getString("photo_quality", null)).thenReturn("ORIGINAL")
        Mockito.`when`(prefs.getString("video_quality", null)).thenReturn("SD")

        val settings = CaptureSettings(prefs)

        assertEquals(PhotoQuality.ORIGINAL, settings.state.value.photo)
        assertEquals(VideoQuality.SD, settings.state.value.video)
    }

    @Test
    fun unknownStoredValue_fallsBackToDefault() {
        Mockito.`when`(prefs.getString("photo_quality", null)).thenReturn("4K_ULTRA")

        assertEquals(PhotoQuality.DEFAULT, CaptureSettings(prefs).state.value.photo)
    }

    @Test
    fun cyclePhoto_walksThroughAllChoicesAndPersists() {
        val settings = CaptureSettings(prefs)

        settings.cyclePhoto()
        assertEquals(PhotoQuality.ORIGINAL, settings.state.value.photo)
        Mockito.verify(editor).putString("photo_quality", "ORIGINAL")

        settings.cyclePhoto()
        assertEquals(PhotoQuality.EDGE_1280, settings.state.value.photo)

        settings.cyclePhoto()
        assertEquals(PhotoQuality.EDGE_1920, settings.state.value.photo)
    }

    @Test
    fun cycleVideo_walksThroughAllChoices() {
        val settings = CaptureSettings(prefs)

        settings.cycleVideo()
        assertEquals(VideoQuality.SD, settings.state.value.video)

        settings.cycleVideo()
        assertEquals(VideoQuality.HD, settings.state.value.video)

        settings.cycleVideo()
        assertEquals(VideoQuality.FHD, settings.state.value.video)
    }

    @Test
    fun cyclingOneAxisLeavesTheOtherAlone() {
        val settings = CaptureSettings(prefs)

        settings.cyclePhoto()

        assertEquals(VideoQuality.FHD, settings.state.value.video)
    }

    @Test
    fun original_meansNoDownscale() {
        assertEquals(PHOTO_EDGE_UNLIMITED, PhotoQuality.ORIGINAL.maxEdge)
    }

    // ── 既定の送信先 ────────────────────────────────────────────────────────

    @Test
    fun legacyMonitorSettingIsIgnoredAndRemoved() {
        Mockito.`when`(prefs.getString("default_sink", null)).thenReturn("モニタ")
        assertEquals("Teams", CaptureSettings(prefs).defaultSink.value)
        Mockito.verify(editor).remove("default_sink")
    }

    @Test
    fun ttsSpeed_defaultsToSlightlyFasterAndRestoresSavedValue() {
        assertEquals(1.2f, CaptureSettings(prefs).ttsSpeed.value, 0.001f)

        Mockito.`when`(prefs.contains("tts_speed")).thenReturn(true)
        Mockito.`when`(prefs.getFloat("tts_speed", 1.2f)).thenReturn(0.9f)
        assertEquals(0.9f, CaptureSettings(prefs).ttsSpeed.value, 0.001f)
    }

    @Test
    fun ttsSpeed_adjustsByPointOneAndClampsAtLimits() {
        val settings = CaptureSettings(prefs)

        settings.adjustTtsSpeed(0.1f)
        assertEquals(1.3f, settings.ttsSpeed.value, 0.001f)
        Mockito.verify(editor).putFloat("tts_speed", 1.3f)

        repeat(10) { settings.adjustTtsSpeed(0.1f) }
        assertEquals(1.5f, settings.ttsSpeed.value, 0.001f)
        repeat(10) { settings.adjustTtsSpeed(-0.1f) }
        assertEquals(0.75f, settings.ttsSpeed.value, 0.001f)
    }

    private companion object {
        /** `AppContainer.sinks` のラベル（Teams が設定済みのときの並び）。 */
        val SINKS = listOf("モニタ", "Teams")
    }
}
