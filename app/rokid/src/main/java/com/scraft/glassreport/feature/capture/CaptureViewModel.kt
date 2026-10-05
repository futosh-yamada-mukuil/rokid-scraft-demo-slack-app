package com.scraft.glassreport.feature.capture

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scraft.glassreport.capture.asr.NoopSpeechSource
import com.scraft.glassreport.capture.asr.RecordedVoice
import com.scraft.glassreport.capture.asr.SpeechRecordingSession
import com.scraft.glassreport.capture.asr.SpeechResult
import com.scraft.glassreport.capture.asr.SpeechSource
import com.scraft.glassreport.capture.asr.VOICE_DURATION_MS
import com.scraft.glassreport.capture.asr.joinRecordedVoice
import com.scraft.glassreport.capture.camera.PhotoSource
import com.scraft.glassreport.capture.camera.VideoSession
import com.scraft.glassreport.capture.camera.VideoSource
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MIME_MP4
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.NoMediaSink
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

private const val TAG = "CaptureViewModel"

/** 結果表示（送信しました／失敗／破棄）を出しておく時間。 */
private const val RESULT_HOLD_MS = 3_000L

/** 静止画撮影を待つ上限。実測は 1.5 秒前後なので十分な余裕がある。 */
private const val PHOTO_TIMEOUT_MS = 15_000L

/**
 * 録画停止（Finalize）と MP4 の読み出しを待つ上限。
 * 録画の長さには依存しない（長く録れば読み出しは伸びるので、余裕を持たせてある）。
 */
private const val VIDEO_FINALIZE_GRACE_MS = 60_000L

/** 音声コメント（録音＋認識）を待つ上限。 */
private const val VOICE_TIMEOUT_MS = 20_000L
private const val OPTIONAL_COMMENT_DURATION_MS = 5_000L

/**
 * 撮影モード。[mime] が宛先の受け入れ判定（[MediaSink.accepts]）に使われる。既定は写真。
 */
enum class CaptureMode(
    val label: String,
    val mime: String,
) {
    PHOTO("写真", MIME_JPEG),
    VIDEO("動画", MIME_MP4),
}

/**
 * 「何を撮って、どこへ送るか」の組。従来画面では前後スワイプで選ぶ。
 *
 * パッドで区別できる操作はタップ／前後スワイプの3つだけなので、モードと宛先を別の操作には割り当てない。
 * **動画 × Teams の組は作られない**（Teams の hostedContents は画像専用のため）。
 */
data class CaptureTarget(
    val mode: CaptureMode,
    val sink: MediaSink,
) {
    /** HUD 上段に出す「写真 → Teams」。 */
    val label: String get() = "${mode.label} → ${sink.label}"
}

/**
 * 撮影の進み具合。HUD の文言はこれから作る。操作（タップ／スワイプ）の意味もこれで変わる。
 *
 * ```
 * IDLE ─タップ→ CAPTURING ─→ COMMENT_CHOICE ─はい→ LISTENING ─→ TRANSCRIBING ─→ CONFIRM
 *                                     └いいえ────────────────────────────→ CONFIRM
 *   │                                                        └─いいえ→ DRAFT_REVIEW（保持）
 *   └─タップ→ RECORDING ─タップ/上限→ COMMENT_CHOICE
 * ```
 */
enum class CapturePhase {
    IDLE,
    CAPTURING,
    RECORDING,
    COMMENT_CHOICE,
    LISTENING,
    COMMENT_REVIEW,
    COMMENT_FAILED,
    TRANSCRIBING,
    CONFIRM,
    DRAFT_REVIEW,
    UPLOADING,
    DONE,
    FAILED,
}

/** 送信確認の選択。**誤送信を防ぐため既定は [NO]**。 */
enum class ConfirmChoice { YES, NO }

enum class CommentReviewChoice { RETAKE, CONFIRM, CANCEL }

enum class DraftReviewChoice { SEND, DISCARD }

enum class CommentFailureChoice { RETRY, CONTINUE }

/**
 * 撮影できたもの。送信するか破棄するかが決まるまで ViewModel が抱える。
 * [bytes] と [mime] はそのまま [MediaSink.send] へ渡せる形。
 */
data class CaptureDraft(
    val mode: CaptureMode,
    val bytes: ByteArray,
    val mime: String,
    /** 尺。動画は「5秒 / 1280x720 / 7.9MB」、写真は「960x1280 / 103KB」。 */
    val measure: String,
    /** 音声コメントの文字起こし。無ければ空文字。 */
    val comment: String = "",
    /** 従来のPC音声認識用。新しい任意コメントフローでは送らない。 */
    val voice: ByteArray? = null,
    val thumbnail: Bitmap? = null,
)

