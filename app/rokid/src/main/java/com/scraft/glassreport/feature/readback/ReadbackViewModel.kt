package com.scraft.glassreport.feature.readback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scraft.glassreport.capture.tts.SpeakPhase
import com.scraft.glassreport.capture.tts.Speaker
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.domain.translate.Translator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 読み上げ確認に使う固定文。Phase 3（Feed）が入るまでは「返信」が存在しないため、
 * 現場から返ってきそうな短文をここに持つ。
 */
val SAMPLE_REPLIES =
    listOf(
        "了解しました。すぐに向かいます。",
        "配管の水漏れは応急処置しました。部品の到着は明日です。",
        "写真を確認しました。バルブを閉じてから作業してください。",
    )

/** 読み上げ確認画面の進み具合。 */
enum class ReadbackPhase { IDLE, TRANSLATING, SPEAKING, DONE, FAILED }

/** 読み上げ確認画面の状態。 */
data class ReadbackUiState(
    val source: String = SAMPLE_REPLIES.first(),
    /** 英訳（まだ訳していなければ空）。 */
    val translated: String = "",
    val phase: ReadbackPhase = ReadbackPhase.IDLE,
    val status: String = "待機中",
    val detail: String = "",
) {
    /** 翻訳中・読み上げ中は次の操作を受け付けない。 */
    val busy: Boolean get() = phase == ReadbackPhase.TRANSLATING || phase == ReadbackPhase.SPEAKING
}

/**
 * 読み上げ（日本語）と英訳読み上げの確認画面のロジック。
 *
 * Phase 3（Feed）が入ったら、[SAMPLE_REPLIES] の代わりに選択中の投稿本文を渡すだけで
 * そのまま「読み上げ」「英語で読み上げ」のメニュー動作になる。
 * [speaker] と [translator] はインターフェースなので、テストではフェイクを注入する。
 */
class ReadbackViewModel(
    private val speaker: Speaker,
    private val translator: Translator,
) : ViewModel() {
    private val _ui = MutableStateFlow(ReadbackUiState())
    val ui: StateFlow<ReadbackUiState> = _ui.asStateFlow()

    private var sampleIndex = 0
    private var translateJob: Job? = null

    init {
        // 合成・再生の進み具合は Speaker が持つので、そのまま HUD の文言に写す。
        viewModelScope.launch {
            speaker.status.collect { status ->
                _ui.update { ui ->
                    when (status.phase) {
                        SpeakPhase.SYNTHESIZING ->
                            ui.copy(
                                phase = ReadbackPhase.SPEAKING,
                                status = "音声を合成中",
                                detail = status.language.label,
                            )
                        SpeakPhase.PLAYING ->
                            ui.copy(
                                phase = ReadbackPhase.SPEAKING,
                                status = "読み上げ中",
                                detail = status.language.label,
                            )
                        SpeakPhase.FAILED ->
                            ui.copy(
                                phase = ReadbackPhase.FAILED,
                                status = "読み上げ失敗",
                                detail = status.message,
                            )
                        SpeakPhase.IDLE ->
                            if (ui.phase == ReadbackPhase.SPEAKING) {
                                ui.copy(phase = ReadbackPhase.DONE, status = "読み上げ完了", detail = "")
                            } else {
                                ui
                            }
                    }
                }
            }
        }
    }

    /** 原文（日本語）をそのまま読み上げる。 */
    fun speakJapanese() {
        if (_ui.value.busy) return
        translateJob?.cancel()
        speaker.speak(_ui.value.source, SpeechLanguage.JA)
    }

    /** 英訳してから読み上げる。訳せなかったら読み上げず HUD に理由を出す。 */
    fun speakEnglish() {
        if (_ui.value.busy) return
        translateJob?.cancel()
        val source = _ui.value.source
        _ui.update { it.copy(phase = ReadbackPhase.TRANSLATING, status = "英訳中", detail = "", translated = "") }
        translateJob =
            viewModelScope.launch {
                translator.toEnglish(source).fold(
                    onSuccess = { english ->
                        _ui.update { it.copy(translated = english) }
                        speaker.speak(english, SpeechLanguage.EN)
                    },
                    onFailure = { error ->
                        _ui.update {
                            it.copy(
                                phase = ReadbackPhase.FAILED,
                                status = "英訳失敗",
                                detail = error.message ?: "原因不明",
                            )
                        }
                    },
                )
            }
    }

    /** 確認用の固定文を次のものへ。読み上げ中なら止める。 */
    fun nextSample() {
        translateJob?.cancel()
        speaker.stop()
        sampleIndex = (sampleIndex + 1) % SAMPLE_REPLIES.size
        _ui.value =
            ReadbackUiState(
                source = SAMPLE_REPLIES[sampleIndex],
                status = "待機中",
            )
    }

    // ViewModel.onCleared() の既定実装は空（@EmptySuper）なので super は呼ばない。
    override fun onCleared() {
        speaker.close()
    }
}
