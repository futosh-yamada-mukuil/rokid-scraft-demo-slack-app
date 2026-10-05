package com.scraft.glassreport.core.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassActionStyleTest {
    private val scheme = darkColorScheme(primary = Color.Green, onPrimary = Color.Black, secondary = Color.Gray)

    @Test
    fun selectedActionUsesOpaqueFillAndContrastingBoldText() {
        val style = resolveGlassActionStyle(scheme, selected = true, enabled = true)
        assertTrue(style.selected)
        assertTrue(style.enabled)
        assertEquals(scheme.primary, style.background)
        assertEquals(scheme.onPrimary, style.contentColor)
        assertEquals(scheme.primary, style.borderColor)
        assertEquals(FontWeight.Bold, style.fontWeight)
    }

    @Test
    fun unselectedActionKeepsReadableTextAndQuietOutline() {
        val style = resolveGlassActionStyle(scheme, selected = false, enabled = true)
        assertFalse(style.selected)
        assertEquals(Color.Transparent, style.background)
        assertEquals(scheme.primary, style.contentColor)
        assertEquals(scheme.secondary, style.borderColor)
        assertEquals(FontWeight.Normal, style.fontWeight)
    }

    @Test
    fun disabledActionNeverLooksSelectedEvenWhenSelectionIndexPointsToIt() {
        val style = resolveGlassActionStyle(scheme, selected = true, enabled = false)
        assertEquals(resolveGlassActionStyle(scheme, selected = false, enabled = false), style)
        assertFalse(style.selected)
        assertFalse(style.enabled)
        assertEquals(Color.Transparent, style.background)
        assertEquals(scheme.secondary.copy(alpha = 0.5f), style.contentColor)
        assertEquals(style.contentColor, style.borderColor)
        assertEquals(FontWeight.Normal, style.fontWeight)
    }
}
