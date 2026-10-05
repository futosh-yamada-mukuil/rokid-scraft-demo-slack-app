package com.scraft.glassreport.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scraft.glassreport.capture.asr.SpeechEngine
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.core.input.glassControls
import com.scraft.glassreport.core.ui.GlassScreen
import com.scraft.glassreport.core.ui.glassAction
import com.scraft.glassreport.core.ui.glassActionStyle
import com.scraft.glassreport.data.prefs.GlassConfig
import com.scraft.glassreport.di.rememberContainer
import com.scraft.glassreport.domain.model.CaptureQualities

/**
 * 画質と既定の送信先の設定画面。
 *
 * 前後スワイプで項目を選び、タップで変更または選択パネルを開く。
 * 言語だけは候補を選んで確定するまで保存しない。
 * 最後の項目「戻る」だけはタップで画面を閉じる。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = rememberContainer()
    val settings = container.captureSettings
    val qualities by settings.state.collectAsState()
    val replyFontSize by settings.replyFontSize.collectAsState()
    val speechRate by settings.ttsSpeed.collectAsState()
    var language by remember {
        mutableStateOf(
            container.glassConfig
                .load()
                .language
                .ifBlank { "ja" },
        )
    }
    var speechEngine by remember { mutableStateOf(container.speechEngine) }

    SettingsScreenContent(
        qualities = qualities,
        replyFontSize = replyFontSize,
        onAdjustReplyFontSize = settings::adjustReplyFontSize,
        speechRate = speechRate,
        onAdjustSpeechRate = settings::adjustTtsSpeed,
        onCyclePhoto = settings::cyclePhoto,
        onCycleVideo = settings::cycleVideo,
        language = language,
        speechEngine = speechEngine,
        onCycleSpeechEngine = {
            speechEngine = if (speechEngine == SpeechEngine.GOOGLE) SpeechEngine.OFFLINE else SpeechEngine.GOOGLE
            container.glassConfig.merge(GlassConfig(asrEngine = speechEngine.code))
        },
        onLanguageSelected = { chosen ->
            language = chosen
            container.glassConfig.merge(GlassConfig(language = language))
        },
        onBack = onBack,
    )
}

/** 画面の見た目と操作。実機依存が無いので Preview からも呼べる。 */
@Composable
internal fun SettingsScreenContent(
    qualities: CaptureQualities,
    onCyclePhoto: () -> Unit,
    onCycleVideo: () -> Unit,
    onBack: () -> Unit,
    replyFontSize: Int = 20,
    onAdjustReplyFontSize: (Int) -> Unit = {},
    speechRate: Float = 1.2f,
    onAdjustSpeechRate: (Float) -> Unit = {},
    language: String = "ja",
    onLanguageSelected: (String) -> Unit = {},
    speechEngine: SpeechEngine = SpeechEngine.GOOGLE,
    onCycleSpeechEngine: () -> Unit = {},
) {
    var selected by remember { mutableIntStateOf(0) }
    var adjusting by remember { mutableStateOf<Adjustment?>(null) }
    var languageChoice by remember { mutableStateOf<SpeechLanguage?>(null) }

    fun closePanel() {
        if (languageChoice != null) {
            languageChoice = null
        } else if (adjusting != null) {
            adjusting = null
        } else {
            onBack()
        }
    }

    fun moveLanguage(delta: Int) {
        val current = languageChoice ?: return
        val choices = SpeechLanguage.selectable
        languageChoice = choices[(choices.indexOf(current).coerceAtLeast(0) + delta + choices.size) % choices.size]
    }

    fun confirmLanguage() {
        languageChoice?.let { onLanguageSelected(it.code) }
        languageChoice = null
    }
    val rows =
        listOf(
            SettingsRow("返信の文字サイズ", "${replyFontSize}sp", "タップで調整", { adjusting = Adjustment.FONT_SIZE }),
            SettingsRow("読み上げ速度", "%.2f×".format(speechRate), "タップで調整", { adjusting = Adjustment.SPEECH_RATE }),
            SettingsRow("音声認識", speechEngine.label, "タップでGoogle / Offline切替", onCycleSpeechEngine),
            SettingsRow("表示・返信言語", SpeechLanguage.forSettings(language).label, "タップで言語を選択", {
                languageChoice = SpeechLanguage.forSettings(language)
            }),
            SettingsRow("戻る", "", "", onBack),
        )
    val scrollState = rememberScrollState()
    val rowRequesters = remember(rows.size) { List(rows.size) { BringIntoViewRequester() } }
    LaunchedEffect(selected, adjusting, languageChoice, rowRequesters) {
        if (adjusting == null && languageChoice == null) rowRequesters.getOrNull(selected)?.bringIntoView()
    }
    val controls =
        glassControls(
            inputKey = if (languageChoice != null) "language" else adjusting,
            onUp = {
                if (languageChoice != null) {
                    moveLanguage(-1)
                    return@glassControls
                }
                when (adjusting) {
                    Adjustment.FONT_SIZE -> onAdjustReplyFontSize(2)
                    Adjustment.SPEECH_RATE -> onAdjustSpeechRate(0.1f)
                    null -> selected = (selected - 1 + rows.size) % rows.size
                }
            },
            onDown = {
                if (languageChoice != null) {
                    moveLanguage(1)
                    return@glassControls
                }
                when (adjusting) {
                    Adjustment.FONT_SIZE -> onAdjustReplyFontSize(-2)
                    Adjustment.SPEECH_RATE -> onAdjustSpeechRate(-0.1f)
                    null -> selected = (selected + 1) % rows.size
                }
            },
            onSelect = {
                if (languageChoice != null) {
                    confirmLanguage()
                } else if (adjusting == null) {
                    rows.getOrNull(selected)?.onSelect?.invoke()
                }
            },
            onDoubleSelect = ::closePanel,
        )
    BackHandler(onBack = ::closePanel)

    GlassScreen(modifier = controls, contentAlignment = Alignment.TopCenter) {
        languageChoice?.let { choice ->
            LanguageSelectionPanel(choice, ::confirmLanguage, { languageChoice = null })
            return@GlassScreen
        }
        if (adjusting != null) {
            AdjustmentPanel(
                kind = adjusting ?: Adjustment.FONT_SIZE,
                fontSize = replyFontSize,
                speechRate = speechRate,
                onIncrease = {
                    if (adjusting == Adjustment.FONT_SIZE) {
                        onAdjustReplyFontSize(2)
                    } else {
                        onAdjustSpeechRate(0.1f)
                    }
                },
                onDecrease = {
                    if (adjusting == Adjustment.FONT_SIZE) {
                        onAdjustReplyFontSize(-2)
                    } else {
                        onAdjustSpeechRate(-0.1f)
                    }
                },
                onClose = { adjusting = null },
            )
            return@GlassScreen
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "設定",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            rows.forEachIndexed { index, row ->
                Box(Modifier.fillMaxWidth().bringIntoViewRequester(rowRequesters[index])) {
                    SettingsItem(
                        row = row,
                        selected = index == selected,
                        onClick = {
                            selected = index
                            row.onSelect()
                        },
                    )
                }
            }
            Text(
                text = "スワイプで選択・タップで切替",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private enum class Adjustment { FONT_SIZE, SPEECH_RATE }

/** 一つの候補にフォーカスし、確定まで設定を書き換えないモーダル。 */
@Composable
internal fun LanguageSelectionPanel(
    choice: SpeechLanguage,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val selectedStyle = glassActionStyle(selected = true)
    val cancelStyle = glassActionStyle(selected = false)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("表示・返信言語", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "${SpeechLanguage.selectable.indexOf(choice).coerceAtLeast(0) + 1} / ${SpeechLanguage.selectable.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().glassAction(selectedStyle, onConfirm).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                choice.label,
                style = MaterialTheme.typography.titleMedium,
                color = selectedStyle.contentColor,
                fontWeight = selectedStyle.fontWeight,
            )
            Text(choice.code, style = MaterialTheme.typography.bodySmall, color = selectedStyle.contentColor)
        }
        Text("前後スワイプで選択・タップで確定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "ダブルタップ / BACK でキャンセル",
            modifier = Modifier.glassAction(cancelStyle, onCancel).padding(4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = cancelStyle.contentColor,
        )
    }
}

/** 設定1項目。[value] が空なら「戻る」のような動作だけの項目。 */
internal data class SettingsRow(
    val label: String,
    val value: String,
    val note: String,
    val onSelect: () -> Unit,
)

@Composable
private fun AdjustmentPanel(
    kind: Adjustment,
    fontSize: Int,
    speechRate: Float,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isFont = kind == Adjustment.FONT_SIZE
    // Swipes change the value directly; no button is selected by the pad in this panel.
    val actionStyle = glassActionStyle(selected = false)
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (isFont) "返信の文字サイズ" else "読み上げ速度",
            style = MaterialTheme.typography.titleLarge,
            color = scheme.primary,
        )
        Text(
            if (isFont) "${fontSize}sp" else "%.2f×".format(speechRate),
            style = MaterialTheme.typography.displayLarge,
            color = scheme.primary,
        )
        Text(
            if (isFont) "返信プレビュー" else "次の読み上げから反映します",
            style =
                if (isFont) {
                    MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            color = scheme.secondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "− 小さく/遅く",
                modifier = Modifier.glassAction(actionStyle, onDecrease).padding(6.dp),
                color = actionStyle.contentColor,
                fontWeight = actionStyle.fontWeight,
            )
            Text(
                "＋ 大きく/速く",
                modifier = Modifier.glassAction(actionStyle, onIncrease).padding(6.dp),
                color = actionStyle.contentColor,
                fontWeight = actionStyle.fontWeight,
            )
        }
        Text(
            "前スワイプ ＋ ・ 後ろスワイプ −",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            "ダブルタップ / BACK で戻る",
            style = MaterialTheme.typography.bodySmall,
            color = actionStyle.contentColor,
            fontWeight = actionStyle.fontWeight,
            modifier = Modifier.glassAction(actionStyle, onClose),
        )
    }
}

@Composable
private fun SettingsItem(
    row: SettingsRow,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val actionStyle = glassActionStyle(selected)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .glassAction(actionStyle, onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                color = actionStyle.contentColor,
                fontWeight = actionStyle.fontWeight,
                modifier = Modifier.weight(1f),
            )
            if (row.value.isNotBlank()) {
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = actionStyle.contentColor,
                    fontWeight = actionStyle.fontWeight,
                    textAlign = TextAlign.End,
                )
            }
        }
        if (row.note.isNotBlank()) {
            Text(
                text = row.note,
                style = MaterialTheme.typography.bodyMedium,
                color = actionStyle.contentColor,
                fontWeight = actionStyle.fontWeight,
            )
        }
    }
}
