package com.scraft.glassreport.feature.home

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.scraft.glassreport.core.input.glassControls

/** アプリの最前面だけを黒く覆う。通信・通知音・TTSは背後で動き続ける。 */
@Composable
fun BlackoutOverlay(onRestore: () -> Unit) {
    val restoreGate = remember { BlackoutRestoreGate(SystemClock.uptimeMillis()) }
    var suppressOpeningKeyUp by remember { mutableStateOf(true) }
    val restoreAfterTap = { if (restoreGate.allowsTap(SystemClock.uptimeMillis())) onRestore() }
    BackHandler(onBack = onRestore)
    Box(
        modifier =
            glassControls(onSelect = restoreAfterTap)
                .onPreviewKeyEvent { event ->
                    if (suppressOpeningKeyUp &&
                        event.type == KeyEventType.KeyUp &&
                        event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                    ) {
                        suppressOpeningKeyUp = false
                        true
                    } else {
                        false
                    }
                }.fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = restoreAfterTap),
    )
}
