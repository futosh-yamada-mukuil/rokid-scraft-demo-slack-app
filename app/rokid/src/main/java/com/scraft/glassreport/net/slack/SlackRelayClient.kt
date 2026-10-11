package com.scraft.glassreport.net.slack

import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.SendOutcome
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/** 中継サーバに「表示中の版から変わるまで」応答を保留させる秒数。サーバ側の上限（25秒）に合わせる。 */
private const val CHANGE_WAIT_SECONDS = 25

/** 変化待ちに失敗したときの再試行間隔。通信断の間に中継サーバを叩き続けないよう倍々に延ばす。 */
private const val CHANGE_RETRY_MIN_MS = 2_000L
private const val CHANGE_RETRY_MAX_MS = 30_000L

/** これより早く応答が返り続けるなら待ちが効いていない。次の要求まで間隔を空けて連打を防ぐ。 */
private const val CHANGE_MIN_ROUND_MS = 1_000L

/**
 * The same photo/comment and FeedRepository contracts used by the glasses' existing UI.
 *
 * [updates] は「取り直す価値がある」合図。写真を送った直後と、中継サーバが保留していた取得が
 * 「表示中の版から変わった」と答えたときに流れる。画面側の定期取得はそのまま残るので、
 * 合図が届かない状況（古い中継サーバ・通信断）でも従来の間隔で追従する。
 *
 * [scope] を渡すと変化待ちを購読者（ロビー・通知・記録一覧）で 1 本に共有し、
 * 誰も見ていなければ止める。null のときは購読ごとに待つ（テスト用）。
 */
class SlackRelayClient(
    private val auth: SlackAuth,
    private val http: OkHttpClient = relayHttpClient(),
    scope: CoroutineScope? = null,
    private val changeWaitSeconds: Int = CHANGE_WAIT_SECONDS,
) : MediaSink,
    FeedRepository {
    override val label = "Slack"
    override val detail = "Slack 開発用チャンネル"
    val configured: Boolean get() = auth.configured
    private val sent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** [latest] が最後に受け取った版。画面が表示している内容に対応する。版を返さないサーバでは null のまま。 */
    private val shownVersion = MutableStateFlow<String?>(null)

    private val changes: Flow<Unit> =
        flow {
            var retryMs = CHANGE_RETRY_MIN_MS
            while (true) {
                // 比べる相手ができるまで待つ（初回取得の前・再登録待ち・版を返さないサーバ）。
                val shown = shownVersion.filterNotNull().first()
                val started = System.nanoTime()
                val current =
                    try {
                        request("feed?since=${URLEncoder.encode(shown, "UTF-8")}&wait=$changeWaitSeconds", cancellable = true)
                            .json
                            .optString("version")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (signIn: SignInRequiredException) {
                        // 登録解除はすぐ画面へ伝える。理由は次の latest() が返し、再登録後の取得で待ちを再開する。
                        shownVersion.compareAndSet(shown, null)
                        emit(Unit)
                        continue
                    } catch (error: Exception) {
                        delay(retryMs)
                        retryMs = (retryMs * 2).coerceAtMost(CHANGE_RETRY_MAX_MS)
                        continue
                    }
                retryMs = CHANGE_RETRY_MIN_MS
                val quick = (System.nanoTime() - started) / 1_000_000 < CHANGE_MIN_ROUND_MS
                if (current.isNotBlank() && current != shown) {
                    // 画面が取り直すまでの間に同じ変化を繰り返し知らせない。
                    shownVersion.compareAndSet(shown, current)
                    emit(Unit)
                }
                // 保留しないサーバや版が落ち着かない状況でも、中継サーバを連打しない。
                if (quick) delay(CHANGE_RETRY_MIN_MS)
            }
        }

    override val updates: Flow<Unit> =
        when {
            // 0 以下なら変化待ちを使わず、定期取得だけで追従する（従来の動き）。
            changeWaitSeconds <= 0 -> sent
            scope != null -> merge(sent, changes.shareIn(scope, SharingStarted.WhileSubscribed()))
            else -> merge(sent, changes)
        }
    private var pendingFingerprint: String? = null
    private var pendingRequestId: String? = null

    override fun accepts(mime: String): Boolean = mime == MIME_JPEG

    override suspend fun send(
        bytes: ByteArray,
        mime: String,
        comment: String,
        voice: ByteArray?,
    ): Result<SendOutcome> =
        pairingResult {
            if (!accepts(mime) || bytes.isEmpty()) throw IOException("SlackにはJPEG写真を送信してください")
            val fingerprint =
                MessageDigest
                    .getInstance(
                        "SHA-256",
                    ).digest(bytes + comment.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            if (pendingFingerprint != fingerprint) {
                pendingFingerprint = fingerprint
                pendingRequestId = UUID.randomUUID().toString()
            }
            val body =
                MultipartBody
                    .Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("comment", comment)
                    .addFormDataPart("requestId", requireNotNull(pendingRequestId))
                    .addFormDataPart("photo", "photo.jpg", bytes.toRequestBody(MIME_JPEG.toMediaType()))
                    .build()
            val started = System.currentTimeMillis()
            val response = request("photos", body)
            if (!response.json.optBoolean("ok")) throw IOException("Slack送信の応答が不正です")
            pendingFingerprint = null
            pendingRequestId = null
            sent.tryEmit(Unit)
            SendOutcome(System.currentTimeMillis() - started)
        }

    override suspend fun latest(): Result<List<FeedPost>> =
        pairingResult {
            val json = request("feed").json
            val posts = json.optJSONArray("posts") ?: throw IOException("Slack返信の応答が不正です")
            (0 until posts.length())
                .map { parsePost(posts.getJSONObject(it)) }
                .also { shownVersion.value = json.optString("version").ifBlank { null } }
        }

    private suspend fun request(
        path: String,
        body: okhttp3.RequestBody? = null,
        cancellable: Boolean = false,
    ): RelayResponse {
        if (!configured) throw IOException("Slack中継サーバが未設定です")
        val token = auth.accessToken() ?: throw SignInRequiredException("Slack端末をPCで登録してください")
        val builder = Request.Builder().url("${auth.relayUrl}/api/slack/$path").header("Authorization", "Bearer $token")
        if (body != null) builder.post(body)
        val response = relayRequest(http, builder.build(), cancellable)
        if (response.code == 401) {
            auth.invalidate()
            throw SignInRequiredException("Slack端末をPCで再登録してください")
        }
        if (response.code !in 200..299) throw response.error()
        return response
    }

    private fun parsePost(json: JSONObject): FeedPost {
        val id = json.optString("id")
        val created = json.optLong("createdAtMs")
        if (id.isBlank() || created <= 0) throw IOException("Slack返信のデータが不正です")
        return FeedPost(
            id = id,
            author = json.optString("author", "Slack"),
            text = json.optString("text"),
            createdAtMs = created,
            attachmentCount = json.optInt("attachmentCount"),
            imageCount = json.optInt("imageCount"),
            isOwn = json.optBoolean("isOwn"),
        )
    }
}
