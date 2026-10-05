package com.scraft.glassreport.core.input

/**
 * ダブルタップ無効時のタップのデバウンス。前身アプリから引き継いだ「正」の値をそのまま使う。
 * この経路ではシングルタップを**即発火**する（撮影画面のシャッターを遅らせない）。
 */
internal const val TAP_DEBOUNCE_MS = 250L

/**
 * ダブルタップ有効時、1回の物理タップで複数キー（DPAD_CENTER と ENTER など）が届く分を潰す窓。
 *
 * ここで [TAP_DEBOUNCE_MS]（250ms）を流用すると、人が打つダブルタップの間隔（実測でおよそ 120〜250ms）が
 * まるごとデバウンスに吸われ、**2回目のタップが永久に届かない**。だから「同一タップの重複イベント」だけを
 * 潰せる短い窓に分けている。移動（スワイプ）側の 250ms デバウンスは別カウンタなので影響しない。
 */
internal const val TAP_BOUNCE_MS = 80L

/** 2回目のタップをダブルタップとみなす窓。シングルタップはこの窓ぶん遅れて発火する。 */
internal const val DOUBLE_TAP_WINDOW_MS = 300L

/** [TapArbiter.onTap] の判定結果。 */
internal sealed interface TapDecision {
    /** 同一タップの重複イベント。何もしない。 */
    data object Ignore : TapDecision

    /** シングルタップを即発火する（ダブルタップを使わない画面）。 */
    data object Single : TapDecision

    /** ダブルタップを発火する。保留していたシングルは破棄済み。 */
    data object Double : TapDecision

    /**
     * シングルタップを保留する。[delayMs] 待ってから [TapArbiter.consumeHeldSingle] に [id] を渡し、
     * true が返ったときだけシングルを発火する（間に2回目のタップが来ていたら false）。
     */
    data class HoldSingle(
        val id: Long,
        val delayMs: Long,
    ) : TapDecision
}

/**
 * タップ列を「シングル」「ダブル」に振り分ける純粋ロジック。
 *
 * 時刻は呼び出し側が渡す（`SystemClock.uptimeMillis()`／テストでは任意の値）。Android にも Compose にも
 * 依存しないので JVM のユニットテストで検証できる。状態を持つため画面ごとに1インスタンス。
 */
internal class TapArbiter(
    private val debounceMs: Long = TAP_DEBOUNCE_MS,
    private val bounceMs: Long = TAP_BOUNCE_MS,
    private val doubleTapWindowMs: Long = DOUBLE_TAP_WINDOW_MS,
) {
    /** 直近に受理したタップの時刻。未タップは null（`Long.MIN_VALUE` だと差分が桁あふれする）。 */
    private var lastTapAt: Long? = null

    /** 保留中シングルの識別子。0 は「保留なし」。 */
    private var heldId = 0L
    private var nextId = 1L

    /**
     * タップ1件を判定する。
     *
     * @param nowMs 単調増加する時刻（ミリ秒）。
     * @param doubleTapEnabled ダブルタップを見る画面かどうか。false なら既存挙動（250ms デバウンス＋即発火）。
     */
    fun onTap(
        nowMs: Long,
        doubleTapEnabled: Boolean,
    ): TapDecision {
        val sinceLast = lastTapAt?.let { nowMs - it }

        if (!doubleTapEnabled) {
            if (sinceLast != null && sinceLast < debounceMs) return TapDecision.Ignore
            lastTapAt = nowMs
            heldId = 0L
            return TapDecision.Single
        }

        // 同一タップの重複イベント（キーが2種類届く等）は 1 操作に集約する。
        if (sinceLast != null && sinceLast < bounceMs) return TapDecision.Ignore
        lastTapAt = nowMs

        val isSecondTap = heldId != 0L && sinceLast != null && sinceLast <= doubleTapWindowMs
        return if (isSecondTap) {
            heldId = 0L
            TapDecision.Double
        } else {
            heldId = nextId++
            TapDecision.HoldSingle(heldId, doubleTapWindowMs)
        }
    }

    /**
     * 判定窓の満了後に呼ぶ。保留していたシングルがまだ有効なら true（発火してよい）。
     * 間にダブルタップが成立していた場合は既に破棄されているので false。
     */
    fun consumeHeldSingle(id: Long): Boolean {
        if (heldId != id) return false
        heldId = 0L
        return true
    }

    /** A new screen state owns subsequent taps; delayed callbacks from the old state must not fire. */
    fun cancelPending() {
        heldId = 0L
    }
}
