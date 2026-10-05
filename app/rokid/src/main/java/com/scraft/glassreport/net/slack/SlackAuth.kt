package com.scraft.glassreport.net.slack

import com.scraft.glassreport.BuildConfig
import com.scraft.glassreport.data.prefs.TokenStore
import com.scraft.glassreport.data.prefs.Tokens
import com.scraft.glassreport.net.teams.DeviceCode
import com.scraft.glassreport.net.teams.SignInFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Custom relay pairing, not Slack OAuth's device-code grant. Bot credentials remain on the PC. */
class SlackAuth(
    private val tokens: TokenStore,
    relayUrl: String = BuildConfig.SLACK_RELAY_URL,
    private val http: OkHttpClient = relayHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val deviceLabel: String = if (BuildConfig.AVD_CONTROLS) "Rokid_Test" else "Rokid",
) : SignInFlow {
    val relayUrl: String = relayUrl.trim().trimEnd('/')
    override val configured: Boolean get() = validRelayUrl(relayUrl)

    override fun signedIn(): Boolean = tokens.load()?.let { it.refreshToken == relayUrl && it.expiresAt > now() } == true

    override suspend fun accessToken(forceRefresh: Boolean): String? {
        val saved = tokens.load()?.takeIf { signedIn() } ?: return null
        if (forceRefresh) {
            val response =
                relayRequest(
                    http,
                    Request
                        .Builder()
                        .url("$relayUrl/api/slack/session")
                        .header("Authorization", "Bearer ${saved.accessToken}")
                        .build(),
                )
            if (response.code == 401) {
                invalidate()
                return null
            }
            if (response.code !in 200..299) throw response.error()
        }
        return saved.accessToken
    }

    fun invalidate() = tokens.clear()

    override suspend fun requestDeviceCode(): Result<DeviceCode> =
        pairingResult {
            if (!configured) throw IOException("Slack中継サーバのURLを設定してください")
            val response = post("device-code", JSONObject().put("label", deviceLabel))
            if (response.code !in 200..299) throw response.error()
            val json = response.json
            val code = json.optString("user_code")
            val secret = json.optString("device_code")
            val uri = json.optString("verification_uri")
            val expires = json.optLong("expires_in")
            if (code.isBlank() || secret.isBlank() || uri.isBlank() || expires !in 1..900) throw IOException("端末コードの応答が不正です")
            DeviceCode(code, uri, secret, json.optLong("interval", 2).coerceIn(1, 30) * 1000, now() + expires * 1000)
        }

    override suspend fun awaitSignIn(code: DeviceCode): Result<Unit> =
        pairingResult {
            while (now() < code.expiresAtMs) {
                delay(code.intervalMs)
                val response = post("device-token", JSONObject().put("device_code", code.deviceCode))
                if (response.code !in 200..299) throw response.error()
                when (response.json.optString("status")) {
                    "pending" -> Unit
                    "connected" -> {
                        val token = response.json.optString("access_token")
                        if (token.isBlank()) throw IOException("端末接続情報の応答が不正です")
                        tokens.save(Tokens(token, relayUrl, Long.MAX_VALUE))
                        return@pairingResult Unit
                    }
                    else -> throw IOException("端末登録の応答が不正です")
                }
            }
            throw IOException("端末コードが期限切れです")
        }

    private suspend fun post(
        path: String,
        json: JSONObject,
    ): RelayResponse =
        relayRequest(
            http,
            Request
                .Builder()
                .url("$relayUrl/api/slack/$path")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        )
}

internal suspend fun <T> pairingResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (
        cancelled: CancellationException,
    ) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
