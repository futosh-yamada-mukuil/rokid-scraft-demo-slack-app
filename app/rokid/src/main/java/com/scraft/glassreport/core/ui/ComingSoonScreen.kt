package com.scraft.glassreport.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scraft.glassreport.R
import com.scraft.glassreport.core.input.glassControls

/**
 * 未実装機能の置き場。ホームから入って**ダブルタップ／BACK で戻る**だけの画面。
 *
 * Feed（記録を見る）や設定が実装できたら、この画面を本物に差し替える。
 */
@Composable
fun ComingSoonScreen(
    title: String,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    // 決定（シングルタップ）に用は無いが、ダブルタップを拾うため onDoubleSelect を渡す。
    val controls = glassControls(onDoubleSelect = onBack)

    GlassScreen(modifier = controls) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.preparing),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.hint_double_tap_back),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
