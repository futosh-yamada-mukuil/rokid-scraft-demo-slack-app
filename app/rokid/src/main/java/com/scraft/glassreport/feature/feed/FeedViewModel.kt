package com.scraft.glassreport.feature.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scraft.glassreport.capture.tts.SpeakPhase
import com.scraft.glassreport.capture.tts.Speaker
import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.core.sound.Chime
import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.data.repo.refreshes
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.model.TranslationState
import com.scraft.glassreport.domain.text.RelativeTime
import com.scraft.glassreport.domain.translate.Translator
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "FeedViewModel"

/**
 * 自動更新の間隔。お客様仕様は「指定間隔（例：30秒）で自動更新」＝**可変である**ことが前提なので、
 * 後で設定画面（Phase 5 の設定QR）から差し替えられるよう定数と引数に出しておく。
 */
const val FEED_REFRESH_INTERVAL_MS = 30_000L

/** 読み上げの進み具合。画面に出す文言はこれで決まる。 */
enum class FeedSpeech { NONE, TRANSLATING, SPEAKING, FAILED }

/**
 * Feed 画面の状態。**表示項目は投稿者・本文・経過時間だけ**（お客様仕様）。
 *
 * 経過時間は「取得した時刻（[nowMs]）から見て何分前か」で出す。更新のたびに [nowMs] が進むので、
 * 画面を開いたままでも 30 秒ごとに表示が追従する。
 */
data class FeedUiState(
    val posts: List<FeedPost> = emptyList(),
    val index: Int = 0,
    /** 経過時間の基準時刻。更新のたびに進める。 */
    val nowMs: Long = 0L,
    /** 一度でも取得に成功したか（未取得と「0 件」を区別するため）。 */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** 直近の失敗理由。成功したら消える。 */
    val error: String = "",
    /** 新着として強調表示する投稿の id。操作すると消える。 */
    val highlightedId: String? = null,
    val menuOpen: Boolean = false,
    val speech: FeedSpeech = FeedSpeech.NONE,
    /** 読み上げ・翻訳の状況表示（「読み上げ中…」など）。 */
    val speechMessage: String = "",
    /** サインインが切れている。画面はこれを見て認証画面へ送る。 */
    val signInRequired: Boolean = false,
) {
    val current: FeedPost? get() = posts.getOrNull(index)

    /** 「3分前」。時刻が読めなかった投稿では空。 */
    val elapsed: String get() = current?.createdAtMs?.let { RelativeTime.format(it, nowMs) }.orEmpty()

    /** 「2 / 5」。1 件しかないときは出さない。 */
    val position: String get() = if (posts.size <= 1) "" else "${index + 1} / ${posts.size}"

    /** 表示中の投稿が新着か（強調表示する）。 */
    val highlighted: Boolean get() = highlightedId != null && current?.id == highlightedId

    /** 翻訳中または読み上げ中。 */
    val speaking: Boolean get() = speech == FeedSpeech.TRANSLATING || speech == FeedSpeech.SPEAKING

    /** 添付の件数表示（「画像 1 件」）。無ければ空。単眼低解像度では読めないので画像自体は出さない。 */
    val attachments: String get() =
        current
            ?.attachmentCount
            ?.takeIf { it > 0 }
            ?.let { "画像 $it 件" }
            .orEmpty()
}

/**
 * Feed（受信側）のロジック。
 *
 * - [poll] を **`Lifecycle.repeatOnLifecycle(STARTED)` の中**で回す。
 *   近接センサーで画面が消えている間は STARTED を外れるので、更新は自然に止まる
 *   （未装着のグラスがトークンとパケットを浪費しない）。
 * - 新着（前回まで見ていない id）を検知したら**通知音を鳴らし、その投稿へ移動して強調**する。
 * - 読み上げは [Speaker]、英訳は [Translator]。どちらもインターフェースなのでテストではフェイクを注入する。
 *
 * @param intervalMs 自動更新の間隔。既定は [FEED_REFRESH_INTERVAL_MS]。
 * @param clock 経過時間の基準に使う現在時刻。テストで固定するための口。
 */
