package com.scraft.glassreport.feature.home

/** ロビーのタッチパッドが現在操作する対象。スワイプは実行ではなく選択だけを行う。 */
internal enum class LobbyFocus { MENU, RECOVERY, CHAT }

internal enum class LobbyDirection { UP, DOWN }

internal enum class LobbyAction { MENU, SIGN_IN, RETRY, NONE }

internal fun selectedLobbyAction(
    focus: LobbyFocus,
    signInRequired: Boolean,
    connectionError: Boolean,
): LobbyAction =
    when (focus) {
        LobbyFocus.MENU -> LobbyAction.MENU
        LobbyFocus.RECOVERY ->
            when {
                signInRequired -> LobbyAction.SIGN_IN
                connectionError -> LobbyAction.RETRY
                else -> LobbyAction.NONE
            }
        LobbyFocus.CHAT -> LobbyAction.NONE
    }

/** 会話から上へ戻るのは、過去投稿を最後までたどったときだけ。 */
internal fun moveLobbyFocus(
    current: LobbyFocus,
    direction: LobbyDirection,
    hasRecovery: Boolean,
    hasChat: Boolean,
    canScrollOlder: Boolean = false,
    canScrollNewer: Boolean = false,
): LobbyFocus =
    when (current) {
        LobbyFocus.MENU ->
            if (direction == LobbyDirection.DOWN) {
                when {
                    hasRecovery -> LobbyFocus.RECOVERY
                    hasChat -> LobbyFocus.CHAT
                    else -> LobbyFocus.MENU
                }
            } else if (hasChat) {
                LobbyFocus.CHAT
            } else {
                LobbyFocus.MENU
            }
        LobbyFocus.RECOVERY ->
            when (direction) {
                LobbyDirection.UP -> LobbyFocus.MENU
                LobbyDirection.DOWN -> if (hasChat) LobbyFocus.CHAT else LobbyFocus.RECOVERY
            }
        LobbyFocus.CHAT ->
            when (direction) {
                LobbyDirection.UP ->
                    if (canScrollOlder) {
                        LobbyFocus.CHAT
                    } else if (hasRecovery) {
                        LobbyFocus.RECOVERY
                    } else {
                        LobbyFocus.MENU
                    }
                LobbyDirection.DOWN -> if (canScrollNewer) LobbyFocus.CHAT else LobbyFocus.MENU
            }
    }
