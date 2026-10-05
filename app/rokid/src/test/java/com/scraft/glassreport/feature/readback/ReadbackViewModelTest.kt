package com.scraft.glassreport.feature.readback

import com.scraft.glassreport.capture.tts.SpeakPhase
import com.scraft.glassreport.capture.tts.SpeakStatus
import com.scraft.glassreport.capture.tts.Speaker
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.domain.translate.Translator
import com.scraft.glassreport.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReadbackViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialState_showsFirstSampleAndIdle() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()

            assertEquals(SAMPLE_REPLIES.first(), vm.ui.value.source)
            assertEquals(ReadbackPhase.IDLE, vm.ui.value.phase)
            assertEquals("待機中", vm.ui.value.status)
        }

    @Test
    fun speakJapanese_speaksSourceAndFollowsSpeakerPhases() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val vm = viewModel(speaker)
            runCurrent()

            vm.speakJapanese()
            runCurrent()
            assertEquals(listOf(SAMPLE_REPLIES.first() to SpeechLanguage.JA), speaker.spoken)
            assertEquals("音声を合成中", vm.ui.value.status)
            assertEquals("日本語", vm.ui.value.detail)

            speaker.playing()
            runCurrent()
            assertEquals(ReadbackPhase.SPEAKING, vm.ui.value.phase)
            assertEquals("読み上げ中", vm.ui.value.status)

            speaker.finish()
            runCurrent()
            assertEquals(ReadbackPhase.DONE, vm.ui.value.phase)
            assertEquals("読み上げ完了", vm.ui.value.status)
        }

    @Test
    fun speakEnglish_translatesThenSpeaksInEnglish() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val vm = viewModel(speaker, FakeTranslator(Result.success("Understood. I am on my way.")))
            runCurrent()

            vm.speakEnglish()
            assertEquals(ReadbackPhase.TRANSLATING, vm.ui.value.phase)
            assertEquals("英訳中", vm.ui.value.status)

            runCurrent()
            assertEquals("Understood. I am on my way.", vm.ui.value.translated)
            assertEquals(listOf("Understood. I am on my way." to SpeechLanguage.EN), speaker.spoken)
            assertEquals(ReadbackPhase.SPEAKING, vm.ui.value.phase)
        }

    @Test
    fun translationFailure_showsReasonAndDoesNotSpeak() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val vm = viewModel(speaker, FakeTranslator(Result.failure(IOException("翻訳が混雑中。約27秒後に再試行"))))
            runCurrent()

            vm.speakEnglish()
            runCurrent()

            assertEquals(ReadbackPhase.FAILED, vm.ui.value.phase)
            assertEquals("英訳失敗", vm.ui.value.status)
            assertEquals("翻訳が混雑中。約27秒後に再試行", vm.ui.value.detail)
            assertTrue(speaker.spoken.isEmpty())
        }

    @Test
    fun speakerFailure_showsReasonOnHud() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val vm = viewModel(speaker)
            runCurrent()

            vm.speakJapanese()
            speaker.fail("読み上げキーが未設定")
            runCurrent()

            assertEquals(ReadbackPhase.FAILED, vm.ui.value.phase)
            assertEquals("読み上げ失敗", vm.ui.value.status)
            assertEquals("読み上げキーが未設定", vm.ui.value.detail)
        }

    @Test
    fun whileSpeaking_furtherRequestsAreIgnored() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val translator = FakeTranslator(Result.success("Understood."))
            val vm = viewModel(speaker, translator)
            runCurrent()

            vm.speakJapanese()
            runCurrent()
            vm.speakJapanese()
            vm.speakEnglish()
            runCurrent()

            assertEquals(1, speaker.spoken.size)
            assertEquals(0, translator.calls)
        }

    @Test
    fun nextSample_stopsPlaybackAndResetsTranslation() =
        runTest(mainDispatcherRule.dispatcher) {
            val speaker = FakeSpeaker()
            val vm = viewModel(speaker, FakeTranslator(Result.success("Understood.")))
            runCurrent()

            vm.speakEnglish()
            runCurrent()
            vm.nextSample()
            runCurrent()

            assertEquals(SAMPLE_REPLIES[1], vm.ui.value.source)
            assertEquals("", vm.ui.value.translated)
            assertEquals(ReadbackPhase.IDLE, vm.ui.value.phase)
            assertEquals("待機中", vm.ui.value.status)
            assertEquals(1, speaker.stopped)
        }

    @Test
    fun nextSample_cyclesThroughAllSamples() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()

            repeat(SAMPLE_REPLIES.size) { vm.nextSample() }

            assertEquals(SAMPLE_REPLIES.first(), vm.ui.value.source)
        }

    private fun viewModel(
        speaker: Speaker = FakeSpeaker(),
        translator: Translator = FakeTranslator(Result.success("Understood.")),
    ) = ReadbackViewModel(speaker, translator)

    /** 合成・再生の進み具合を手で進められるフェイク。 */
    private class FakeSpeaker : Speaker {
        private val _status = MutableStateFlow(SpeakStatus())
        override val status: StateFlow<SpeakStatus> = _status.asStateFlow()

        val spoken = mutableListOf<Pair<String, SpeechLanguage>>()
        var stopped = 0
        var closed = false

        override fun speak(
            text: String,
            language: SpeechLanguage,
        ) {
            spoken += text to language
            _status.value = SpeakStatus(SpeakPhase.SYNTHESIZING, language)
        }

        fun playing() = _status.update { it.copy(phase = SpeakPhase.PLAYING) }

        fun finish() = _status.update { it.copy(phase = SpeakPhase.IDLE, message = "") }

        fun fail(message: String) = _status.update { it.copy(phase = SpeakPhase.FAILED, message = message) }

        override fun stop() {
            stopped++
            _status.value = SpeakStatus()
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeTranslator(
        private val result: Result<String>,
    ) : Translator {
        var calls = 0

        override suspend fun toEnglish(text: String): Result<String> {
            calls++
            return result
        }
    }
}
