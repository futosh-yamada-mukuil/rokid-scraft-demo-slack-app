package com.scraft.glassreport.feature.notification

import com.scraft.glassreport.core.sound.Chime
import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.net.SignInRequiredException
import com.scraft.glassreport.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TeamsChatNotificationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun firstLoadSeedsSeenMessagesWithoutShowingOrRinging() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRepository(listOf(post("1")))
            val chime = FakeChime()
            val vm = TeamsChatNotificationViewModel(repository, chime)

            vm.refresh()

            assertNull(vm.ui.value.post)
            assertEquals(0, chime.played)
        }

    @Test
    fun newMessageShowsHudRingsOnceAndDisappears() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRepository(listOf(post("1")))
            val chime = FakeChime()
            val vm = TeamsChatNotificationViewModel(repository, chime, holdMs = 8_000L)
            vm.refresh()

            repository.posts = listOf(post("2", author = "佐藤 花子", text = "確認をお願いします"), post("1"))
            vm.refresh()

            assertEquals(
                "2",
                vm.ui.value.post
                    ?.id,
            )
            assertEquals(
                "佐藤 花子",
                vm.ui.value.post
                    ?.author,
            )
            assertEquals(1, chime.played)

            advanceTimeBy(8_000L)
            runCurrent()
            assertNull(vm.ui.value.post)
        }

    @Test
    fun sameMessageIsNeverNotifiedTwice() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRepository(emptyList())
            val chime = FakeChime()
            val vm = TeamsChatNotificationViewModel(repository, chime)
            vm.refresh()
            repository.posts = listOf(post("1"))

            vm.refresh()
            vm.refresh()

            assertEquals(1, chime.played)
        }

    @Test
    fun feedCanSynchronizeSeenIdsWithoutShowingADuplicateNotification() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRepository(emptyList())
            val chime = FakeChime()
            val vm = TeamsChatNotificationViewModel(repository, chime)
            vm.refresh()
            repository.posts = listOf(post("1"))

            vm.refresh(notify = false)
            vm.refresh(notify = true)

            assertNull(vm.ui.value.post)
            assertEquals(0, chime.played)
        }

    @Test
    fun expiredSessionRequestsSignIn() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = TeamsChatNotificationViewModel(FailingRepository(SignInRequiredException("Teams 未サインイン")), FakeChime())

            vm.refresh()

            assertTrue(vm.ui.value.signInRequired)
            vm.onSignInHandled()
            assertFalse(vm.ui.value.signInRequired)
        }

    private fun post(
        id: String,
        author: String = "山田 太郎",
        text: String = "新しいメッセージです",
    ) = FeedPost(id, author, text, createdAtMs = 1_700_000_000_000L)

    private class FakeRepository(
        var posts: List<FeedPost>,
    ) : FeedRepository {
        override suspend fun latest(): Result<List<FeedPost>> = Result.success(posts)
    }

    private class FailingRepository(
        private val error: Throwable,
    ) : FeedRepository {
        override suspend fun latest(): Result<List<FeedPost>> = Result.failure(error)
    }

    private class FakeChime : Chime {
        var played = 0

        override fun play() {
            played++
        }

        override fun close() = Unit
    }
}
