package com.scraft.glassreport.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * タップ判定の仕様をここに固定する。時刻は引数で注入するので実時間を待つ必要はない。
 *
 * 実機の値: 移動・決定のデバウンス 250ms（前身から引き継いだ正）／ダブルタップ判定窓 300ms。
 */
class TapArbiterTest {
    @Test
    fun phaseChangeCancelsHeldRecordingTapBeforeItCanReachConfirmation() {
        val recording = TapArbiter()
        val pending = recording.onTap(4_900, doubleTapEnabled = true) as TapDecision.HoldSingle

        // Five-second auto-stop replaces the input key before the single-tap delay expires.
        recording.cancelPending()
        assertFalse(recording.consumeHeldSingle(pending.id))

        val confirmation = TapArbiter()
        val next = confirmation.onTap(5_300, doubleTapEnabled = true) as TapDecision.HoldSingle
        assertTrue(confirmation.consumeHeldSingle(next.id))
        assertFalse(recording.consumeHeldSingle(pending.id))
    }

    // ── ダブルタップを使わない画面（撮影画面など）: 既存挙動を壊さない ──────────────────

    @Test
    fun singleOnly_firesImmediately() {
        val arbiter = TapArbiter()

        assertEquals(TapDecision.Single, arbiter.onTap(1_000, doubleTapEnabled = false))
    }

    @Test
    fun singleOnly_debouncesRepeatsWithin250ms() {
        val arbiter = TapArbiter()

        assertEquals(TapDecision.Single, arbiter.onTap(1_000, doubleTapEnabled = false))
        assertEquals(TapDecision.Ignore, arbiter.onTap(1_100, doubleTapEnabled = false))
        assertEquals(TapDecision.Ignore, arbiter.onTap(1_249, doubleTapEnabled = false))
        assertEquals(TapDecision.Single, arbiter.onTap(1_250, doubleTapEnabled = false))
    }

    @Test
    fun singleOnly_firesAtTimeZero() {
        // uptimeMillis が小さい（起動直後）でも初回タップを取りこぼさない。
        val arbiter = TapArbiter()

        assertEquals(TapDecision.Single, arbiter.onTap(0, doubleTapEnabled = false))
    }

    // ── ダブルタップを使う画面（ホーム・メニューなど） ──────────────────────────────

    @Test
    fun firstTap_isHeldForTheWindow() {
        val arbiter = TapArbiter()

        val decision = arbiter.onTap(1_000, doubleTapEnabled = true)

        assertTrue(decision is TapDecision.HoldSingle)
        assertEquals(DOUBLE_TAP_WINDOW_MS, (decision as TapDecision.HoldSingle).delayMs)
    }

    @Test
    fun heldSingle_firesWhenWindowExpiresWithoutSecondTap() {
        val arbiter = TapArbiter()

        val held = arbiter.onTap(1_000, doubleTapEnabled = true) as TapDecision.HoldSingle

        assertTrue(arbiter.consumeHeldSingle(held.id))
        // 二重発火しない。
        assertFalse(arbiter.consumeHeldSingle(held.id))
    }

    @Test
    fun secondTapWithinWindow_isDoubleAndCancelsHeldSingle() {
        val arbiter = TapArbiter()

        val held = arbiter.onTap(1_000, doubleTapEnabled = true) as TapDecision.HoldSingle
        assertEquals(TapDecision.Double, arbiter.onTap(1_200, doubleTapEnabled = true))

        // 保留していたシングルは破棄されるので、窓が明けても発火しない。
        assertFalse(arbiter.consumeHeldSingle(held.id))
    }

    @Test
    fun secondTapAtWindowEdge_isStillDouble() {
        val arbiter = TapArbiter()

        arbiter.onTap(1_000, doubleTapEnabled = true)

        assertEquals(TapDecision.Double, arbiter.onTap(1_000 + DOUBLE_TAP_WINDOW_MS, doubleTapEnabled = true))
    }

    @Test
    fun secondTapAfterWindow_startsANewSingle() {
        val arbiter = TapArbiter()

        val first = arbiter.onTap(1_000, doubleTapEnabled = true) as TapDecision.HoldSingle
        assertTrue(arbiter.consumeHeldSingle(first.id)) // 窓が明けてシングル発火

        val second = arbiter.onTap(1_000 + DOUBLE_TAP_WINDOW_MS + 1, doubleTapEnabled = true)
        assertTrue(second is TapDecision.HoldSingle)
        assertTrue(arbiter.consumeHeldSingle((second as TapDecision.HoldSingle).id))
    }

    @Test
    fun doubleTapIsNotSwallowedByTheMoveDebounce() {
        // 250ms デバウンスを流用していると、人が打つダブルタップ（120〜250ms 間隔）が
        // まるごと吸われて 2 回目が届かない。判定窓と干渉しないことを固定する。
        listOf(100L, 150L, 200L, 249L, 250L).forEach { gap ->
            val arbiter = TapArbiter()
            arbiter.onTap(5_000, doubleTapEnabled = true)

            assertEquals(
                "間隔 ${gap}ms のタップはダブルタップとして届くべき",
                TapDecision.Double,
                arbiter.onTap(5_000 + gap, doubleTapEnabled = true),
            )
        }
    }

    @Test
    fun duplicateKeysFromOneTapAreCollapsed() {
        // 1回の物理タップで DPAD_CENTER と ENTER の 2 発が届くケース。
        val arbiter = TapArbiter()

        val held = arbiter.onTap(2_000, doubleTapEnabled = true)
        assertTrue(held is TapDecision.HoldSingle)
        assertEquals(TapDecision.Ignore, arbiter.onTap(2_000 + TAP_BOUNCE_MS - 1, doubleTapEnabled = true))

        // 重複は無視されるだけで、保留中のシングルは生きている。
        assertTrue(arbiter.consumeHeldSingle((held as TapDecision.HoldSingle).id))
    }

    @Test
    fun tripleTap_isDoubleThenSingle() {
        val arbiter = TapArbiter()

        arbiter.onTap(1_000, doubleTapEnabled = true)
        assertEquals(TapDecision.Double, arbiter.onTap(1_150, doubleTapEnabled = true))

        val third = arbiter.onTap(1_300, doubleTapEnabled = true)
        assertTrue(third is TapDecision.HoldSingle)
        assertTrue(arbiter.consumeHeldSingle((third as TapDecision.HoldSingle).id))
    }

    @Test
    fun staleHoldId_neverFires() {
        val arbiter = TapArbiter()

        val first = arbiter.onTap(1_000, doubleTapEnabled = true) as TapDecision.HoldSingle
        assertTrue(arbiter.consumeHeldSingle(first.id))

        val second = arbiter.onTap(2_000, doubleTapEnabled = true) as TapDecision.HoldSingle

        // 古い保留の遅延処理が遅れて到着しても、新しい保留を消費してしまわない。
        assertFalse(arbiter.consumeHeldSingle(first.id))
        assertTrue(arbiter.consumeHeldSingle(second.id))
    }
}
