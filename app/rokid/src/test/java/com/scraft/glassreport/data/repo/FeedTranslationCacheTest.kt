package com.scraft.glassreport.data.repo

import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.model.TranslationState
import com.scraft.glassreport.domain.translate.TranslationException
import com.scraft.glassreport.domain.translate.Translator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedTranslationCacheTest {
    private fun post(
        text: String = "请检查照片",
        id: String = "1",
    ) = FeedPost(id, "Author", text, null, canSpeak = false)

    private class FakeTranslator : Translator {
        val calls = mutableListOf<Pair<String, SpeechLanguage>>()
        var fail = false
        var wait = 0L

        override suspend fun toEnglish(text: String) = translate(text, SpeechLanguage.EN)

        override suspend fun translate(
            text: String,
            targetLanguage: SpeechLanguage,
        ): Result<String> {
            calls += text to targetLanguage
            delay(wait)
            return if (fail) Result.failure(TranslationException("offline", code = 429)) else Result.success("${targetLanguage.name}:$text")
        }
    }

    @Test fun sharesResultsAndSeparatesEditedTextTargetAndChannel() =
        runTest {
            val translator = FakeTranslator()
            val cache = FeedTranslationCache(translator, backgroundScope)

            fun get(
                text: String = "请检查照片",
                target: SpeechLanguage = SpeechLanguage.EN,
                channel: String = "a",
            ) = cache.present(channel, listOf(post(text)), target).single()
            assertEquals(TranslationState.PENDING, get().translation)
            get()
            runCurrent()
            assertEquals("EN:请检查照片", get().text)
            assertTrue(get().canSpeak)
            assertEquals(1, translator.calls.size)
            get("edited")
            runCurrent()
            get(target = SpeechLanguage.NL)
            runCurrent()
            get(channel = "b")
            runCurrent()
            assertEquals(4, translator.calls.size)
        }

    @Test fun skipsImagesAndBacksOffFailuresWithoutGuessingJapanese() =
        runTest {
            val translator = FakeTranslator().apply { fail = true }
            val cache = FeedTranslationCache(translator, backgroundScope, clock = { testScheduler.currentTime })
            val image = post("").copy(imageCount = 1)
            assertFalse(cache.present("a", listOf(image), SpeechLanguage.EN).single().canSpeak)
            cache.present("a", listOf(post(), post("second", "2")), SpeechLanguage.EN)
            runCurrent()
            val failed = cache.present("a", listOf(post()), SpeechLanguage.EN).single()
            assertEquals(TranslationState.FAILED, failed.translation)
            assertFalse(failed.canSpeak)
            assertEquals("请检查照片", failed.text)
            assertEquals(1, translator.calls.size)
            advanceTimeBy(60_001)
            translator.fail = false
            cache.present("a", listOf(post()), SpeechLanguage.EN)
            runCurrent()
            assertEquals(2, translator.calls.size)
        }

    @Test fun queueWaitIsBoundedAndOldLanguageCannotReplaceNewOne() =
        runTest {
            val translator = FakeTranslator().apply { wait = 30_000 }
            val cache = FeedTranslationCache(translator, backgroundScope, clock = { testScheduler.currentTime })
            val posts = (1..20).map { post("text $it", "$it") }
            cache.present("a", posts, SpeechLanguage.EN)
            runCurrent()
            advanceTimeBy(25_001)
            runCurrent()
            assertTrue(cache.present("a", posts, SpeechLanguage.EN).all { it.translation == TranslationState.FAILED })
            assertEquals(1, translator.calls.size)
            translator.wait = 0
            cache.present("a", posts.take(1), SpeechLanguage.NL)
            runCurrent()
            assertEquals(SpeechLanguage.NL, cache.present("a", posts.take(1), SpeechLanguage.NL).single().language)
        }

    @Test fun repositoryReusesRawRowsForImmediateLanguageSwitch() =
        runTest {
            var fetches = 0
            var target = SpeechLanguage.EN
            val translator = FakeTranslator()
            val cache = FeedTranslationCache(translator, backgroundScope)
            val repo =
                TranslatedFeedRepository(
                    object : FeedRepository {
                        override suspend fun latest(): Result<List<FeedPost>> {
                            fetches++
                            return Result.success(listOf(post()))
                        }
                    },
                    cache,
                    { "a" },
                    { target },
                    emptyFlow(),
                )
            repo.latest()
            runCurrent()
            assertEquals(
                "EN:请检查照片",
                repo
                    .latest()
                    .getOrThrow()
                    .single()
                    .text,
            )
            target = SpeechLanguage.NL
            repo.latest()
            runCurrent()
            assertEquals(
                "NL:请检查照片",
                repo
                    .latest()
                    .getOrThrow()
                    .single()
                    .text,
            )
            assertEquals(1, fetches)
        }

    @Test fun notificationsBaselinePendingHistoryButWaitForNewTranslation() {
        val seen = SeenPostCursor()
        val old = post(id = "old").copy(translation = TranslationState.PENDING)
        assertNull(seen.claimNewest(listOf(old)))
        assertNull(seen.claimNewest(listOf(old.copy(translation = TranslationState.TRANSLATED))))
        val fresh = post(id = "new").copy(translation = TranslationState.PENDING)
        assertNull(seen.claimNewest(listOf(fresh, old)))
        assertEquals("new", seen.claimNewest(listOf(fresh.copy(translation = TranslationState.TRANSLATED), old))?.id)
        assertNull(seen.claimNewest(listOf(fresh.copy(translation = TranslationState.TRANSLATED, language = SpeechLanguage.NL), old)))
    }
}
