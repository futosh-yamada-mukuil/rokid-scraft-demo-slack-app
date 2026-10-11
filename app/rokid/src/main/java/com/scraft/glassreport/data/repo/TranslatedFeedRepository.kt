package com.scraft.glassreport.data.repo

import com.scraft.glassreport.capture.tts.SpeechLanguage
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.model.TranslationState
import com.scraft.glassreport.domain.translate.TranslationException
import com.scraft.glassreport.domain.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/** Posts fetched within this window are re-presented without another Graph GET. */
private const val RAW_REUSE_MS = 3_000L

/**
 * [clock] is wall time and can jump (the glasses sync it after boot). A reading earlier than the
 * recorded one means the clock moved back, so the interval is treated as elapsed instead of
 * freezing the feed or the translation retry until the clock catches up.
 */
private fun elapsedAtLeast(
    now: Long,
    since: Long,
    intervalMs: Long,
): Boolean = (now - since).let { it < 0 || it >= intervalMs }

/** In-memory only. One worker protects API quotas; no message content is persisted or logged. */
class FeedTranslationCache(
    private val translator: Translator,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryMs: Long = 60_000,
) {
    private data class Key(
        val channel: String,
        val id: String,
        val text: String,
        val target: SpeechLanguage,
    )

    private class Entry(
        var result: Result<String>? = null,
        var finishedAt: Long = 0,
        var job: Job? = null,
    )

    private val entries = LinkedHashMap<Key, Entry>()
    private val worker = Semaphore(1)

    @Volatile private var retryNotBefore = 0L
    val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Synchronized
    fun present(
        channel: String,
        posts: List<FeedPost>,
        target: SpeechLanguage,
    ): List<FeedPost> {
        // Discard obsolete queued work on channel/language changes, retaining completed translations.
        entries
            .filter { (key, entry) -> entry.result == null && (key.channel != channel || key.target != target) }
            .keys
            .toList()
            .forEach { key -> entries.remove(key)?.job?.cancel() }
        return posts.map { post ->
            if (post.text.isBlank() || (post.imageCount > 0 && post.text.trim() == "(コメントなし)")) {
                return@map post.copy(language = target, canSpeak = false)
            }
            val key = Key(channel, post.id, post.text, target)
            var entry = entries[key]
            if (entry?.result?.isFailure == true && elapsedAtLeast(clock(), entry.finishedAt, retryMs)) {
                entries.remove(key)
                entry = null
            }
            if (entry == null) {
                entry = Entry()
                entries[key] = entry
                val pending = entry
                pending.job =
                    scope.launch {
                        val result =
                            try {
                                withTimeout(25_000) {
                                    worker.withPermit {
                                        // A backoff longer than retryMs can only come from a clock that moved back.
                                        if (retryNotBefore - clock() in 1..retryMs) {
                                            Result.failure(IllegalStateException("翻訳の再試行待ち"))
                                        } else {
                                            translator
                                                .translate(key.text, key.target)
                                                .mapCatching { it.trim().also { text -> require(text.isNotEmpty()) } }
                                                .also {
                                                    val error = it.exceptionOrNull() as? TranslationException
                                                    if (error != null &&
                                                        (error.code in listOf(401, 403, 429) || (error.code ?: 0) >= 500)
                                                    ) {
                                                        retryNotBefore = clock() + retryMs
                                                    }
                                                }
                                        }
                                    }
                                }
                            } catch (cancel: CancellationException) {
                                if (cancel is kotlinx.coroutines.TimeoutCancellationException) Result.failure(cancel) else throw cancel
                            } catch (error: Exception) {
                                Result.failure(error)
                            }
                        synchronized(this@FeedTranslationCache) {
                            pending.result = result
                            pending.finishedAt = clock()
                        }
                        updates.tryEmit(Unit)
                    }
                while (entries.size > 200) entries.remove(entries.keys.first())?.job?.cancel()
            }
            val result = entry.result
            when {
                result == null -> post.copy(translation = TranslationState.PENDING, canSpeak = false)
                result.isFailure -> post.copy(translation = TranslationState.FAILED, canSpeak = false)
                else -> post.copy(text = result.getOrThrow(), language = target, translation = TranslationState.TRANSLATED, canSpeak = true)
            }
        }
    }
}

/** Shared by lobby, reply viewer and notification paths; events never trigger a Graph GET per translated row. */
class TranslatedFeedRepository(
    private val source: FeedRepository,
    private val cache: FeedTranslationCache,
    private val channel: () -> String,
    private val target: () -> SpeechLanguage,
    configUpdates: Flow<Unit>,
    private val clock: () -> Long = System::currentTimeMillis,
) : FeedRepository {
    /** The source said its content changed: the next [latest] must not answer from the reuse window. */
    private val sourceChanged = AtomicBoolean(false)
    override val updates = merge(cache.updates, configUpdates, source.updates.onEach { sourceChanged.set(true) })
    private val mutex = Mutex()
    private var fetchedAt = Long.MIN_VALUE
    private var fetchedChannel = ""
    private var raw: List<FeedPost>? = null
    private var lastTarget: SpeechLanguage? = null

    override suspend fun latest(): Result<List<FeedPost>> =
        mutex.withLock {
            val activeChannel = channel()
            val activeTarget = target()
            val languageChanged = lastTarget != activeTarget
            lastTarget = activeTarget
            val stale = elapsedAtLeast(clock(), fetchedAt, RAW_REUSE_MS)
            // Cleared before fetching, so a change announced during the request is fetched next time.
            val announced = sourceChanged.getAndSet(false)
            if (raw == null || fetchedChannel != activeChannel || announced || (!languageChanged && stale)) {
                val result = source.latest()
                if (result.isFailure) return@withLock result
                // Do not display results from a channel that changed during the remote request.
                if (channel() != activeChannel) return@withLock Result.success(emptyList())
                raw = result.getOrThrow()
                fetchedChannel = activeChannel
                fetchedAt = clock()
            }
            Result.success(cache.present(activeChannel, raw.orEmpty(), target()))
        }
}
