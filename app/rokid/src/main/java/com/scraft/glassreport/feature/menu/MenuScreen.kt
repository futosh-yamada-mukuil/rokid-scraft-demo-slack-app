package com.scraft.glassreport.feature.menu

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scraft.glassreport.core.input.glassControls
import com.scraft.glassreport.core.ui.GlassScreen
import com.scraft.glassreport.core.ui.glassAction
import com.scraft.glassreport.core.ui.glassActionStyle

/**
 * メニュー1項目。
 *
 * @param label 項目名。
 * @param note 補助表示（未実装機能の「準備中」など）。null なら出さない。
 * @param onSelect 決定時の動作。
 */
data class MenuEntry(
    val label: String,
    val note: String? = null,
    val enabled: Boolean = true,
    val onSelect: () -> Unit,
)

/**
 * タッチパッド駆動のメニュー。ホーム画面と（Phase 1 の）アクションメニューで共用する。
 *
 * - 前後スワイプ … 項目移動（末尾から先頭へ巡回）
 * - タップ … 決定
 * - ダブルタップ / BACK … [onBack]
 *
 * 選択中の項目は反転表示（地=primary / 字=onPrimary）にする。単色ディスプレイでは
 * 枠線の差より面の反転のほうが遠目に分かりやすい。
 *
 * @param hint 画面下に出す操作ヒント。null なら出さない。
 * @param initialSelected 初期の選択位置。既定は先頭（Preview で任意の行の反転表示を見るために開けてある）。
 */
@Composable
fun MenuScreen(
    title: String,
    entries: List<MenuEntry>,
    hint: String? = null,
    initialSelected: Int = 0,
    onBack: () -> Unit,
) {
    var selected by remember { mutableIntStateOf(initialSelected) }
    val rowRequesters = remember(entries.size) { List(entries.size) { BringIntoViewRequester() } }
    LaunchedEffect(selected, rowRequesters) { rowRequesters.getOrNull(selected)?.bringIntoView() }

    BackHandler { onBack() }

    val controls =
        glassControls(
            onUp = { selected = nextEnabled(entries, selected, -1) },
            onDown = { selected = nextEnabled(entries, selected, 1) },
            onSelect = {
                entries
                    .getOrNull(selected)
                    ?.takeIf { it.enabled }
                    ?.onSelect
                    ?.invoke()
            },
            onDoubleSelect = onBack,
        )

    GlassScreen(modifier = controls) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            entries.forEachIndexed { i, entry ->
                Box(Modifier.fillMaxWidth().bringIntoViewRequester(rowRequesters[i])) {
                    MenuItem(
                        entry = entry,
                        selected = i == selected,
                        onClick = {
                            if (entry.enabled) {
                                selected = i
                                entry.onSelect()
                            }
                        },
                    )
                }
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun nextEnabled(
    entries: List<MenuEntry>,
    current: Int,
    step: Int,
): Int {
    if (entries.isEmpty()) return current
    for (offset in 1..entries.size) {
        val index = (current + offset * step + entries.size * 2) % entries.size
        if (entries[index].enabled) return index
    }
    return current
}

@Composable
private fun MenuItem(
    entry: MenuEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val actionStyle = glassActionStyle(selected, entry.enabled)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                // 幅は画面に追従させる（480x640 / 240dpi = 320dp 幅。固定 300dp では余白に収まらない）。
                .fillMaxWidth()
                .glassAction(actionStyle, onClick)
                .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyLarge,
            color = actionStyle.contentColor,
            fontWeight = actionStyle.fontWeight,
            textAlign = TextAlign.Center,
        )
        if (entry.note != null) {
            Text(
                text = entry.note,
                style = MaterialTheme.typography.bodyMedium,
                color = actionStyle.contentColor,
                fontWeight = actionStyle.fontWeight,
                textAlign = TextAlign.Center,
            )
        }
    }
}
