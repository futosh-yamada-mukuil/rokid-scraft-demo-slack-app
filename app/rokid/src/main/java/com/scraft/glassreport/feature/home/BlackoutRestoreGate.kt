package com.scraft.glassreport.feature.home

/** 黒画面を開いたタップの残りイベントで、すぐ復帰してしまうのを防ぐ。 */
internal class BlackoutRestoreGate(
    private val openedAtMs: Long,
) {
    fun allowsTap(nowMs: Long): Boolean = nowMs - openedAtMs >= 400L
}
