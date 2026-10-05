package com.scraft.glassreport.core.input

import android.os.SystemClock
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEBOUNCE_MS = 250L

/**
 * [glassControls] の `onDoubleSelect` の既定値（何もしない空ラムダ）。
 *
 * この**インスタンスがそのまま渡ってきたか**でダブルタップ判定の有無を切り替える。
 * シングルとダブルを両立させるとシングルの発火が判定窓ぶん遅れるため、
 * ダブルタップを使わない画面（撮影画面のシャッター等）は従来どおり即発火にしたい。
 */
private val NO_DOUBLE_TAP: () -> Unit = {}

/**
 * グラスのタッチパッド（DPADキーとして届く）を扱う共通 Modifier。
 * 1スワイプで複数キー(例: LEFT+UP)が連続送出されることによる“チラつき”を防ぐため、
 * 移動・決定それぞれに 250ms デバウンスをかける。要・画面ルート要素への付与（自動でフォーカス取得）。
 *
 * - 上/左スワイプ → onUp
 * - 下/右スワイプ → onDown
 * - タップ(中央/Enter) → onSelect
 * - ダブルタップ → onDoubleSelect（**渡した画面だけ**有効。長押しはシステムAIが起動するため代替として使う）
 *
 * `onDoubleSelect` を渡すと、シングルタップは判定窓（[DOUBLE_TAP_WINDOW_MS]）ぶん遅れて発火する。
 * 渡さない場合は従来どおり 250ms デバウンスの即発火で、既存の呼び出し側の挙動は変わらない。
 * タップの判定は [TapArbiter]（時刻注入・ユニットテスト対象）に閉じている。
 * [inputKey] が変わると保留タップを破棄する。自動遷移する画面では phase を渡し、次状態への誤操作を防ぐ。
 */
@Composable
fun glassControls(
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
    onSelect: () -> Unit = {},
    onDoubleSelect: () -> Unit = NO_DOUBLE_TAP,
    inputKey: Any? = Unit,
): Modifier {
    val focus = remember { FocusRequester() }
    val lastMove = remember { mutableLongStateOf(0L) }
    val taps = remember(inputKey) { TapArbiter() }
    val scope = rememberCoroutineScope()
    val doubleTapEnabled = onDoubleSelect !== NO_DOUBLE_TAP
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(taps) { onDispose { taps.cancelPending() } }

    return Modifier
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val now = SystemClock.uptimeMillis()
            when (e.key) {
                Key.DirectionDown, Key.DirectionRight -> {
                    if (now - lastMove.longValue >= DEBOUNCE_MS) {
                        lastMove.longValue = now
                        onDown()
                    }
                    true
                }
                Key.DirectionUp, Key.DirectionLeft -> {
                    if (now - lastMove.longValue >= DEBOUNCE_MS) {
                        lastMove.longValue = now
                        onUp()
                    }
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    when (val decision = taps.onTap(now, doubleTapEnabled)) {
                        TapDecision.Ignore -> Unit
                        TapDecision.Single -> onSelect()
                        TapDecision.Double -> onDoubleSelect()
                        is TapDecision.HoldSingle ->
                            scope.launch {
                                delay(decision.delayMs)
                                if (taps.consumeHeldSingle(decision.id)) onSelect()
                            }
                    }
                    true
                }
                else -> false
            }
        }
}
