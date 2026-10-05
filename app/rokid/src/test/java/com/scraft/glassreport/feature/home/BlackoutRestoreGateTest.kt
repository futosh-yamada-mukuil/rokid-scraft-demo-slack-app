package com.scraft.glassreport.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackoutRestoreGateTest {
    @Test
    fun openingTapCannotImmediatelyRestoreBlackout() {
        val gate = BlackoutRestoreGate(openedAtMs = 1_000L)

        assertFalse(gate.allowsTap(1_000L))
        assertFalse(gate.allowsTap(1_399L))
        assertTrue(gate.allowsTap(1_400L))
        assertTrue(gate.allowsTap(2_000L))
    }
}
