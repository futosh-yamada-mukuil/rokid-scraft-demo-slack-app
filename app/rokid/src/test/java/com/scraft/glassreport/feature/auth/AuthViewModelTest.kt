package com.scraft.glassreport.feature.auth

import com.scraft.glassreport.net.teams.DeviceCode
import com.scraft.glassreport.net.teams.SignInFlow
import com.scraft.glassreport.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sampleCode =
        DeviceCode(
            userCode = "F7KQ2M9XZ",
            verificationUri = "https://microsoft.com/devicelogin",
            deviceCode = "dev-1",
            intervalMs = 5_000L,
            expiresAtMs = 900_000L,
        )

    /** 実際の HTTP を伴わないサインイン。待ち状態を観察できるよう [gate] で完了を制御する。 */
    private class FakeSignInFlow(
        override val configured: Boolean = true,
        private val signedIn: Boolean = false,
        private val token: String? = "at-1",
        private var deviceCode: Result<DeviceCode>? = null,
    ) : SignInFlow {
        var requestedCodes = 0
            private set
        private var gate = CompletableDeferred<Result<Unit>>()

        fun answerWith(code: Result<DeviceCode>) {
            deviceCode = code
            gate = CompletableDeferred()
        }

        fun finishSignIn(result: Result<Unit>) = gate.complete(result)

        override fun signedIn(): Boolean = signedIn

        override suspend fun accessToken(forceRefresh: Boolean): String? = token

        override suspend fun requestDeviceCode(): Result<DeviceCode> {
            requestedCodes++
            return deviceCode ?: Result.failure(IllegalStateException("未設定"))
        }

        override suspend fun awaitSignIn(code: DeviceCode): Result<Unit> = gate.await()
    }

    @Test
    fun showsUserCodeWhileWaitingThenFinishesWhenTheUserSignsIn() =
        runTest(mainDispatcherRule.dispatcher) {
            val auth = FakeSignInFlow().apply { answerWith(Result.success(sampleCode)) }

            val vm = AuthViewModel(auth)
            advanceUntilIdle()

            // 人がブラウザに入力する間、コードと URL を出したまま待つ。
            assertEquals(AuthPhase.WAITING, vm.ui.value.phase)
            assertEquals("F7KQ2M9XZ", vm.ui.value.userCode)
            assertEquals("https://microsoft.com/devicelogin", vm.ui.value.verificationUri)

            auth.finishSignIn(Result.success(Unit))
            advanceUntilIdle()

            assertEquals(AuthPhase.DONE, vm.ui.value.phase)
        }

    @Test
    fun signedInAlready_skipsStraightToDoneWithoutAskingForACode() =
        runTest(mainDispatcherRule.dispatcher) {
            val auth = FakeSignInFlow(signedIn = true)

            val vm = AuthViewModel(auth)
            advanceUntilIdle()

            assertEquals(AuthPhase.DONE, vm.ui.value.phase)
            assertEquals(0, auth.requestedCodes)
        }

    @Test
    fun signedInButTokenUnusable_asksForANewCode() =
        runTest(mainDispatcherRule.dispatcher) {
            // refresh_token が失効している状態（accessToken が null）。
            val auth = FakeSignInFlow(signedIn = true, token = null).apply { answerWith(Result.success(sampleCode)) }

            val vm = AuthViewModel(auth)
            advanceUntilIdle()

            assertEquals(AuthPhase.WAITING, vm.ui.value.phase)
            assertEquals(1, auth.requestedCodes)
        }

    @Test
    fun notConfigured_letsTheUserContinueWithoutTeams() =
        runTest(mainDispatcherRule.dispatcher) {
            val auth = FakeSignInFlow(configured = false)

            val vm = AuthViewModel(auth)
            advanceUntilIdle()

            assertEquals(AuthPhase.NOT_CONFIGURED, vm.ui.value.phase)
            assertTrue(
                vm.ui.value.message
                    .contains("ダブルタップで戻る"),
            )
            assertEquals(0, auth.requestedCodes)
        }

    @Test
    fun deviceCodeError_requiresLeavingBeforeAnotherCodeIsGenerated() =
        runTest(mainDispatcherRule.dispatcher) {
            val auth = FakeSignInFlow().apply { answerWith(Result.failure(IOException("AADSTS7000218: 設定が不正"))) }

            val vm = AuthViewModel(auth)
            advanceUntilIdle()

            assertEquals(AuthPhase.FAILED, vm.ui.value.phase)
            assertEquals("AADSTS7000218: 設定が不正（戻って入り直してください）", vm.ui.value.message)
            assertEquals(1, auth.requestedCodes)
        }

    @Test
    fun pollingFailure_isShownWithRetryHint() =
        runTest(mainDispatcherRule.dispatcher) {
            val auth = FakeSignInFlow().apply { answerWith(Result.success(sampleCode)) }
            val vm = AuthViewModel(auth)
            advanceUntilIdle()

            auth.finishSignIn(Result.failure(IOException("コードの有効期限が切れました")))
            advanceUntilIdle()

            assertEquals(AuthPhase.FAILED, vm.ui.value.phase)
            assertEquals("コードの有効期限が切れました（戻って入り直してください）", vm.ui.value.message)
        }
}
