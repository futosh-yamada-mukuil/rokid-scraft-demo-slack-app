package com.scraft.glassreport.feature.notification

import com.scraft.glassreport.domain.model.FeedPost
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatNotificationTitleTest {
    @Test
    fun teamsShowsGraphDisplayNameInsteadOfLanguage() {
        val post = FeedPost("1", "田中", "了解しました", null)

        assertEquals("田中", notificationTitle(post))
    }

    @Test
    fun teamsFallsBackWhenAuthorIsMissing() {
        val post = FeedPost("1", "", "了解しました", null)

        assertEquals("(不明な送信者)", notificationTitle(post))
    }
}