/** 撮影画面の状態。 */
data class CaptureUiState(
    val mode: CaptureMode = CaptureMode.PHOTO,
    /** 「写真 → モニタ」。撮影モードと送信先を1行で見せる。 */
    val target: String = "",
    val phase: CapturePhase = CapturePhase.IDLE,
    val status: String = "待機中",
    val detail: String = "",
    val host: String = "",
    val draft: CaptureDraft? = null,
    val confirm: ConfirmChoice = ConfirmChoice.NO,
    /** 撮影後に音声コメントを残すか。初期選択は「いいえ」。 */
    val commentChoice: ConfirmChoice = ConfirmChoice.NO,
    val commentReviewChoice: CommentReviewChoice = CommentReviewChoice.CONFIRM,
    val draftReviewChoice: DraftReviewChoice = DraftReviewChoice.SEND,
    val commentFailureChoice: CommentFailureChoice = CommentFailureChoice.RETRY,
    /** 確認ポップアップに出す警告（送信失敗の理由）。通常は空。 */
    val alert: String = "",
    /** Teams のサインインが切れている。画面はこれを見て認証画面へ送る。 */
    val signInRequired: Boolean = false,
) {
    /** 撮影・録音・送信の最中は新しい撮影を受け付けない。 */
    val busy: Boolean
        get() =
            phase == CapturePhase.CAPTURING ||
                phase == CapturePhase.RECORDING ||
                phase == CapturePhase.LISTENING ||
                phase == CapturePhase.TRANSCRIBING ||
                phase == CapturePhase.UPLOADING

    /** 送信確認のポップアップを出しているか。 */
    val confirming: Boolean
        get() = phase == CapturePhase.CONFIRM && draft != null

    val choosingComment: Boolean
        get() = phase == CapturePhase.COMMENT_CHOICE && draft != null
}

/**
 * 「写真または動画を撮る → 任意の音声コメント → サマリー確認 → 送信」を受け持つ。
 * [fixedMode] がある画面では撮影モードと宛先を設定から固定する。
 *
 * 操作はタップ／前スワイプ／後スワイプの3つだけなので、**意味は [CapturePhase] で変わる**。
 * - 待機中: スワイプ＝[CaptureTarget] の巡回、タップ＝撮影開始
 * - 録画中: タップ＝停止（スワイプは無効）
 * - 確認中: スワイプ＝はい／いいえ移動、タップ＝決定、BACK＝撮影内容を保持
 *
 * 宛先は [MediaSink] 越しにしか触らないので、受信モニタと Teams で呼び出し口は同じ。
 * **送信は [upload] の1か所だけ**で、選択中の [CaptureTarget] の宛先へ送る。
 */
