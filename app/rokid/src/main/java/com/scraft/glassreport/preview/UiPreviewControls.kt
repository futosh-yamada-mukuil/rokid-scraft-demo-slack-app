package com.scraft.glassreport.preview

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp

/** グラスの可視領域の下に置くPC操作用パネル。実機ビルドでは表示しない。 */
@Composable
fun UiPreviewControls(
    onKey: (Int) -> Unit,
    onBack: () -> Unit,
    onReply: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                if (onReply != null) "AVD UIプレビュー · 外部送信なし" else "MUKUIL · Slack接続モード",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "↑↓＝スワイプ / 決定＝タップ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewButton("↑") { onKey(KeyEvent.KEYCODE_DPAD_UP) }
                PreviewButton("↓") { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
                PreviewButton("決定") { onKey(KeyEvent.KEYCODE_DPAD_CENTER) }
                PreviewButton("戻る", onBack)
            }
            if (onReply != null) PreviewButton("返信サンプルを受信", onReply)
        }
    }
}

@Composable
private fun PreviewButton(
    label: String,
    onClick: () -> Unit,
) {
    // 画面本体のDPADフォーカスを保ち、クリック後もグラス操作を受け取れるようにする。
    Button(onClick = onClick, modifier = Modifier.focusProperties { canFocus = false }) { Text(label) }
}
