package com.scraft.glassreport.feature.home

import com.scraft.glassreport.feature.menu.MenuEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LobbyFocusTest {
    @Test
    fun disconnected_swipeSelectsRecoveryWithoutOpeningMenu() {
        val recovery = moveLobbyFocus(LobbyFocus.MENU, LobbyDirection.DOWN, hasRecovery = true, hasChat = false)
        assertEquals(LobbyFocus.RECOVERY, recovery)
        assertEquals(LobbyAction.SIGN_IN, selectedLobbyAction(recovery, signInRequired = true, connectionError = false))
        assertEquals(LobbyAction.RETRY, selectedLobbyAction(recovery, signInRequired = false, connectionError = true))
        assertEquals(
            LobbyFocus.MENU,
            moveLobbyFocus(recovery, LobbyDirection.UP, hasRecovery = true, hasChat = false),
        )
    }

    @Test
    fun chatScrollStaysInChatUntilOldestMessage() {
        val chat = moveLobbyFocus(LobbyFocus.RECOVERY, LobbyDirection.DOWN, hasRecovery = true, hasChat = true)
        assertEquals(LobbyFocus.CHAT, chat)
        assertEquals(LobbyAction.NONE, selectedLobbyAction(chat, signInRequired = false, connectionError = true))
        assertEquals(
            LobbyFocus.CHAT,
            moveLobbyFocus(chat, LobbyDirection.UP, hasRecovery = true, hasChat = true, canScrollOlder = true),
        )
        assertEquals(
            LobbyFocus.RECOVERY,
            moveLobbyFocus(chat, LobbyDirection.UP, hasRecovery = true, hasChat = true, canScrollOlder = false),
        )
    }

    @Test
    fun connected_menuAndChatAreSeparateFocusTargets() {
        val chat = moveLobbyFocus(LobbyFocus.MENU, LobbyDirection.DOWN, hasRecovery = false, hasChat = true)
        assertEquals(LobbyFocus.CHAT, chat)
        assertEquals(
            LobbyFocus.CHAT,
            moveLobbyFocus(chat, LobbyDirection.DOWN, hasRecovery = false, hasChat = true, canScrollNewer = true),
        )
        assertEquals(
            LobbyFocus.MENU,
            moveLobbyFocus(chat, LobbyDirection.DOWN, hasRecovery = false, hasChat = true, canScrollNewer = false),
        )
        assertEquals(
            LobbyFocus.MENU,
            moveLobbyFocus(chat, LobbyDirection.UP, hasRecovery = false, hasChat = true, canScrollOlder = false),
        )
        assertEquals(
            LobbyFocus.CHAT,
            moveLobbyFocus(LobbyFocus.MENU, LobbyDirection.UP, hasRecovery = false, hasChat = true),
        )
    }

    @Test
    fun overlayMenu_skipsDisabledVideoAndKeepsExitAsLastAction() {
        val entries =
            listOf(
                MenuEntry("写真送信", onSelect = {}),
                MenuEntry("動画送信", enabled = false, onSelect = {}),
                MenuEntry("設定", onSelect = {}),
                MenuEntry("終了", onSelect = {}),
            )

        assertEquals(2, nextHomeEntry(entries, 0, 1))
        assertEquals(0, nextHomeEntry(entries, 2, -1))
        assertEquals(3, nextHomeEntry(entries, 2, 1))
    }
}
