package com.scraft.glassreport.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme

@Preview(name = "縦・全画像と左下ロゴ", widthDp = 320, heightDp = 427, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PortraitPhotoFramePreview() = PhotoFramePreview(landscape = false)

@Preview(name = "横・4:3画像と左下ロゴ", widthDp = 320, heightDp = 427, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LandscapePhotoFramePreview() = PhotoFramePreview(landscape = true)

@Composable
private fun PhotoFramePreview(landscape: Boolean) {
    ScraftGlassTheme {
        PhotoPreviewFrame(landscape) {
            Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                Text("左上", modifier = Modifier.align(Alignment.TopStart), color = Color.White)
                Text("右上", modifier = Modifier.align(Alignment.TopEnd), color = Color.White)
                Text("画像中央", modifier = Modifier.align(Alignment.Center), color = Color.White)
                Text("右下", modifier = Modifier.align(Alignment.BottomEnd), color = Color.White)
            }
        }
    }
}
