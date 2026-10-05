package com.scraft.glassreport.net.teams

import android.util.Log
import com.scraft.glassreport.BuildConfig
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.domain.text.HtmlText
import com.scraft.glassreport.domain.text.RelativeTime
import com.scraft.glassreport.net.JsonLite
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.SendOutcome
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "TeamsClient"

/** Microsoft Graph の既定エンドポイント。 */
const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"

/** Feed の親投稿の件数（`$top`）。返信もこの件数を上限に追加する。 */
const val FEED_TOP = 5

/** Expanded replies are paged independently; bound polling work and retained HUD data. */
private const val MAX_REPLY_PAGES = 20
private const val MAX_REPLIES_PER_THREAD = 4_000
private const val MAX_READ_BYTES = 2L * 1024 * 1024

/** 投稿者名が取れないとき（システム投稿・退職者など）の表示。 */
private const val UNKNOWN_AUTHOR = "(不明な送信者)"

/** 人の投稿だけを Feed に出す（`systemEventMessage` 等は除く）。 */
private const val MESSAGE_TYPE_MESSAGE = "message"

/** 画像を参照する `hostedContents` の一時ID。本文の `<img src>` と対応させる。 */
private const val HOSTED_CONTENT_ID = "1"

/** 投稿できたときの結果。[elapsedMs] は POST 開始から応答までの実測値。 */
data class PostedMessage(
    val messageId: String,
    val webUrl: String,
    val elapsedMs: Long,
)

/**
 * Teams チャネルの投稿と取得（Microsoft Graph）。
 *
 * - [postMessage] … 写真＋コメントを 1 投稿として送る（Phase 1）
 * - [listMessages] … 最新の投稿を数件読む（Phase 3 の Feed）
 *
 * ```
 * POST {graph}/teams/{teamId}/channels/{channelId}/messages
 * body: { "body": {"contentType":"html","content":"…"},
 *         "hostedContents": [{"@microsoft.graph.temporaryId":"1", "contentBytes":"<base64>", …}] }
 * ```
 *
 * **画像は `hostedContents` に base64 で埋め、本文からは相対 URL
 * `../hostedContents/1/$value` で参照する**（投稿後に Graph 側が絶対 URL へ書き換える。実データで確認済み）。
 * 一時ID と `<img src>` の対応を間違えると本文だけが届く。
 *
 * **動画は送れない**（`hostedContents` は画像専用）。[accepts] が弾く。
 * また `ChannelMessage.ReadWrite` が付与されないため、**投稿の編集・削除もできない**。
 */
