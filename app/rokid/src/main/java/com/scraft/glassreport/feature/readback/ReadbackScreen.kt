package com.scraft.glassreport.feature.readback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scraft.glassreport.core.input.glassControls
import com.scraft.glassreport.core.ui.GlassScreen
import com.scraft.glassreport.core.ui.glassAction
import com.scraft.glassreport.core.ui.glassActionStyle
import com.scraft.glassreport.di.rememberContainer

/** 操作メニューの並び。前後スワイプで移動、タップで決定（パッドで区別できる 3 操作に収める）。 */
private val ACTION_LABELS = listOf("日本語で読み上げ", "英訳して読み上げ", "別の文にする")

/**
 * Phase 4（読み上げ・翻訳）の確認用画面。
 *
 * Feed（Phase 3）がまだ無く読み上げ対象の「返信」が存在しないため、固定文を相手に
 * 「読み上げ」「英訳して読み上げ」だけを実機で確かめられるようにした独立画面。
 * Feed が入ったら [ReadbackViewModel] に投稿本文を渡すだけでこの導線は不要になる。
 */
@Composable
fun ReadbackScreen(onBack: () -> Unit) {
    val container = rememberContainer()
    val viewModel: ReadbackViewModel =
        viewModel(
            factory =
                viewModelFactory {
                    initializer { ReadbackViewModel(container.newSpeaker(), container.translator) }
                },
        )
    val ui by viewModel.ui.collectAsState()

    var selected by remember { mutableIntStateOf(0) }
    val run = { index: Int ->
        when (index) {
            0 -> viewModel.speakJapanese()
            1 -> viewModel.speakEnglish()
            else -> viewModel.nextSample()
        }
    }
    BackHandler { onBack() }

    val controls =
        glassControls(
            onUp = { selected = (selected - 1 + ACTION_LABELS.size) % ACTION_LABELS.size },
            onDown = { selected = (selected + 1) % ACTION_LABELS.size },
            onSelect = { run(selected) },
            onDoubleSelect = onBack,
        )

    ReadbackScreenContent(
        ui = ui,
        selected = selected,
        onSelect = { index ->
            selected = index
            run(index)
        },
        modifier = controls,
    )
}

/** 実機依存を持たない見た目。debug の Preview と本番で同じものを使う。 */
@Composable
internal fun ReadbackScreenContent(
    ui: ReadbackUiState,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val actionRequesters = remember { List(ACTION_LABELS.size) { BringIntoViewRequester() } }
    LaunchedEffect(selected) { actionRequesters.getOrNull(selected)?.bringIntoView() }

    GlassScreen(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "読み上げ確認",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.secondary,
            )
            Text(
                text = ui.source,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.primary,
                textAlign = TextAlign.Center,
            )
            if (ui.translated.isNotBlank()) {
                Text(
                    text = ui.translated,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }

            ACTION_LABELS.forEachIndexed { index, label ->
                Box(Modifier.fillMaxWidth().bringIntoViewRequester(actionRequesters[index])) {
                    ActionItem(
                        label = label,
                        selected = index == selected,
                        onClick = { onSelect(index) },
                    )
                }
            }

            Text(
                text = ui.status,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (ui.detail.isNotBlank()) {
                Text(
                    text = ui.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ActionItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val actionStyle = glassActionStyle(selected)
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = actionStyle.contentColor,
        fontWeight = actionStyle.fontWeight,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .glassAction(actionStyle, onClick)
                .padding(vertical = 10.dp),
    )
}
