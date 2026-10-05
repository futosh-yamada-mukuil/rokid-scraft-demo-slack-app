package com.scraft.glassreport.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.model.TranslationState
import org.json.JSONArray

private const val MAX_SEEN_IDS = 100
private const val PREFS_NAME = "teams-notification-seen"
private const val KEY_SCOPE = "channel"
private const val KEY_INITIALIZED = "initialized"
private const val KEY_IDS = "ids"

/** UI と常駐サービスで共有する既読判定。最初の取得は既存投稿の基準作りだけに使う。 */
interface SeenPosts {
    fun claimNewest(posts: List<FeedPost>): FeedPost?
}

/** 最新100件の ID だけを保持する判定器。本文やユーザー名は保存しない。 */
class SeenPostCursor(
    var initialized: Boolean = false,
    ids: Collection<String> = emptyList(),
) : SeenPosts {
    private val seen = LinkedHashSet(ids)

    @Synchronized
    override fun claimNewest(posts: List<FeedPost>): FeedPost? {
        // Baseline includes pending history, but new pending posts stay unclaimed until ready.
        val eligible = if (initialized) posts.filter { it.translation != TranslationState.PENDING } else posts
        val newest = if (initialized) eligible.firstOrNull { it.id !in seen } else null
        initialized = true
        eligible.asReversed().forEach { post ->
            seen.remove(post.id)
            seen.add(post.id)
        }
        while (seen.size > MAX_SEEN_IDS) seen.remove(seen.first())
        return newest
    }

    @Synchronized
    fun ids(): List<String> = seen.toList()
}

/** チャネルが変われば既読をリセットし、初回取得で過去投稿を通知しない。 */
class PersistentSeenPosts(
    private val prefs: SharedPreferences,
    private val scope: () -> String,
) : SeenPosts {
    private var cachedScope: String? = null
    private var cursor: SeenPostCursor? = null

    @Synchronized
    override fun claimNewest(posts: List<FeedPost>): FeedPost? {
        val activeScope = scope()
        if (cursor == null || cachedScope != activeScope) {
            cachedScope = activeScope
            cursor =
                if (prefs.getString(KEY_SCOPE, null) == activeScope) {
                    SeenPostCursor(
                        initialized = prefs.getBoolean(KEY_INITIALIZED, false),
                        ids =
                            runCatching {
                                JSONArray(prefs.getString(KEY_IDS, "[]"))
                                    .let { array -> (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank) }
                            }.getOrDefault(emptyList()),
                    )
                } else {
                    SeenPostCursor()
                }
        }
        val current = checkNotNull(cursor)
        val newest = current.claimNewest(posts)
        val ids = JSONArray().also { array -> current.ids().forEach(array::put) }
        prefs
            .edit()
            .putString(KEY_SCOPE, activeScope)
            .putBoolean(KEY_INITIALIZED, current.initialized)
            .putString(KEY_IDS, ids.toString())
            .apply()
        return newest
    }

    companion object {
        fun create(
            context: Context,
            scope: () -> String,
        ): PersistentSeenPosts = PersistentSeenPosts(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), scope)
    }
}
