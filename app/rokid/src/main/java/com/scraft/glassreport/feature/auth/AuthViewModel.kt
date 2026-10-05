package com.scraft.glassreport.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scraft.glassreport.net.teams.SignInFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** サインインの進み具合。画面の文言と操作はこれで決まる。 */
enum class AuthPhase {
    /** 保存済みトークンを確認している。 */
    CHECKING,

    /** devicecode を取得している。 */
    REQUESTING,

    /** コードを表示して、人がブラウザで入力するのを待っている。 */
    WAITING,

    /** サインイン済み。画面は次へ進む。 */
    DONE,

    /** 失敗。戻って認証画面へ入り直すと新しいコードを生成する。 */
    FAILED,

    /** `.env` に Teams の設定が無い。送受信はできないがロビーへ戻れる。 */
    NOT_CONFIGURED,
}

/** 認証画面の状態。 */
data class AuthUiState(
    val phase: AuthPhase = AuthPhase.CHECKING,
    /** 人がブラウザに入力するコード。**画面の主役**なので大きく出す。 */
    val userCode: String = "",
    /** 入力先の URL。グラスでは短く見せたいので `https://` は落として表示する。 */
    val verificationUri: String = "",
    val message: String = "",
)

/**
 * Device Code Flow をグラスの画面に載せる。
 *
 * 初回だけ人が別端末のブラウザでコードを入力する。以降は `refresh_token` で自動更新されるので、
 * この画面は「保存済みトークンがあれば素通り」する（[AuthPhase.DONE] を即座に出す）。
 */
class AuthViewModel(
    private val auth: SignInFlow,
) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        start()
    }

    /** 画面へ入った時に一度だけサインインを開始する。 */
    private fun start() {
        job?.cancel()
        job =
            viewModelScope.launch {
                if (!auth.configured) {
                    _ui.value =
                        AuthUiState(
                            phase = AuthPhase.NOT_CONFIGURED,
                            message = "Slack の設定がありません。ダブルタップで戻る",
                        )
                    return@launch
                }
                _ui.value = AuthUiState(phase = AuthPhase.CHECKING, message = "サインインを確認中…")

                // 保存済みトークンで通るなら、人を待たせずに次へ進む。
                if (auth.signedIn() && auth.accessToken() != null) {
                    _ui.value = AuthUiState(phase = AuthPhase.DONE, message = "サインイン済み")
                    return@launch
                }

                _ui.value = AuthUiState(phase = AuthPhase.REQUESTING, message = "コードを取得中…")
                val code =
                    auth.requestDeviceCode().getOrElse { error ->
                        _ui.value = failed(error.message)
                        return@launch
                    }

                _ui.value =
                    AuthUiState(
                        phase = AuthPhase.WAITING,
                        userCode = code.userCode,
                        verificationUri = code.verificationUri,
                        message = "PCのブラウザで入力してください",
                    )

                auth.awaitSignIn(code).fold(
                    onSuccess = { _ui.value = AuthUiState(phase = AuthPhase.DONE, message = "サインインしました") },
                    onFailure = { error -> _ui.value = failed(error.message) },
                )
            }
    }

    private fun failed(message: String?) =
        AuthUiState(
            phase = AuthPhase.FAILED,
            message = "${message ?: "サインインに失敗しました"}（戻って入り直してください）",
        )
}
