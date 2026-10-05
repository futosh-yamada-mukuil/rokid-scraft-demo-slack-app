package com.scraft.glassreport.data.repo

import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.net.teams.FEED_TOP
import com.scraft.glassreport.net.teams.TeamsClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * Feed が読む「開発用Teamsチャネルの最新投稿」の窓口。
 *
 * ポーリングの間隔は**呼び出し側（`FeedViewModel`）が決める**（内部設計書 4 章）。
 * 画面はこのインターフェースにだけ依存し、テストではフェイクを注入する。
 */
interface FeedRepository {
    val updates: Flow<Unit> get() = emptyFlow()

    /** 最新の数件を新しい順で返す。失敗理由は画面にそのまま出せる短い日本語。 */
    suspend fun latest(): Result<List<FeedPost>>
}

/** Teams（Microsoft Graph）から読む実装。 */
class TeamsFeedRepository(
    private val client: TeamsClient,
    private val top: Int = FEED_TOP,
) : FeedRepository {
    override suspend fun latest(): Result<List<FeedPost>> = client.listMessages(top)
}

/** Translation/config changes refresh immediately; the timer still fetches new remote posts. */
fun FeedRepository.refreshes(intervalMs: Long): Flow<Unit> =
    merge(
        updates,
        flow {
            while (true) {
                emit(Unit)
                delay(intervalMs)
            }
        },
    ).conflate()