class TeamsClient(
    private val auth: TeamsAuth,
    // 製品の接続先は APK の BuildConfig。テスト時だけプロバイダーを差し替える。
    private val teamIdProvider: () -> String = { BuildConfig.MS_TEAM_ID },
    private val channelIdProvider: () -> String = { BuildConfig.MS_CHANNEL_ID },
    graphBase: String = GRAPH_BASE,
    private val http: OkHttpClient = defaultHttpClient(),
) : MediaSink {
    /** ID が変わらない場合（テストや固定運用）向け。 */
    constructor(
        auth: TeamsAuth,
        teamId: String,
        channelId: String,
        graphBase: String = GRAPH_BASE,
        http: OkHttpClient = defaultHttpClient(),
    ) : this(auth, { teamId }, { channelId }, graphBase, http)

    private val graphBase = graphBase.trimEnd('/')

    /** Teams の送信・受信を同じチーム／チャネルへ統一する。 */
    private val teamId: String get() = teamIdProvider()
    private val channelId: String get() = channelIdProvider()

    val configured: Boolean get() = auth.configured && teamId.isNotBlank() && channelId.isNotBlank()

    // ── MediaSink（撮影画面からは宛先を区別せずに呼ばれる） ─────────────────────
    override val label: String = "Teams"

    override val detail: String get() = if (configured) "Teams チャネル" else "Teams 未設定"

    /** 画像だけ。動画は Teams へ送れないので、動画モードではこの宛先が候補から外れる。 */
    override fun accepts(mime: String): Boolean = mime.startsWith("image/")

    override suspend fun send(
        bytes: ByteArray,
        mime: String,
        comment: String,
        voice: ByteArray?,
    ): Result<SendOutcome> = postMessage(comment = comment, photo = bytes, mime = mime).map { SendOutcome(it.elapsedMs) }

    /**
     * コメント（と任意の写真）を 1 投稿として送る。
     * 例外は投げず [Result] に畳んで返す（失敗理由は HUD にそのまま出せる短い日本語）。
     */
    suspend fun postMessage(
        comment: String,
        photo: ByteArray? = null,
        mime: String = MIME_JPEG,
    ): Result<PostedMessage> {
        if (!configured) return Result.failure(IllegalStateException("Teams の設定がありません"))
        val image = photo?.takeIf { it.isNotEmpty() }
        if (comment.isBlank() && image == null) {
            return Result.failure(IllegalArgumentException("送信する本文または画像がありません"))
        }
        if (image != null && !accepts(mime)) {
            return Result.failure(IllegalArgumentException("Teams には送れない形式です"))
        }

        val payload = buildPayload(comment, image, mime)

        val started = System.currentTimeMillis()
        val response = sendWithAuth { token -> postRequest(token, payload) }.getOrElse { return Result.failure(it) }
        val elapsed = System.currentTimeMillis() - started

        if (response.code !in 200..299) {
            Log.w(TAG, "投稿失敗 HTTP ${response.code}: ${response.body.take(200)}")
            val reason = JsonLite.string(response.body, "message")?.take(60)
            return Result.failure(IOException("HTTP ${response.code}${if (reason != null) ": $reason" else ""}"))
        }

        val posted =
            PostedMessage(
                messageId = JsonLite.string(response.body, "id").orEmpty(),
                webUrl = JsonLite.string(response.body, "webUrl").orEmpty(),
                elapsedMs = elapsed,
            )
        Log.i(TAG, "投稿成功 id=${posted.messageId} ${photo?.size?.div(1_024) ?: 0}KB ${elapsed}ms")
        return Result.success(posted)
    }

    /**
     * 投稿の JSON を組み立てる。
     *
     * 本文は `contentType: html` なので、コメントは**必ず HTML エスケープする**
     * （文字起こしに `<` が混ざっても本文が壊れないように）。
     * base64 は [java.util.Base64]（`android.util.Base64` はローカル JVM テストでスタブに化ける）。
     */
    private fun buildPayload(
        comment: String,
        photo: ByteArray?,
        mime: String,
    ): String {
        val text = comment.trim()
        val html =
            buildString {
                if (text.isNotEmpty()) append("<div>").append(escapeHtml(text)).append("</div>")
                if (photo != null) {
                    append("<div><img src=\"../hostedContents/$HOSTED_CONTENT_ID/\$value\" style=\"max-width:600px\" ")
                    append("alt=\"添付画像\"></img></div>")
                }
            }

        return buildString {
            append("{\"body\":{\"contentType\":\"html\",\"content\":\"").append(JsonLite.escape(html)).append("\"}")
            if (photo != null) {
                append(",\"hostedContents\":[{\"@microsoft.graph.temporaryId\":\"$HOSTED_CONTENT_ID\"")
                append(",\"contentBytes\":\"").append(Base64.getEncoder().encodeToString(photo)).append("\"")
                append(",\"contentType\":\"").append(JsonLite.escape(mime)).append("\"}]")
            }
            append("}")
        }
    }

    /**
     * 更新されたチャネルスレッドを [top] 件取得し、親投稿＋最新返信 [top] 件を返す。
     * Graph は返信を含むスレッド最終更新で並べるので、古い写真への新着返信も対象となる。
     * 返信のページングは追加権限不要。最大20ページ・4000返信/スレッド・2MiB/応答を超えたら
     * 部分取得を成功にせず失敗として返し、呼び出し側の既存表示と新着カーソルを保つ。
     *
     * ```
     * GET {graph}/teams/{teamId}/channels/{channelId}/messages?$top=5&$expand=replies
     * → {"value":[{"id":…,"messageType":"message","createdDateTime":"…Z",
     *              "from":{"user":{"displayName":"…"}},
     *              "body":{"contentType":"html","content":"<div>…</div>"}}, …]}
     * ```
     *
     * 本文は HTML なので [HtmlText] で剥がし、画像は件数だけ数える（本文には展開しない）。
     * `messageType` が `message` でないもの（入退室などのシステム投稿）と削除済みは落とす。
     * 例外は投げず [Result] に畳んで返す（失敗理由は画面にそのまま出せる短い日本語）。
     */
    suspend fun listMessages(top: Int = FEED_TOP): Result<List<FeedPost>> {
        if (!configured) return Result.failure(IllegalStateException("Teams チャネルが未設定"))
        val limit = top.coerceIn(1, 50)
        // Capture the scope once: settings can change while this poll is in flight.
        val scope = messagesUrl()
        val page = fetchMessagePage("$scope?\$top=$limit&\$expand=replies").getOrElse { return Result.failure(it) }
        return try {
            val parents = page.optJSONArray("value") ?: JSONArray()
            val roots = parseMessages(parents).distinctBy { it.id }.take(limit)
            val replies = mutableListOf<FeedPost>()
            for (index in 0 until minOf(parents.length(), limit)) {
                val parent = parents.optJSONObject(index) ?: continue
                val parentId = parent.optString("id")
                if (parentId.isBlank() || !parent.isNull("deletedDateTime")) continue
                val replyUrl = "$scope/${encodePath(parentId)}/replies"
                val messages = linkedMapOf<String, JSONObject>()

                fun add(values: JSONArray) {
                    for (i in 0 until values.length()) {
                        val reply = values.optJSONObject(i) ?: continue
                        val id = reply.optString("id")
                        val replyTo = reply.optString("replyToId")
                        if (id.isBlank() || (replyTo.isNotBlank() && replyTo != parentId)) continue
                        // Repeated page boundary entries may carry edits/deletions; keep the newest version.
                        val old = messages[id]
                        if (old == null || reply.optString("lastModifiedDateTime") >= old.optString("lastModifiedDateTime")) {
                            messages[id] = reply
                            if (messages.size > MAX_REPLIES_PER_THREAD) throw JSONException("Reply capacity exceeded")
                        }
                    }
                }
                add(parent.optJSONArray("replies") ?: JSONArray())
                var next = parent.optString("replies@odata.nextLink").takeIf { it.isNotBlank() }
                val visited = mutableSetOf<String>()
                while (next != null) {
                    if (visited.size >= MAX_REPLY_PAGES || !visited.add(next) || !isReplyPage(next, replyUrl)) {
                        return Result.failure(IOException("返信を読み取れませんでした"))
                    }
                    val replyPage = fetchMessagePage(next).getOrElse { return Result.failure(it) }
                    add(replyPage.optJSONArray("value") ?: JSONArray())
                    next = replyPage.optString("@odata.nextLink").takeIf { it.isNotBlank() }
                }
                replies += parseMessages(JSONArray(messages.values.toList()), parentId).take(limit)
            }
            // Preserve the existing root-post window and add only the latest reply window.
            Result.success(
                (roots + replies.sortedByDescending { it.createdAtMs ?: Long.MIN_VALUE }.take(limit))
                    .sortedByDescending { it.createdAtMs ?: Long.MIN_VALUE },
            )
        } catch (e: JSONException) {
            Result.failure(IOException("投稿を読み取れませんでした"))
        }
    }

    /** Never send the bearer token to a nextLink outside this thread or Graph origin. */
    private fun isReplyPage(
        url: String,
        expected: String,
    ): Boolean {
        val actual = url.toHttpUrlOrNull() ?: return false
        val scope = expected.toHttpUrlOrNull() ?: return false
        return actual.scheme == scope.scheme &&
            actual.host == scope.host &&
            actual.port == scope.port &&
            actual.pathSegments == scope.pathSegments &&
            actual.username.isEmpty() &&
            actual.password.isEmpty()
    }

    private suspend fun fetchMessagePage(url: String): Result<JSONObject> {
        val response =
            sendWithAuth { token -> getRequest(token, url) }
                .getOrElse { error ->
                    // 通信断はグラスの Wi-Fi が切れているときに必ず通る道。画面にはこの文言を出す。
                    return Result.failure(if (error is SignInRequiredException) error else IOException("ネットワークに接続できません"))
                }

        if (response.code !in 200..299) {
            Log.w(TAG, "取得失敗 HTTP ${response.code}: ${response.body.take(200)}")
            return Result.failure(IOException(readErrorMessage(response.code)))
        }
        return try {
            Result.success(JSONObject(response.body))
        } catch (e: JSONException) {
            Log.w(TAG, "応答 JSON を解釈できない", e)
            Result.failure(IOException("投稿を読み取れませんでした"))
        }
    }

    /**
     * `value` 配列を [FeedPost] にする。**新しい順**に並べ直して返す
     * （Graph は新しい順で返すが、順序に依存しないよう自分で保証する）。
     */
    private fun parseMessages(
        values: JSONArray,
        parentId: String? = null,
    ): List<FeedPost> {
        val posts = ArrayList<FeedPost>(values.length())
        val signedInUserId = auth.signedInUserId()
        for (i in 0 until values.length()) {
            val message = values.optJSONObject(i) ?: continue
            if (message.optString("messageType") != MESSAGE_TYPE_MESSAGE) continue
            if (!message.isNull("deletedDateTime")) continue

            val html = message.optJSONObject("body")?.optString("content").orEmpty()
            val author =
                message
                    .optJSONObject("from")
                    ?.optJSONObject("user")
                    ?.optString("displayName")
                    ?.takeIf { it.isNotBlank() }
                    ?: UNKNOWN_AUTHOR
            val authorId =
                message
                    .optJSONObject("from")
                    ?.optJSONObject("user")
                    ?.optString("id")
                    .orEmpty()
            val embeddedImages = HtmlText.imageCount(html)
            val attachments = message.optJSONArray("attachments")
            val imageAttachments =
                (0 until (attachments?.length() ?: 0)).count { index ->
                    attachments?.optJSONObject(index)?.optString("contentType")?.startsWith("image/") == true
                }
            posts +=
                FeedPost(
                    id = if (parentId == null) message.optString("id") else "reply:$parentId:${message.optString("id")}",
                    author = author,
                    text = HtmlText.toPlainText(html),
                    canSpeak = false,
                    createdAtMs = RelativeTime.parseIso8601(message.optString("createdDateTime")),
                    attachmentCount = embeddedImages + (attachments?.length() ?: 0),
                    imageCount = embeddedImages + imageAttachments,
                    isOwn =
                        if (signedInUserId != null && authorId.isNotBlank()) {
                            signedInUserId.equals(authorId, ignoreCase = true)
                        } else {
                            null
                        },
                )
        }
        return posts.sortedByDescending { it.createdAtMs ?: Long.MIN_VALUE }
    }

    /** 読み取り失敗の理由。原因の切り分けができる粒度で、画面に収まる短さにする。 */
    private fun readErrorMessage(code: Int): String =
        when (code) {
            401, 403 -> "Teams にアクセスできません"
            404 -> "チャネルが見つかりません"
            429 -> "Teams が混雑中"
            in 500..599 -> "Teams サーバ障害"
            else -> "取得失敗 HTTP $code"
        }

    /**
     * アクセストークンを付けて 1 回投げ、**401 なら 1 度だけ強制更新して再試行**する。
     * 期限内でもトークンが失効することがある（管理者の操作・パスワード変更など）ため。
     */
    private suspend fun sendWithAuth(build: (String) -> Request): Result<HttpResponse> {
        val token = auth.accessToken() ?: return Result.failure(SignInRequiredException("Teams 未サインイン"))

        val response = execute(build(token)).getOrElse { return Result.failure(it) }
        if (response.code != 401) return Result.success(response)

        Log.i(TAG, "401 のためトークンを更新して再試行する")
        val refreshed = auth.accessToken(forceRefresh = true) ?: return Result.failure(SignInRequiredException("Teams 未サインイン"))
        return execute(build(refreshed))
    }

    private fun messagesUrl() = "$graphBase/teams/${encodePath(teamId)}/channels/${encodePath(channelId)}/messages"

    private fun postRequest(
        token: String,
        payload: String,
    ): Request =
        Request
            .Builder()
            .url(messagesUrl())
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

    private fun getRequest(
        token: String,
        url: String,
    ): Request =
        Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

    private suspend fun execute(request: Request): Result<HttpResponse> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { res ->
                    val body = res.body
                    // Expanded threads can be large. Refuse an oversized response before allocating its full body.
                    if (request.method == "GET") {
                        val source = body.source()
                        source.request(MAX_READ_BYTES + 1)
                        if (source.buffer.size > MAX_READ_BYTES) throw IOException("Teams response too large")
                    }
                    Result.success(HttpResponse(res.code, body?.string().orEmpty()))
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "${request.method} タイムアウト", e)
                Result.failure(IOException("タイムアウト"))
            } catch (e: IOException) {
                Log.w(TAG, "${request.method} エラー", e)
                Result.failure(IOException(e.message?.take(40) ?: "通信エラー"))
            }
        }

    /** チャネル ID は `19:…@thread.tacv2` とコロンを含むのでパスとしてエスケープする。 */
    private fun encodePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private data class HttpResponse(
        val code: Int,
        val body: String,
    )

    companion object {
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS) // 写真を base64 で載せるので余裕を取る
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
