package com.scraft.glassreport.net.slack

import com.scraft.glassreport.data.repo.FeedRepository
import com.scraft.glassreport.domain.model.FeedPost
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.SendOutcome
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** The same photo/comment and FeedRepository contracts used by the glasses' existing UI. */
class SlackRelayClient(
    private val auth: SlackAuth,
    private val http: OkHttpClient = relayHttpClient(),
) : MediaSink,
    FeedRepository {
    override val label = "Slack"
    override val detail = "Slack 開発用チャンネル"
    val configured: Boolean get() = auth.configured
    override val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
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
            updates.tryEmit(Unit)
            SendOutcome(System.currentTimeMillis() - started)
        }

    override suspend fun latest(): Result<List<FeedPost>> =
        pairingResult {
            val posts = request("feed").json.optJSONArray("posts") ?: throw IOException("Slack返信の応答が不正です")
            (0 until posts.length()).map { parsePost(posts.getJSONObject(it)) }
        }

    private suspend fun request(
        path: String,
        body: okhttp3.RequestBody? = null,
    ): RelayResponse {
        if (!configured) throw IOException("Slack中継サーバが未設定です")
        val token = auth.accessToken() ?: throw SignInRequiredException("Slack端末をPCで登録してください")
        val builder = Request.Builder().url("${auth.relayUrl}/api/slack/$path").header("Authorization", "Bearer $token")
        if (body != null) builder.post(body)
        val response = relayRequest(http, builder.build())
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
