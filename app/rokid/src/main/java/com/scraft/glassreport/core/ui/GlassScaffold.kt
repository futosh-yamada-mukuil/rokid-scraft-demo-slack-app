package com.scraft.glassreport.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp

/** 実視界は高さの約2/3。640px中約426pxなので、下端に約16pxの余裕を残す。 */
const val GLASS_VISIBLE_FRACTION = 0.64f

/** 背景やカメラプレビューは全面に描き、情報だけを可視領域へ収める土台。 */
@Composable
fun GlassVisibleArea(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(maxHeight * GLASS_VISIBLE_FRACTION)
                    .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp)
                    .clipToBounds(),
            contentAlignment = contentAlignment,
        ) { content() }
    }
}

/** グラス共通の黒地土台。 */
@Composable
fun GlassScreen(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    GlassVisibleArea(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = contentAlignment,
        content = content,
    )
}