class CaptureViewModel(
    sinks: List<MediaSink>,
    private val speech: SpeechSource = NoopSpeechSource,
    defaultSink: String = "",
    private val fixedMode: CaptureMode? = null,
    private val recordVoiceComment: Boolean = true,
    private val askCommentAfterCapture: Boolean = false,
) : ViewModel() {
    /** 撮影モード × 受け入れ可能な宛先。写真が先、動画が後（既定は「写真 → 先頭の宛先」）。 */
    private val targets: List<CaptureTarget> =
        (fixedMode?.let(::listOf) ?: CaptureMode.entries)
            .flatMap { mode ->
                sinks
                    .filter { it.accepts(mode.mime) && (fixedMode == null || it.label == defaultSink) }
                    .map { CaptureTarget(mode, it) }
            }.ifEmpty { listOf(CaptureTarget(fixedMode ?: CaptureMode.PHOTO, NoMediaSink)) }

    /**
     * 開いた直後にどの組を選んでおくか。設定画面の「既定の送信先」に当たる最初の組で、
     * 見つからなければ先頭。**写真の組が先に並んでいるので、既定が Teams でも
     * 選ばれるのは「写真 → Teams」**（動画 × Teams はそもそも作られない）。
     */
    private var index = targets.indexOfFirst { it.sink.label == defaultSink }.coerceAtLeast(0)

    private val _ui = MutableStateFlow(stateFor(targets[index]))
    val ui: StateFlow<CaptureUiState> = _ui.asStateFlow()

    private var resetJob: Job? = null
    private var videoSession: VideoSession? = null
    private var commentAudioPermissionGranted: Boolean = false
    private var commentJob: Job? = null
    private var commentSession: SpeechRecordingSession? = null
    private var heldComment: RecordedVoice? = null
    private var commentGeneration = 0
    private var reviewRequested = false
    private var commentStopRequested = false

    // ── タッチパッド（3操作） ────────────────────────────────────────────────

    /** 前スワイプ。確認中は選択の移動、待機中は1つ前の「撮影モード × 送信先」へ。 */
    fun previousTarget() = onSwipe(-1)

    /** 後スワイプ。確認中は選択の移動、待機中は次の「撮影モード × 送信先」へ。 */
    fun nextTarget() = onSwipe(1)

    /**
     * タップ。待機中は撮影開始、録画中は停止、確認中は決定。
     * [photo] / [video] は今のモードで使える方だけが非 null（画面が CameraX の準備状況で決める）。
     */
    fun onTap(
        photo: PhotoSource?,
        video: VideoSource?,
        audioPermissionGranted: Boolean,
    ) {
        when (_ui.value.phase) {
            CapturePhase.RECORDING -> videoSession?.stop()
            CapturePhase.LISTENING -> stopCommentRecording()
            CapturePhase.COMMENT_REVIEW -> decideCommentReview(_ui.value.commentReviewChoice)
            CapturePhase.COMMENT_FAILED -> decideCommentFailure(_ui.value.commentFailureChoice)
            CapturePhase.COMMENT_CHOICE -> decideComment(_ui.value.commentChoice)
            CapturePhase.CONFIRM -> decide(_ui.value.confirm)
            CapturePhase.DRAFT_REVIEW -> decideDraftReview(_ui.value.draftReviewChoice)
            CapturePhase.IDLE, CapturePhase.DONE, CapturePhase.FAILED -> startCapture(photo, video, audioPermissionGranted)
            else -> Unit
        }
    }

    /** BACK。撮影後は内容を保持し、明示的な破棄以外で失わない。 */
    fun onBackPressed(): Boolean {
        when (_ui.value.phase) {
            CapturePhase.LISTENING -> {
                reviewCommentRecording()
                return true
            }
            CapturePhase.COMMENT_REVIEW -> {
                decideCommentReview(CommentReviewChoice.CANCEL)
                return true
            }
            CapturePhase.TRANSCRIBING -> {
                cancelCommentWork()
                _ui.value.draft?.let { confirmDraft(it.copy(comment = "", voice = null), "コメントなし") }
                return true
            }
            CapturePhase.DRAFT_REVIEW -> {
                _ui.update { it.copy(draftReviewChoice = DraftReviewChoice.SEND) }
                return true
            }
            CapturePhase.COMMENT_FAILED -> {
                retainDraft()
                return true
            }
            else -> Unit
        }
        if (_ui.value.phase != CapturePhase.CONFIRM && _ui.value.phase != CapturePhase.COMMENT_CHOICE) return false
        retainDraft()
        return true
    }

    /** 「はい」「いいえ」を直接選んで決定する（エミュのクリック用。パッドは [onTap] 経由）。 */
    fun decide(choice: ConfirmChoice) {
        val draft = _ui.value.draft ?: return
        if (_ui.value.phase != CapturePhase.CONFIRM) return
        _ui.update { it.copy(confirm = choice) }
        if (choice == ConfirmChoice.YES) upload(draft) else retainDraft()
    }

    fun decideDraftReview(choice: DraftReviewChoice) {
        val draft = _ui.value.draft ?: return
        if (_ui.value.phase != CapturePhase.DRAFT_REVIEW) return
        if (choice == DraftReviewChoice.SEND) confirmDraft(draft, _ui.value.detail) else discard()
    }

    private fun retainDraft() {
        if (_ui.value.draft == null) return
        _ui.update {
            it.copy(
                phase = CapturePhase.DRAFT_REVIEW,
                status = "撮影内容を保持しています",
                draftReviewChoice = DraftReviewChoice.SEND,
            )
        }
    }

    /** 撮影後の「コメントを残しますか？」。YES は録音・文字起こし、NO は送信確認へ。 */
    fun decideComment(choice: ConfirmChoice) {
        val draft = _ui.value.draft ?: return
        if (_ui.value.phase != CapturePhase.COMMENT_CHOICE) return
        _ui.update { it.copy(commentChoice = choice, alert = "") }
        if (choice == ConfirmChoice.NO) {
            confirmDraft(draft, "コメントなし")
            return
        }
        if (!commentAudioPermissionGranted || !speech.available) {
            val reason = if (!commentAudioPermissionGranted) "マイク権限がありません" else speech.unavailableReason
            finishComment(draft, CommentOutcome("", reason, failed = true))
            return
        }
        startOptionalComment(draft)
    }

    fun decideCommentFailure(choice: CommentFailureChoice) {
        if (_ui.value.phase != CapturePhase.COMMENT_FAILED) return
        val draft = _ui.value.draft ?: return
        if (choice == CommentFailureChoice.CONTINUE) {
            confirmDraft(draft.copy(voice = null), "コメントの録音をスキップしました")
        } else if (!commentAudioPermissionGranted || !speech.available) {
            val reason = if (!commentAudioPermissionGranted) "マイク権限がありません" else speech.unavailableReason
            finishComment(draft, CommentOutcome("", reason, failed = true))
        } else {
            startOptionalComment(draft)
        }
    }

    /** Single-tap stops early and transcribes; review is requested separately by the double-tap arbiter. */
    private fun stopCommentRecording() {
        if (_ui.value.phase != CapturePhase.LISTENING || commentSession == null || reviewRequested) return
        commentStopRequested = true
        commentSession?.stop()
        _ui.update { it.copy(status = "録音を停止中", detail = "文字起こしへ進みます") }
    }

    fun reviewCommentRecording() {
        if (_ui.value.phase != CapturePhase.LISTENING || commentSession == null) return
        reviewRequested = true
        commentStopRequested = true
        commentSession?.stop()
        _ui.update { it.copy(status = "録音を停止中", detail = "録音内容を保持します") }
    }

    fun decideCommentReview(choice: CommentReviewChoice) {
        if (_ui.value.phase != CapturePhase.COMMENT_REVIEW) return
        val draft = _ui.value.draft ?: return
        val voice = heldComment ?: return
        when (choice) {
            CommentReviewChoice.RETAKE -> startOptionalComment(draft)
            CommentReviewChoice.CANCEL -> startOptionalComment(draft, voice)
            CommentReviewChoice.CONFIRM -> {
                heldComment = null
                _ui.update { it.copy(phase = CapturePhase.TRANSCRIBING, status = "文字起こし中", detail = "") }
                val generation = commentGeneration
                commentJob = viewModelScope.launch { transcribeComment(draft, voice, generation) }
            }
        }
    }

    private fun cancelCommentWork() {
        commentGeneration++
        runCatching { commentSession?.stop() }
        commentSession = null
        commentJob?.cancel()
        commentJob = null
        heldComment = null
        reviewRequested = false
        commentStopRequested = false
    }

    private fun startOptionalComment(
        draft: CaptureDraft,
        prefix: RecordedVoice? = null,
    ) {
        cancelCommentWork()
        val generation = commentGeneration
        val remainingMs = (OPTIONAL_COMMENT_DURATION_MS - (prefix?.durationMs ?: 0L)).coerceAtLeast(0L)
        if (remainingMs == 0L && prefix != null) {
            _ui.update { it.copy(phase = CapturePhase.TRANSCRIBING, status = "文字起こし中", detail = "") }
            commentJob = viewModelScope.launch { transcribeComment(draft, prefix, generation) }
            return
        }
        val session =
            try {
                speech.beginRecording(remainingMs)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                finishComment(draft, CommentOutcome("", "録音開始失敗: ${error.message?.take(30) ?: "原因不明"}", failed = true))
                return
            }
        commentSession = session
        _ui.update { it.copy(phase = CapturePhase.LISTENING, status = "録音中…", detail = "タップで終了・ダブルタップで録音メニュー") }
        if (session == null) {
            // Compatibility for older SpeechSource implementations without separately stoppable recording.
            commentJob =
                viewModelScope.launch {
                    val comment = recordComment(commentAudioPermissionGranted)
                    coroutineContext.ensureActive()
                    if (generation == commentGeneration) finishComment(draft, comment)
                }
            return
        }
        commentJob =
            viewModelScope.launch {
                try {
                    val voice =
                        coroutineScope {
                            val countdown =
                                launch {
                                    var remaining = (remainingMs + 999L) / 1000L
                                    while (remaining > 0L && !commentStopRequested) {
                                        _ui.update { it.copy(status = "録音中 ${remaining}秒…") }
                                        delay(1000L)
                                        remaining--
                                    }
                                }
                            try {
                                withTimeout(VOICE_TIMEOUT_MS) { session.await().getOrThrow() }
                            } finally {
                                countdown.cancel()
                            }
                        }
                    coroutineContext.ensureActive()
                    if (generation != commentGeneration) return@launch
                    commentSession = null
                    val combined = joinRecordedVoice(prefix, voice, OPTIONAL_COMMENT_DURATION_MS)
                    if (reviewRequested) {
                        heldComment = combined
                        _ui.update {
                            it.copy(
                                phase = CapturePhase.COMMENT_REVIEW,
                                status = "録音をどうしますか？",
                                detail = "録音 ${seconds(combined.durationMs)}",
                                commentReviewChoice = CommentReviewChoice.CONFIRM,
                            )
                        }
                    } else {
                        transcribeComment(draft, combined, generation)
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    if (generation == commentGeneration) {
                        runCatching { commentSession?.stop() }
                        commentSession = null
                        finishComment(draft, CommentOutcome("", "録音失敗: ${error.message?.take(30) ?: "原因不明"}", failed = true))
                    }
                }
            }
    }

    private suspend fun transcribeComment(
        draft: CaptureDraft,
        voice: RecordedVoice,
        generation: Int,
    ) {
        _ui.update { it.copy(phase = CapturePhase.TRANSCRIBING, status = "文字起こし中", detail = "") }
        val result =
            try {
                withTimeout(VOICE_TIMEOUT_MS) { speech.transcribe(voice) }
            } catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                Result.failure(error)
            }
        coroutineContext.ensureActive()
        if (generation == commentGeneration) finishComment(draft, commentOutcome(result))
    }

    private fun finishComment(
        draft: CaptureDraft,
        comment: CommentOutcome,
    ) {
        heldComment = null
        if (comment.failed) {
            _ui.update {
                it.copy(
                    phase = CapturePhase.COMMENT_FAILED,
                    status = "音声入力に失敗しました",
                    detail = comment.note,
                    draft = draft,
                    alert = "写真は保持しています",
                    commentFailureChoice = CommentFailureChoice.RETRY,
                )
            }
            return
        }
        val note = if (comment.text.isBlank()) "コメントなし（${comment.note}）" else "文字起こし: ${comment.text}"
        confirmDraft(
            draft.copy(comment = comment.text, voice = if (askCommentAfterCapture) null else comment.voice),
            if (askCommentAfterCapture) note else comment.note,
        )
    }

    /** 認証画面へ送ったあとにフラグを落とす（戻ってきたときに二重で遷移しないように）。 */
    fun onSignInHandled() {
        _ui.update { it.copy(signInRequired = false) }
    }

    fun onCameraError(error: Throwable) {
        Log.w(TAG, "カメラのバインドに失敗", error)
        // 確認中・送信中に来た再バインドの失敗で、撮ったものを捨てさせない。
        if (_ui.value.phase == CapturePhase.COMMENT_CHOICE ||
            _ui.value.phase == CapturePhase.LISTENING ||
            _ui.value.phase == CapturePhase.COMMENT_REVIEW ||
            _ui.value.phase == CapturePhase.COMMENT_FAILED ||
            _ui.value.phase == CapturePhase.TRANSCRIBING ||
            _ui.value.phase == CapturePhase.CONFIRM ||
            _ui.value.phase == CapturePhase.DRAFT_REVIEW ||
            _ui.value.phase == CapturePhase.UPLOADING
        ) {
            return
        }
        fail("撮影失敗: カメラエラー", error.message.orEmpty().take(40))
    }

    override fun onCleared() {
        cancelCommentWork()
        videoSession?.stop()
        speech.close()
    }

    // ── 撮影モード × 送信先の巡回 ───────────────────────────────────────────

    /** スワイプの意味は状態で変わる。確認中は「はい／いいえ」、待機中は宛先の巡回。 */
    private fun onSwipe(step: Int) {
        when (_ui.value.phase) {
            CapturePhase.COMMENT_FAILED ->
                _ui.update {
                    it.copy(
                        commentFailureChoice =
                            if (it.commentFailureChoice == CommentFailureChoice.RETRY) {
                                CommentFailureChoice.CONTINUE
                            } else {
                                CommentFailureChoice.RETRY
                            },
                    )
                }
            CapturePhase.COMMENT_CHOICE -> _ui.update { it.copy(commentChoice = it.commentChoice.other()) }
            CapturePhase.COMMENT_REVIEW ->
                _ui.update {
                    it.copy(
                        commentReviewChoice =
                            CommentReviewChoice.entries[
                                (
                                    it.commentReviewChoice.ordinal +
                                        step +
                                        CommentReviewChoice.entries.size
                                ) %
                                    CommentReviewChoice.entries.size,
                            ],
                    )
                }
            CapturePhase.CONFIRM -> _ui.update { it.copy(confirm = it.confirm.other()) }
            CapturePhase.DRAFT_REVIEW ->
                _ui.update {
                    it.copy(
                        draftReviewChoice =
                            if (it.draftReviewChoice == DraftReviewChoice.SEND) DraftReviewChoice.DISCARD else DraftReviewChoice.SEND,
                    )
                }
            CapturePhase.IDLE, CapturePhase.DONE, CapturePhase.FAILED -> if (fixedMode == null) moveTarget(step)
            else -> Unit
        }
    }

    private fun moveTarget(step: Int) {
        if (targets.size <= 1) return
        resetJob?.cancel()
        index = (index + step + targets.size) % targets.size
        _ui.value = stateFor(targets[index])
    }

    private fun stateFor(target: CaptureTarget) =
        CaptureUiState(
            mode = target.mode,
            target = target.label,
            host = target.sink.detail,
        )

    // ── 撮影 ────────────────────────────────────────────────────────────────

    private fun startCapture(
        photo: PhotoSource?,
        video: VideoSource?,
        audioPermissionGranted: Boolean,
    ) {
        resetJob?.cancel()
        commentAudioPermissionGranted = audioPermissionGranted
        when (_ui.value.mode) {
            CaptureMode.PHOTO -> capturePhoto(photo, audioPermissionGranted)
            CaptureMode.VIDEO -> captureVideo(video)
        }
    }

    /** 写真: シャッター → 音声コメント（3秒）→ 文字起こし → 確認。 */
    private fun capturePhoto(
        source: PhotoSource?,
        audioPermissionGranted: Boolean,
    ) {
        if (source == null) {
            fail("撮影失敗: カメラ未準備")
            return
        }
        commentJob =
            viewModelScope.launch {
                val startedAt = SystemClock.elapsedRealtime()
                _ui.update { it.copy(phase = CapturePhase.CAPTURING, status = "撮影中", detail = "", draft = null) }

                // CameraX の撮影要求が返ってこないことがある（画面 OFF 中に投げた場合など）。
                // 待ち続けると HUD が「撮影中」で固まり以降のタップも効かなくなるので、必ず打ち切る。
                val photo =
                    runCatching { withTimeout(PHOTO_TIMEOUT_MS) { source.takePhoto() } }
                        .onFailure { Log.w(TAG, "撮影に失敗", it) }
                        .getOrElse {
                            fail("撮影失敗: ${reason(it, "撮影タイムアウト", "撮影エラー")}", it.message.orEmpty().take(40))
                            return@launch
                        }

                if (askCommentAfterCapture) {
                    offerComment(
                        CaptureDraft(
                            mode = CaptureMode.PHOTO,
                            bytes = photo.jpeg,
                            mime = MIME_JPEG,
                            measure = "${photo.width}x${photo.height} / ${formatBytes(photo.jpeg.size)}",
                            thumbnail = photo.thumbnail,
                        ),
                    )
                    return@launch
                }

                val draft =
                    CaptureDraft(
                        mode = CaptureMode.PHOTO,
                        bytes = photo.jpeg,
                        mime = MIME_JPEG,
                        measure = "${photo.width}x${photo.height} / ${formatBytes(photo.jpeg.size)}",
                        thumbnail = photo.thumbnail,
                    )
                commentAudioPermissionGranted = audioPermissionGranted
                _ui.update { it.copy(draft = draft) }
                val comment = if (recordVoiceComment) recordComment(audioPermissionGranted) else CommentOutcome("", "")
                Log.i(
                    TAG,
                    "写真 ${photo.width}x${photo.height} ${photo.jpeg.size}B " +
                        "撮影〜確認 ${SystemClock.elapsedRealtime() - startedAt}ms コメント${comment.text.length}文字",
                )
                finishComment(draft, comment)
            }
    }

    /**
     * 録画: 1回目のタップで開始し、2回目のタップまたは端末の安全監視で停止。
     * 新フローでは動画本体を無音で録り、撮影後に音声コメントを選べる。
     */
    private fun captureVideo(source: VideoSource?) {
        if (source == null) {
            fail("撮影失敗: カメラ未準備")
            return
        }
        // 開始は同期的に。セッションを先に持ってから RECORDING にすることで、
        // 直後のタップ（停止）が空振りしないようにする。
        val session =
            runCatching { source.startRecording() }
                .onFailure { Log.w(TAG, "録画を開始できません", it) }
                .getOrElse {
                    fail("撮影失敗: 録画エラー", it.message.orEmpty().take(40))
                    return
                }
        videoSession = session
        _ui.update {
            it.copy(phase = CapturePhase.RECORDING, status = "録画中 0秒", detail = recordingHint(), draft = null)
        }

        viewModelScope.launch {
            val ticker =
                launch {
                    var elapsed = 1
                    while (true) {
                        delay(1_000L)
                        _ui.update { it.copy(phase = CapturePhase.RECORDING, status = "録画中 ${elapsed}秒", detail = recordingHint()) }
                        elapsed++
                    }
                }
            // 録画時間に固定上限はない（実機側の低電池・低メモリ監視か利用者の停止で止まる）。
            // ただし**停止後の待ちには必ず上限を掛ける**。Finalize イベントが来ないと
            // 「処理中」のまま永久に固まり、画面から抜ける手段が無くなるため。
            // この猶予は録画の長さではなく「停止処理＋MP4 の読み出し」に掛かる時間で決まる。
            val video =
                runCatching { withTimeout(VIDEO_FINALIZE_GRACE_MS) { session.await() } }
                    .onFailure { Log.w(TAG, "録画に失敗", it) }
                    .also {
                        ticker.cancel()
                        videoSession = null
                    }.getOrElse {
                        fail("撮影失敗: ${reason(it, "録画タイムアウト", "録画エラー")}", it.message.orEmpty().take(40))
                        return@launch
                    }

            Log.i(
                TAG,
                "録画 ${video.width}x${video.height} ${video.durationMs}ms ${video.bytes.size}B audio=${video.hasAudioTrack}",
            )
            val draft =
                CaptureDraft(
                    mode = CaptureMode.VIDEO,
                    bytes = video.bytes,
                    mime = MIME_MP4,
                    measure =
                        "${duration(video.durationMs)} / ${video.width}x${video.height} / " +
                            formatBytes(video.bytes.size),
                    thumbnail = video.thumbnail,
                )
            if (askCommentAfterCapture) {
                offerComment(draft)
            } else {
                confirmDraft(draft, if (video.hasAudioTrack) "音声付き動画" else "動画を撮影しました")
            }
        }
    }

    private fun recordingHint(): String = "タップで停止（電池10%・メモリ不足で自動停止）"

    private fun offerComment(draft: CaptureDraft) {
        _ui.update {
            it.copy(
                phase = CapturePhase.COMMENT_CHOICE,
                status = "コメントを残しますか？",
                detail = "はい: 5秒録音して文字起こし",
                draft = draft,
                commentChoice = ConfirmChoice.NO,
                alert = "",
            )
        }
    }

    // ── 音声コメント ────────────────────────────────────────────────────────

    /** 文字起こしの結果と、HUD に添える一言。 */
    private data class CommentOutcome(
        val text: String,
        val note: String,
        val voice: ByteArray? = null,
        val failed: Boolean = false,
    )

    /**
     * 3秒録って文字起こしする。**権限が無い／鍵が無い／失敗した場合もここで畳んで**
     * コメントなしで先へ進める（デモを止めない）。
     */
    private suspend fun recordComment(audioPermissionGranted: Boolean): CommentOutcome {
        if (!audioPermissionGranted) return CommentOutcome("", "マイク権限なし（コメントなし）")
        if (!speech.available) return CommentOutcome("", "${speech.unavailableReason}（コメントなし）")

        val result =
            coroutineScope {
                // 残り秒数の表示は動画と同じ作り。認識待ちに入ったら「文字起こし中」に切り替える。
                val countdown =
                    launch {
                        val durationMs = if (askCommentAfterCapture) OPTIONAL_COMMENT_DURATION_MS else VOICE_DURATION_MS
                        for (remaining in (durationMs / 1_000L).toInt() downTo 1) {
                            _ui.update {
                                it.copy(phase = CapturePhase.LISTENING, status = "録音中 ${remaining}秒…", detail = "コメントをどうぞ")
                            }
                            delay(1_000L)
                        }
                        _ui.update { it.copy(phase = CapturePhase.TRANSCRIBING, status = "文字起こし中", detail = "") }
                    }
                try {
                    withTimeout(VOICE_TIMEOUT_MS) { speech.listenOnce() }
                } catch (error: Throwable) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    Result.failure(error)
                } finally {
                    countdown.cancel()
                }
            }

        return commentOutcome(result)
    }

    private fun commentOutcome(result: Result<SpeechResult>): CommentOutcome =
        result.fold(
            onSuccess = { spoken ->
                Log.i(TAG, "文字起こし 録音${spoken.recordMs}ms 認識${spoken.recognizeMs}ms ${spoken.text.length}文字")
                when {
                    spoken.text.isNotBlank() -> CommentOutcome(spoken.text, "認識 ${seconds(spoken.recognizeMs)}", spoken.wav)
                    spoken.silent -> CommentOutcome("", "マイクが音を拾えませんでした", spoken.wav, failed = true)
                    spoken.wav != null ->
                        CommentOutcome(
                            "",
                            if (askCommentAfterCapture) "聞き取れませんでした" else "PCでAUTO認識します",
                            spoken.wav,
                            failed = askCommentAfterCapture,
                        )
                    else -> CommentOutcome("", "聞き取れませんでした", failed = true)
                }
            },
            onFailure = { error ->
                Log.w(TAG, "文字起こしに失敗", error)
                CommentOutcome("", "文字起こし失敗: ${error.message?.take(30) ?: "原因不明"}", failed = true)
            },
        )

    // ── 確認と送信 ──────────────────────────────────────────────────────────

    private fun confirmDraft(
        draft: CaptureDraft,
        note: String,
    ) {
        _ui.update {
            it.copy(
                phase = CapturePhase.CONFIRM,
                status = "送信の確認",
                detail = note,
                draft = draft,
                confirm = ConfirmChoice.NO,
                alert = "",
            )
        }
    }

    /**
     * **送信はここだけ**。宛先は選択中の [CaptureTarget]（受信モニタ / Teams）で、
     * [MediaSink] 越しにしか触らないので実体の違いはここに出てこない。
     */
    private fun upload(draft: CaptureDraft) {
        val sink = targets[index].sink
        viewModelScope.launch {
            _ui.update { it.copy(phase = CapturePhase.UPLOADING, status = "送信中", detail = draft.measure) }
            sink.send(draft.bytes, draft.mime, draft.comment, draft.voice).fold(
                onSuccess = { outcome ->
                    Log.i(TAG, "送信完了 宛先=${sink.label} bytes=${draft.bytes.size} 送信${outcome.elapsedMs}ms")
                    done("送信しました", "${draft.measure} / 送信${seconds(outcome.elapsedMs)}")
                },
                onFailure = { error ->
                    // 撮ったものは捨てず確認に戻す（タップで再送、いいえで保持）。
                    // サインイン切れだけは画面遷移が要るので、宛先固有の文言ではなく型で見分ける。
                    Log.w(TAG, "送信に失敗 宛先=${sink.label}", error)
                    _ui.update {
                        it.copy(
                            phase = CapturePhase.CONFIRM,
                            status = "送信に失敗しました",
                            detail = draft.measure,
                            confirm = ConfirmChoice.NO,
                            alert = "送信に失敗しました: ${error.message ?: "原因不明"}",
                            signInRequired = error is SignInRequiredException,
                        )
                    }
                },
            )
        }
    }

    private fun discard() {
        Log.i(TAG, "破棄しました")
        done("送信しませんでした", "破棄しました")
    }

    // ── HUD の後始末 ────────────────────────────────────────────────────────

    private fun done(
        status: String,
        detail: String = "",
    ) = finish(CapturePhase.DONE, status, detail)

    private fun fail(
        status: String,
        detail: String = "",
    ) = finish(CapturePhase.FAILED, status, detail)

    /** 結果を [RESULT_HOLD_MS] だけ出してから待機に戻す。抱えていた撮影物はここで手放す。 */
    private fun finish(
        phase: CapturePhase,
        status: String,
        detail: String,
    ) {
        _ui.update {
            it.copy(phase = phase, status = status, detail = detail, draft = null, confirm = ConfirmChoice.NO, alert = "")
        }
        resetJob?.cancel()
        resetJob =
            viewModelScope.launch {
                delay(RESULT_HOLD_MS)
                _ui.update { it.copy(phase = CapturePhase.IDLE, status = "待機中", detail = "") }
            }
    }

    private fun reason(
        error: Throwable,
        onTimeout: String,
        otherwise: String,
    ): String = if (error is TimeoutCancellationException) onTimeout else otherwise

    private fun seconds(ms: Long): String = "${ms / 1_000}.${(ms % 1_000) / 100}秒"

    private fun duration(ms: Long): String {
        val total = (ms + 500L) / 1_000L
        return if (total >= 60L) "${total / 60}分${total % 60}秒" else "${total}秒"
    }

    private fun formatBytes(bytes: Int): String =
        if (bytes >= 1_024 * 1_024) {
            "${bytes / (1_024 * 1_024)}.${(bytes % (1_024 * 1_024)) / (100 * 1_024)}MB"
        } else {
            "${bytes / 1_024}KB"
        }
}

private fun ConfirmChoice.other(): ConfirmChoice = if (this == ConfirmChoice.YES) ConfirmChoice.NO else ConfirmChoice.YES
