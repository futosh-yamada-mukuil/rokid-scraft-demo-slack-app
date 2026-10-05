package com.scraft.glassreport.feature.notification

import com.scraft.glassreport.capture.tts.SpeakStatus
import com.scraft.glassreport.capture.tts.Speaker
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.core.sound.Chime
import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.data.repo.FeedTranslationCache
import com.scraft.glassreport.data.repo.SeenPostCursor
import com.scraft.glassreport.data.repo.TranslatedFeedRepository
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.model.TranslationState
import com.scraft.glassreport.domain.translate.Translator
import com.scraft.glassreport.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises real cache completion events, not pre-translated fake notification rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslatedNotificationIntegrationTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun cacheCompletionNotifiesAndSpeaksNewPostExactlyOnceWithoutWaitingForNextPoll() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture(this)
            backgroundScope.launch { fixture.vm.poll() }
            runCurrent()
            fixture.source.posts = listOf(raw("new"))
            advanceTimeBy(10_000)
            runCurrent()
            assertNull(fixture.vm.ui.value.post)
            assertEquals(0, fixture.chime.played)
            assertTrue(fixture.speaker.spoken.isEmpty())

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                "new",
                fixture.vm.ui.value.post
                    ?.id,
            )
            assertEquals(listOf("English:请检查照片" to SpeechLanguage.EN), fixture.speaker.spoken)
            assertEquals(1, fixture.chime.played)
            // Completion reuses the source rows rather than causing another Graph GET.
            assertEquals(2, fixture.source.fetches)
            fixture.vm.refresh()
            assertEquals(1, fixture.speaker.spoken.size)
            assertEquals(1, fixture.chime.played)
        }

    @Test fun pendingHistoryBecomesTranslatedWithoutAnyNotificationOrSpeech() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture(this)
            fixture.source.posts = listOf(raw("old"))
            backgroundScope.launch { fixture.vm.poll() }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertNull(fixture.vm.ui.value.post)
            assertEquals(0, fixture.chime.played)
            assertTrue(fixture.speaker.spoken.isEmpty())
        }

    @Test fun failedTranslationNotifiesOriginalOnceAndNeverSpeaksUnknownLanguage() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture(this, fail = true)
            backgroundScope.launch { fixture.vm.poll() }
            runCurrent()
            fixture.source.posts = listOf(raw("new"))
            advanceTimeBy(11_000)
            runCurrent()
            assertEquals(
                TranslationState.FAILED,
                fixture.vm.ui.value.post
                    ?.translation,
            )
            assertEquals(
                "[翻訳できません・原文]\n请检查照片",
                fixture.vm.ui.value.post
                    ?.displayText,
            )
            assertEquals(1, fixture.chime.played)
            assertTrue(fixture.speaker.spoken.isEmpty())
            fixture.vm.refresh()
            assertEquals(1, fixture.chime.played)
        }

    @Test fun backgroundCursorLeavesPendingNewPostForNextTranslatedPollAndSharedForegroundCannotRepeatIt() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture(this)
            val seen = SeenPostCursor()
            assertNull(seen.claimNewest(fixture.repository.latest().getOrThrow()))
            fixture.source.posts = listOf(raw("new"))
            advanceTimeBy(30_000)
            assertNull(seen.claimNewest(fixture.repository.latest().getOrThrow()))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            val claimed = seen.claimNewest(fixture.repository.latest().getOrThrow())
            assertEquals("new", claimed?.id)
            assertTrue(claimed?.canSpeak == true)
            assertEquals(SpeechLanguage.EN, claimed?.language)
            assertNull(seen.claimNewest(fixture.repository.latest().getOrThrow()))
        }

    private class Fixture(
        scope: TestScope,
        fail: Boolean = false,
    ) {
        val source = Source()
        private val translator =
            object : Translator {
                override suspend fun toEnglish(text: String) = translate(text, SpeechLanguage.EN)

                override suspend fun translate(
                    text: String,
                    targetLanguage: SpeechLanguage,
                ): Result<String> {
                    delay(1_000)
                    return if (fail) Result.failure(IllegalStateException("offline")) else Result.success("English:$text")
                }
            }
        val repository =
            TranslatedFeedRepository(
                source,
                FeedTranslationCache(translator, scope.backgroundScope, clock = { scope.testScheduler.currentTime }),
                { "channel" },
                { SpeechLanguage.EN },
                emptyFlow(),
                clock = { scope.testScheduler.currentTime },
            )
        val chime = CountingChime()
        val speaker = CountingSpeaker()
        val vm = TeamsChatNotificationViewModel(repository, chime, speaker)
    }

    private class Source : FeedRepository {
        var posts = emptyList<FeedPost>()
        var fetches = 0

        override suspend fun latest(): Result<List<FeedPost>> {
            fetches++
            return Result.success(posts)
        }
    }

    private class CountingChime : Chime {
        var played = 0

        override fun play() {
            played++
        }

        override fun close() = Unit
    }

    private class CountingSpeaker : Speaker {
        override val status = MutableStateFlow(SpeakStatus())
        val spoken = mutableListOf<Pair<String, SpeechLanguage>>()

        override fun speak(
            text: String,
            language: SpeechLanguage,
        ) {
            spoken += text to language
        }

        override fun stop() = Unit

        override fun close() = Unit
    }

    companion object {
        private fun raw(id: String) = FeedPost(id, "Author", "请检查照片", null, canSpeak = false)
    }
}
