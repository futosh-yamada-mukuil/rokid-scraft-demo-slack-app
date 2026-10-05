package com.scraft.glassreport.feature.feed

import com.scraft.glassreport.capture.tts.SpeakPhase
import com.scraft.glassreport.capture.tts.SpeakStatus
import com.scraft.glassreport.capture.tts.Speaker
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.core.sound.Chime
import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.translate.Translator
import com.scraft.glassreport.net.SignInRequiredException
import com.scraft.glassreport.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = 1_700_000_000_000L

    private fun post(
        id: String,
        author: String = "山田 太郎",
        text: String = "了解しました。すぐに向かいます。",
        minutesAgo: Long = 3,
        attachments: Int = 0,
    ) = FeedPost(
        id = id,
        author = author,
        text = text,
        createdAtMs = now - minutesAgo * 60_000L,
        attachmentCount = attachments,
    )

    @Test
    fun firstLoad_showsNewestPostWithoutRingingTheChime() =
        runTest(mainDispatcherRule.dispatcher) {
            val chime = FakeChime()
            val vm = viewModel(FakeFeedRepository(listOf(post("2"), post("1"))), chime = chime)

            vm.refresh()

            assertEquals(
                "2",
                vm.ui.value.current
                    ?.id,
            )
            assertEquals(
                "山田 太郎",
                vm.ui.value.current
                    ?.author,
            )
            assertEquals("3分前", vm.ui.value.elapsed)
            assertEquals("1 / 2", vm.ui.value.position)
            assertTrue(vm.ui.value.loaded)
            // 起動しただけで鳴らさない（既存の投稿はすべて「見たもの」として扱う）。
            assertEquals(0, chime.played)
            assertFalse(vm.ui.value.highlighted)
        }

    @Test
    fun newArrival_ringsMovesToItAndHighlights() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeFeedRepository(listOf(post("1")))
            val chime = FakeChime()
            val vm = viewModel(repository, chime = chime)
            vm.refresh()

            repository.posts = listOf(post("2", author = "佐藤 花子", minutesAgo = 0), post("1"))
            vm.refresh()

            assertEquals(1, chime.played)
            assertEquals(
                "2",
                vm.ui.value.current
                    ?.id,
            )
            assertEquals(
                "佐藤 花子",
                vm.ui.value.current
                    ?.author,
            )
            assertEquals("たった今", vm.ui.value.elapsed)
            assertTrue(vm.ui.value.highlighted)
        }

    @Test
    fun refreshWithoutNewPosts_keepsThePostBeingRead() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeFeedRepository(listOf(post("3"), post("2"), post("1")))
            val chime = FakeChime()
            val vm = viewModel(repository, chime = chime)
            vm.refresh()
            vm.nextPost()
            vm.nextPost()

            assertEquals(
                "1",
                vm.ui.value.current
                    ?.id,
            )
            vm.refresh()

            assertEquals(
                "1",
                vm.ui.value.current
                    ?.id,
            )
            assertEquals(0, chime.played)
        }

    @Test
    fun swipeMovesThroughPostsAndWrapsAround() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeFeedRepository(listOf(post("3"), post("2"), post("1"))))
            vm.refresh()

            vm.nextPost()
            assertEquals(
                "2",
                vm.ui.value.current
                    ?.id,
            )
            assertEquals("2 / 3", vm.ui.value.position)

            vm.previousPost()
            vm.previousPost()
            assertEquals(
                "1",
                vm.ui.value.current
                    ?.id,
            )

            vm.nextPost()
            assertEquals(
                "3",
                vm.ui.value.current
                    ?.id,
            )
        }

    @Test
    fun swipeClearsTheNewArrivalHighlight() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeFeedRepository(listOf(post("1")))
            val vm = viewModel(repository)
            vm.refresh()
            repository.posts = listOf(post("2"), post("1"))
            vm.refresh()
            assertTrue(vm.ui.value.highlighted)

            vm.nextPost()

            assertFalse(vm.ui.value.highlighted)
        }

    @Test
    fun polling_refreshesOnceEveryInterval_andStopsWhenCancelled() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeFeedRepository(listOf(post("1")))
            val vm = viewModel(repository)

            val polling = backgroundScope.launch { vm.poll() }
            runCurrent()
            assertEquals(1, repository.calls)

            advanceTimeBy(FEED_REFRESH_INTERVAL_MS)
            runCurrent()
            assertEquals(2, repository.calls)

            advanceTimeBy(FEED_REFRESH_INTERVAL_MS)
            runCurrent()
            assertEquals(3, repository.calls)

            // 画面が消えた（STARTED を外れた）ときに相当する。以降は 1 度も叩かない。
            polling.cancel()
            advanceTimeBy(FEED_REFRESH_INTERVAL_MS * 3)
            runCurrent()
            assertEquals(3, repository.calls)
        }

    @Test
    fun failure_keepsPostsAndShowsReason() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeFeedRepository(listOf(post("1")))
            val vm = viewModel(repository)
            vm.refresh()

            repository.result = Result.failure(IOException("ネットワークに接続できません"))
            vm.refresh()

            assertEquals(
                "1",
                vm.ui.value.current
                    ?.id,
            )
            assertEquals("ネットワークに接続できません", vm.ui.value.error)
            assertFalse(vm.ui.value.loading)
        }

    @Test
    fun signInRequired_isSurfacedForTheScreenToNavigate() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeFeedRepository(emptyList())
            repository.result = Result.failure(SignInRequiredException("Teams 未サインイン"))
            val vm = viewModel(repository)

            vm.refresh()
            assertTrue(vm.ui.value.signInRequired)

            vm.onSignInHandled()
            assertFalse(vm.ui.value.signInRequired)
        }

    @Test
    fun tapOpensTheActionMenuAndClosingReturnsToTheFeed() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeFeedRepository(listOf(post("1"))))
            vm.refresh()

            vm.onTap()
            assertTrue(vm.ui.value.menuOpen)

            vm.closeMenu()
            assertFalse(vm.ui.value.menuOpen)
        }

    @Test
    fun speakJapanese_readsTheSelectedPostAndClosesTheMenu() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val vm = viewModel(FakeFeedRepository(listOf(post("1"))), speaker = speaker)
            vm.refresh()
            vm.openMenu()

            vm.speakJapanese()
            runCurrent()

            assertFalse(vm.ui.value.menuOpen)
            assertEquals(listOf("了解しました。すぐに向かいます。" to SpeechLanguage.JA), speaker.spoken)
            speaker.playing()
            runCurrent()
            assertEquals("読み上げ中…", vm.ui.value.speechMessage)

            speaker.finish()
            runCurrent()
            assertEquals(FeedSpeech.NONE, vm.ui.value.speech)
            assertEquals("", vm.ui.value.speechMessage)
        }

    @Test
    fun speakEnglish_translatesThenReadsInEnglish() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val translator = FakeTranslator(Result.success("Understood. On my way."))
            val vm = viewModel(FakeFeedRepository(listOf(post("1"))), speaker = speaker, translator = translator)
            vm.refresh()

            vm.speakEnglish()
            assertEquals(FeedSpeech.TRANSLATING, vm.ui.value.speech)
            assertEquals("英訳中…", vm.ui.value.speechMessage)

            runCurrent()
            assertEquals(listOf("Understood. On my way." to SpeechLanguage.EN), speaker.spoken)
            assertEquals(FeedSpeech.SPEAKING, vm.ui.value.speech)
        }

    @Test
    fun translationFailure_fallsBackToJapaneseAndSaysSo() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val translator = FakeTranslator(Result.failure(IOException("翻訳が混雑中")))
            val vm = viewModel(FakeFeedRepository(listOf(post("1"))), speaker = speaker, translator = translator)
            vm.refresh()

            vm.speakEnglish()
            runCurrent()
            speaker.playing()
            runCurrent()

            assertEquals(listOf("了解しました。すぐに向かいます。" to SpeechLanguage.JA), speaker.spoken)
            assertEquals("読み上げ中…（英訳できず）", vm.ui.value.speechMessage)
        }

    @Test
    fun tapWhileSpeakingStopsInsteadOfOpeningTheMenu() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val vm = viewModel(FakeFeedRepository(listOf(post("1"))), speaker = speaker)
            vm.refresh()
            vm.speakJapanese()
            runCurrent()

            vm.onTap()

            assertEquals(1, speaker.stopped)
            assertFalse(vm.ui.value.menuOpen)
            assertEquals(FeedSpeech.NONE, vm.ui.value.speech)
        }

    @Test
    fun photoOnlyPost_isNotSpokenButSaysWhy() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val vm = viewModel(FakeFeedRepository(listOf(post("1", text = "", attachments = 1))), speaker = speaker)
            vm.refresh()
            vm.openMenu()

            vm.speakJapanese()
            runCurrent()

            assertTrue(speaker.spoken.isEmpty())
            assertEquals("読み上げる本文がありません", vm.ui.value.speechMessage)
            assertEquals("画像 1 件", vm.ui.value.attachments)
            assertFalse(vm.ui.value.menuOpen)
        }

    @Test
    fun emptyChannel_isDistinguishedFromNotLoadedYet() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeFeedRepository(emptyList()))

            assertFalse(vm.ui.value.loaded)
            vm.refresh()

            assertTrue(vm.ui.value.loaded)
            assertEquals(null, vm.ui.value.current)
            assertEquals("", vm.ui.value.position)
        }

    private fun viewModel(
        repository: FeedRepository,
        speaker: Speaker = FakeSpeaker(),
        translator: Translator = FakeTranslator(Result.success("Understood.")),
        chime: Chime = FakeChime(),
    ) = FeedViewModel(repository, speaker, translator, chime, clock = { now })

    private class FakeFeedRepository(
        var posts: List<FeedPost>,
    ) : FeedRepository {
        var calls = 0

        /** 失敗を返させたいときに差し替える。null なら [posts] を返す。 */
        var result: Result<List<FeedPost>>? = null

        override suspend fun latest(): Result<List<FeedPost>> {
            calls++
            return result ?: Result.success(posts)
        }
    }

    private class FakeChime : Chime {
        var played = 0
        var closed = false

        override fun play() {
            played++
        }

        override fun close() {
            closed = true
        }
    }

    /** 合成・再生の進み具合を手で進められるフェイク。 */
    private class FakeSpeaker : Speaker {
        private val _status = MutableStateFlow(SpeakStatus())
        override val status: StateFlow<SpeakStatus> = _status.asStateFlow()

        val spoken = mutableListOf<Pair<String, SpeechLanguage>>()
        var stopped = 0

        override fun speak(
            text: String,
            language: SpeechLanguage,
        ) {
            spoken += text to language
            _status.value = SpeakStatus(SpeakPhase.SYNTHESIZING, language)
        }

        fun playing() = _status.update { it.copy(phase = SpeakPhase.PLAYING) }

        fun finish() = _status.update { it.copy(phase = SpeakPhase.IDLE, message = "") }

        override fun stop() {
            stopped++
            _status.value = SpeakStatus()
        }

        override fun close() {}
    }

    private class FakeTranslator(
        private val result: Result<String>,
    ) : Translator {
        override suspend fun toEnglish(text: String): Result<String> = result
    }
}