class FeedViewModel(
    private val repository: FeedRepository,
    private val speaker: Speaker,
    private val translator: Translator,
    private val chime: Chime,
    private val intervalMs: Long = FEED_REFRESH_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _ui = MutableStateFlow(FeedUiState(nowMs = clock()))
    val ui: StateFlow<FeedUiState> = _ui.asStateFlow()

    /** 一度でも見た投稿の id。初回の取得で全部ここに入るため、起動時に通知音は鳴らない。 */
    private val seenIds = mutableSetOf<String>()

    /** 初回の取得を終えたか。**空のチャネルから始めても次の投稿を新着として鳴らす**ために別に持つ。 */
    private var firstLoadDone = false

    private var translateJob: Job? = null

    /** 英訳に失敗して日本語のまま読み上げているか。読み上げ中の文言に添える。 */
    private var translationFailed = false

    init {
        // 合成・再生の進み具合は Speaker が持つので、そのまま画面の文言に写す。
        viewModelScope.launch {
            speaker.status.collect { status ->
                _ui.update { ui ->
                    when (status.phase) {
                        SpeakPhase.SYNTHESIZING -> ui.copy(speech = FeedSpeech.SPEAKING, speechMessage = speakingMessage("音声を準備中…"))
                        SpeakPhase.PLAYING -> ui.copy(speech = FeedSpeech.SPEAKING, speechMessage = speakingMessage("読み上げ中…"))
                        SpeakPhase.FAILED ->
                            ui.copy(
                                speech = FeedSpeech.FAILED,
                                speechMessage = status.message.ifBlank { "読み上げできませんでした" },
                            )
                        SpeakPhase.IDLE ->
                            if (ui.speech == FeedSpeech.SPEAKING) ui.copy(speech = FeedSpeech.NONE, speechMessage = "") else ui
                    }
                }
            }
        }
    }

    /**
     * 画面が見えている間だけ回す自動更新。**呼び出し側の取り消しで止まる**（正常終了はしない）。
     * 最初の 1 回はすぐ取りに行き、以降は [intervalMs] ごと。
     */
    suspend fun poll() {
        repository.refreshes(intervalMs).collect {
            refresh()
        }
    }

    /** 1 回だけ取り直す。 */
    suspend fun refresh() {
        _ui.update { it.copy(loading = true) }
        repository.latest().fold(
            onSuccess = { posts -> onLoaded(posts) },
            onFailure = { error -> onFailed(error) },
        )
    }

    /** 前スワイプ：1 つ前（新しい方）の投稿へ。 */
    fun previousPost() = movePost(-1)

    /** 後スワイプ：1 つ次（古い方）の投稿へ。 */
    fun nextPost() = movePost(1)

    /**
     * タップ。読み上げ中なら止める、そうでなければアクションメニューを開く
     * （区別できる操作が 3 つしか無いので、1 つのタップに 2 役を持たせる。外部設計書 4 章）。
     */
    fun onTap() {
        if (_ui.value.speaking) {
            stopSpeaking()
        } else {
            openMenu()
        }
    }

    fun openMenu() {
        _ui.update { it.copy(menuOpen = true, highlightedId = null) }
    }

    fun closeMenu() {
        _ui.update { it.copy(menuOpen = false) }
    }

    /** Read the displayed translation in its actual language. Name retained for compatibility. */
    fun speakJapanese() {
        val text = startSpeaking() ?: return
        val post = _ui.value.current ?: return
        if (!post.canSpeak) {
            _ui.update { it.copy(speech = FeedSpeech.FAILED, speechMessage = "翻訳未完了のため読み上げできません") }
            return
        }
        _ui.update { it.copy(speech = FeedSpeech.SPEAKING, speechMessage = "読み上げ中…") }
        speaker.speak(text, post.language)
    }

    /**
     * 選択中の投稿を英訳してから読み上げる。
     * **訳せなければ日本語のまま読み上げる**（外部設計書 6 章のフォールバック）。
     */
    fun speakEnglish() {
        val text = startSpeaking() ?: return
        _ui.update { it.copy(speech = FeedSpeech.TRANSLATING, speechMessage = "英訳中…") }
        translateJob =
            viewModelScope.launch {
                translator.toEnglish(text).fold(
                    onSuccess = { english -> speaker.speak(english, SpeechLanguage.EN) },
                    onFailure = { error ->
                        Log.w(TAG, "英訳に失敗", error)
                        val post = _ui.value.current
                        if (post?.canSpeak == true) {
                            translationFailed = true
                            speaker.speak(text, post.language)
                        } else {
                            _ui.update { it.copy(speech = FeedSpeech.FAILED, speechMessage = "翻訳できませんでした") }
                        }
                    },
                )
            }
    }

    /** 読み上げ（と翻訳）を打ち切る。 */
    fun stopSpeaking() {
        translateJob?.cancel()
        translationFailed = false
        speaker.stop()
        _ui.update { it.copy(speech = FeedSpeech.NONE, speechMessage = "") }
    }

    /**
     * 読み上げ開始の共通処理。メニューを閉じ、前の読み上げを片付けて本文を返す。
     * 読み上げられる本文が無ければ理由を出して null。
     */
    private fun startSpeaking(): String? {
        translateJob?.cancel()
        translationFailed = false
        val text = readableText()
        if (text == null) {
            _ui.update { it.copy(menuOpen = false, speech = FeedSpeech.FAILED, speechMessage = "読み上げる本文がありません") }
            return null
        }
        _ui.update { it.copy(menuOpen = false) }
        return text
    }

    /** 英訳に失敗して日本語に落ちた場合は、読み上げ中の表示にそのことを添える。 */
    private fun speakingMessage(base: String): String = if (translationFailed) "$base（英訳できず）" else base

    /** 認証画面へ送ったあとにフラグを落とす（戻ってきたときに二重で遷移しないように）。 */
    fun onSignInHandled() {
        _ui.update { it.copy(signInRequired = false) }
    }

    /** 読み上げ対象の本文。投稿が無い・本文が空（写真だけ）のときは読み上げない。 */
    private fun readableText(): String? =
        _ui.value.current
            ?.text
            ?.takeIf { it.isNotBlank() }

    private fun movePost(step: Int) {
        val ui = _ui.value
        if (ui.posts.isEmpty()) return
        if (ui.speaking) stopSpeaking()
        _ui.update {
            it.copy(
                index = (it.index + step + it.posts.size) % it.posts.size,
                nowMs = clock(),
                highlightedId = null,
            )
        }
    }

    /**
     * 取得できた投稿を反映する。
     *
     * - 新着があれば通知音＋先頭へ移動＋強調
     * - 新着が無ければ**見ていた投稿に留まる**（読んでいる最中に表示が飛ばないように）
     */
    private fun onLoaded(posts: List<FeedPost>) {
        val firstLoad = !firstLoadDone
        firstLoadDone = true
        val eligible = if (firstLoad) posts else posts.filter { it.translation != TranslationState.PENDING }
        val arrivals = eligible.filterNot { it.id in seenIds }
        seenIds += eligible.map { it.id }

        val viewingId = _ui.value.current?.id
        // 初回の取得は「全部が新着」になるが、起動しただけで鳴らすのは煩いので鳴らさない。
        val newest = arrivals.firstOrNull()?.takeIf { !firstLoad }
        if (newest != null) {
            Log.i(TAG, "新着 ${arrivals.size} 件")
            chime.play()
        }
        val index =
            when {
                newest != null -> posts.indexOfFirst { it.id == newest.id }.coerceAtLeast(0)
                else -> posts.indexOfFirst { it.id == viewingId }.coerceAtLeast(0)
            }

        _ui.update {
            it.copy(
                posts = posts,
                index = index,
                nowMs = clock(),
                loaded = true,
                loading = false,
                error = "",
                highlightedId = newest?.id ?: it.highlightedId?.takeIf { id -> posts.any { post -> post.id == id } },
                signInRequired = false,
            )
        }
    }

    /** 失敗しても**表示中の投稿は消さない**（通信が一瞬切れただけで画面が空になると使い物にならない）。 */
    private fun onFailed(error: Throwable) {
        Log.w(TAG, "取得に失敗", error)
        _ui.update {
            it.copy(
                loading = false,
                error = error.message ?: "更新できませんでした",
                signInRequired = error is SignInRequiredException,
            )
        }
    }

    override fun onCleared() {
        speaker.close()
        chime.close()
        super.onCleared()
    }
}
