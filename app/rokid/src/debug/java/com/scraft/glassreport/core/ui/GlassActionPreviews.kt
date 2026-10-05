package com.scraft.glassreport.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme

@Preview(name = "共通操作・選択/未選択/無効", widthDp = 320, heightDp = 427, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GlassActionStatesPreview() {
    ScraftGlassTheme {
        GlassScreen {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple("選択中・写真送信", true, true),
                    Triple("未選択・設定", false, true),
                    Triple("無効・動画送信（準備中）", true, false),
                ).forEach { (label, selected, enabled) ->
                    val style = glassActionStyle(selected, enabled)
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth().glassAction(style) {}.padding(horizontal = 8.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = style.contentColor,
                        fontWeight = style.fontWeight,
                    )
                }
            }
        }
    }
}
