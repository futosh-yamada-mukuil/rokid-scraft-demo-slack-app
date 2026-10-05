package com.scraft.glassreport.feature.capture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.scraft.glassreport.BuildConfig
import com.scraft.glassreport.capture.camera.PhotoWatermarkGeometry
import com.scraft.glassreport.core.ui.GlassVisibleArea
import com.scraft.glassreport.core.ui.VendorLogo

/** The viewport and logo use the final photo's coordinates, never the HUD's visible area. */
@Composable
internal fun PhotoPreviewFrame(
    landscape: Boolean,
    showLogo: Boolean = true,
    content: @Composable () -> Unit,
) {
    val frame: @Composable () -> Unit = {
        BoxWithConstraints(
            Modifier.aspectRatio(if (landscape) 4f / 3f else 3f / 4f).clipToBounds(),
        ) {
            content()
            if (showLogo) {
                val bounds = PhotoWatermarkGeometry.bounds(constraints.maxWidth, constraints.maxHeight)
                val density = LocalDensity.current
                VendorLogo(
                    modifier =
                        Modifier
                            .offset(with(density) { bounds.left.toDp() }, with(density) { bounds.top.toDp() })
                            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }),
                    tint = Color(PhotoWatermarkGeometry.TINT_ARGB),
                )
            }
        }
    }
    if (landscape) {
        GlassVisibleArea(content = frame)
    } else {
        // Portrait photos may extend below the optical 2/3 area: show the whole image and its true corner.
        // AVD操作パネルの上に写真全体を収め、写真下端のロゴが隠れないようにする。
        val bottomInset = if (BuildConfig.AVD_CONTROLS) 104.dp else 0.dp
        Box(Modifier.fillMaxSize().padding(bottom = bottomInset), contentAlignment = Alignment.Center) { frame() }
    }
}
