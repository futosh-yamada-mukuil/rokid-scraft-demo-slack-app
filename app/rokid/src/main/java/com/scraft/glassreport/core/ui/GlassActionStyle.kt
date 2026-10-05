package com.scraft.glassreport.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared appearance for glass actions. Pass the actual pad selection, not a saved setting value.
 * Apply [glassAction] before existing padding, and use [contentColor]/[fontWeight] on every label.
 * Gesture-only surfaces and informational highlights are not action buttons.
 */
internal data class GlassActionStyle(
    val selected: Boolean,
    val enabled: Boolean,
    val background: Color,
    val contentColor: Color,
    val borderColor: Color,
    val fontWeight: FontWeight,
)

@Composable
internal fun glassActionStyle(
    selected: Boolean,
    enabled: Boolean = true,
): GlassActionStyle = resolveGlassActionStyle(MaterialTheme.colorScheme, selected, enabled)

internal fun resolveGlassActionStyle(
    scheme: ColorScheme,
    selected: Boolean,
    enabled: Boolean,
): GlassActionStyle {
    val active = selected && enabled
    return GlassActionStyle(
        selected = active,
        enabled = enabled,
        background = if (active) scheme.primary else Color.Transparent,
        contentColor =
            if (!enabled) {
                scheme.secondary.copy(alpha = 0.5f)
            } else if (active) {
                scheme.onPrimary
            } else {
                scheme.primary
            },
        borderColor =
            if (!enabled) {
                scheme.secondary.copy(alpha = 0.5f)
            } else if (active) {
                scheme.primary
            } else {
                scheme.secondary
            },
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
    )
}

internal fun Modifier.glassAction(
    style: GlassActionStyle,
    onClick: () -> Unit,
): Modifier =
    clip(RoundedCornerShape(10.dp))
        .background(style.background)
        .border(2.dp, style.borderColor, RoundedCornerShape(10.dp))
        .semantics { selected = style.selected }
        .clickable(enabled = style.enabled, role = Role.Button, onClick = onClick)
