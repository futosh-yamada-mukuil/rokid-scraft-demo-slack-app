package com.scraft.glassreport.data.repo

import com.scraft.glassreport.domain.model.FeedPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeenPostCursorTest {
    @Test
    fun firstFetchSeedsAndLaterFetchClaimsOnlyNewMessage() {
        val cursor = SeenPostCursor()
        assertNull(cursor.claimNewest(listOf(post("old"))))
        assertEquals("new", cursor.claimNewest(listOf(post("new"), post("old")))?.id)
        assertNull(cursor.claimNewest(listOf(post("new"), post("old"))))
    }

    @Test
    fun restoredIdsDoNotNotifyAgain() {
        val before = SeenPostCursor()
        before.claimNewest(listOf(post("old")))
        before.claimNewest(listOf(post("new"), post("old")))
        val restored = SeenPostCursor(initialized = before.initialized, ids = before.ids())
        assertNull(restored.claimNewest(listOf(post("new"), post("old"))))
        assertEquals("next", restored.claimNewest(listOf(post("next"), post("new")))?.id)
    }

    private fun post(id: String) = FeedPost(id, "テスト", "本文", createdAtMs = 0L)
}
