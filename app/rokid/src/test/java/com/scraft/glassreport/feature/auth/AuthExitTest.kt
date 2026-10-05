package com.scraft.glassreport.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 認証画面から抜けるときの分岐。
 *
 * Navigation-Compose 本体を動かす結合テストは実機/Robolectric が要るので、
 * **「呼び出し元へ戻す方を選ぶか」だけ**をここで固める。
 * `popBackStack` で戻れば呼び出し元の NavBackStackEntry がそのまま生き、
 * それにひもづく `CaptureViewModel`（＝保持した下書き）も残る。
 */
class AuthExitTest {
    /** 実際の遷移の代わりに「どちらが呼ばれたか」を控える。 */
    private class Nav(
        private val canPop: Boolean = true,
    ) {
        var popped = 0
        var wentHome = 0

        fun pop(): Boolean {
            if (!canPop) return false
            popped++
            return true
        }

        fun home() {
            wentHome++
        }
    }

    @Test
    fun startupAuth_hasNoCallerSoItGoesHome() {
        val nav = Nav()

        leaveAuth(hasCaller = false, popBackStack = nav::pop, goHome = nav::home)

        assertEquals(AuthExit.HOME, authExitFor(hasCaller = false))
        assertEquals(1, nav.wentHome)
        assertEquals(0, nav.popped)
    }

    @Test
    fun authPushedFromAScreen_popsBackToTheCallerInsteadOfStackingHome() {
        val nav = Nav()

        leaveAuth(hasCaller = true, popBackStack = nav::pop, goHome = nav::home)

        assertEquals(AuthExit.CALLER, authExitFor(hasCaller = true))
        assertEquals(1, nav.popped)
        // ホームを積み直さないことが要件。積むと呼び出し元の ViewModel（下書き）が作り直される。
        assertEquals(0, nav.wentHome)
    }

    @Test
    fun skippingAuth_returnsToTheCallerToo() {
        // onDone（サインイン成功）も onSkip（BACK）も同じハンドラを通す。
        val done = Nav()
        val skipped = Nav()

        leaveAuth(hasCaller = true, popBackStack = done::pop, goHome = done::home)
        leaveAuth(hasCaller = true, popBackStack = skipped::pop, goHome = skipped::home)

        assertEquals(1, done.popped)
        assertEquals(1, skipped.popped)
    }

    @Test
    fun unpoppableStack_fallsBackToHomeSoTheScreenNeverGetsStuck() {
        val nav = Nav(canPop = false)

        leaveAuth(hasCaller = true, popBackStack = nav::pop, goHome = nav::home)

        assertEquals(1, nav.wentHome)
    }

    @Test
    fun exitDependsOnlyOnWhetherACallerExists() {
        // 画面名で分岐しない＝記録一覧（Feed）から積まれても同じ道を通る。
        assertTrue(authExitFor(hasCaller = true) == AuthExit.CALLER)
        assertTrue(authExitFor(hasCaller = false) == AuthExit.HOME)
    }
}
