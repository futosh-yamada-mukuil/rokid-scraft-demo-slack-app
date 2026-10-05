package com.scraft.glassreport.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scraft.glassreport.core.input.glassControls
import com.scraft.glassreport.core.ui.GlassScreen
import com.scraft.glassreport.di.rememberContainer
import kotlinx.coroutines.launch

/**
 * Teams サインイン画面。`user_code` を大きく出し、人が別端末のブラウザで入力するのを待つ。
 * ポーリングが通れば自動で [onDone] へ進む（初回だけ通る画面）。
 *
 * BACK とダブルタップでロビーへ戻る。Teams 利用にはサインインが必要。
 */
@Composable
fun AuthScreen(
    onDone: () -> Unit,
    onSkip: () -> Unit = onDone,
) {
    val container = rememberContainer()
    val viewModel: AuthViewModel =
        viewModel(
            factory = viewModelFactory { initializer { AuthViewModel(container.signInFlow) } },
        )
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(ui.phase) { if (ui.phase == AuthPhase.DONE) onDone() }
    BackHandler { onSkip() }

    // コードは画面へ入った時にだけ生成する。単タップは何もしない。
    // 再生成したい場合はいったん BACK / ダブルタップで抜け、入り直す。
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    AuthScreenContent(
        ui = ui,
        scrollState = scroll,
        modifier =
            glassControls(
                onUp = { scope.launch { scroll.animateScrollTo((scroll.value - 80).coerceAtLeast(0)) } },
                onDown = { scope.launch { scroll.animateScrollTo((scroll.value + 80).coerceAtMost(scroll.maxValue)) } },
                onDoubleSelect = onSkip,
            ),
    )
}

/** 実機依存を持たない見た目。debug Preview も本番も同じ UI を使う。 */
@Composable
internal fun AuthScreenContent(
    ui: AuthUiState,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val visibleScroll = scrollState ?: rememberScrollState()

    GlassScreen(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(visibleScroll),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Slack 接続",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.secondary,
            )

            if (ui.userCode.isNotBlank()) {
                // 単眼・低解像度でも読み違えないよう、コードだけは 44sp（displayLarge）で出す。
                // ただし user_code は 9 文字あり、480px(=320dp) 幅では既定の字間 4sp だと収まらない。
                // 字間を詰めて 1 行に入れる（入らなければ折り返す＝切れるよりは読める）。
                Text(
                    text = ui.userCode,
                    style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 1.sp),
                    color = scheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
                Text(
                    text = ui.verificationUri.removePrefix("https://").removePrefix("http://"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.primary,
                    textAlign = TextAlign.Center,
                )
            }

            if (ui.message.isNotBlank()) {
                Text(
                    text = ui.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.secondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (ui.phase == AuthPhase.WAITING) {
                Text(
                    text = "ダブルタップ / BACK で戻る",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.secondary,
                )
            }
        }
    }
}
