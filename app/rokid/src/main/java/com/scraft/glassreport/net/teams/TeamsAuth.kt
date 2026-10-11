package com.scraft.glassreport.net.teams

import android.util.Log
import com.scraft.glassreport.BuildConfig
import com.scraft.glassreport.data.prefs.CURRENT_AUTH_VERSION
import com.scraft.glassreport.data.prefs.TokenStore
import com.scraft.glassreport.data.prefs.Tokens
import com.scraft.glassreport.net.JsonLite
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "TeamsAuth"

/** 既定の authority。 */
const val MS_LOGIN_BASE = "https://login.microsoftonline.com"

/**
 * 要求するスコープ。
 * - `offline_access` … 更新トークンを取得し、デバイスコードの再入力を減らす
 * - `ChannelMessage.Send` … 指定チャネルへ本文・画像を送信
 * - `ChannelMessage.Read.All` … 指定チャネルを閲覧し、返信通知を取得
 */
const val TEAMS_SCOPES = "offline_access ChannelMessage.Read.All ChannelMessage.Send"

/** アクセストークンを使い切る前に更新する余裕。 */
private const val REFRESH_MARGIN_MS = 60_000L

/** `slow_down` を受けたときに次回以降の間隔へ上乗せする秒数（RFC 8628 の指示どおり）。 */
private const val SLOW_DOWN_EXTRA_MS = 5_000L

/** `expires_in` が無いときの既定値。 */
private const val DEFAULT_EXPIRES_IN_SEC = 3_600L
private const val DEFAULT_CODE_EXPIRES_IN_SEC = 900L
private const val DEFAULT_INTERVAL_SEC = 5L

/**
 * 認証サーバ側の一時障害を表す `error`。人の再サインインでは直らないので、
 * 通信断と同じく「あとでもう一度試す」に倒す（保存済みトークンは捨てない）。
 */
private val TRANSIENT_OAUTH_ERRORS = setOf("temporarily_unavailable", "server_error")

/**
 * `devicecode` の応答。[userCode] を人に見せ、[deviceCode] でポーリングする。
 * [expiresAtMs] は絶対時刻（epoch ms）。
 */
data class DeviceCode(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val intervalMs: Long,
    val expiresAtMs: Long,
)

/**
 * サインインの窓口。画面（`AuthViewModel`）はこれに依存し、テストではフェイクを注入する。
 * 実装は [TeamsAuth] だけ。
 */
interface SignInFlow {
    /** `.env` から設定が入っているか。未設定ならサインイン画面を出さない。 */
    val configured: Boolean

    /** 保存済みトークンでサインイン済みか（ネットワークを使わない即時判定）。 */
    fun signedIn(): Boolean

    /** 投稿に使えるアクセストークン。再サインインが必要なら null。 */
    suspend fun accessToken(forceRefresh: Boolean = false): String?

    /** サインインを開始し、人に見せるコードを得る。 */
    suspend fun requestDeviceCode(): Result<DeviceCode>

    /** ブラウザでの入力が済むまで待つ。 */
    suspend fun awaitSignIn(code: DeviceCode): Result<Unit>
}

/** ポーリング 1 回の結果。 */
sealed interface PollResult {
    /** サインイン完了。トークンは [TokenStore] に保存済み。 */
    object Granted : PollResult

    /** まだブラウザ側で入力されていない。[extraDelayMs] は次回以降の間隔への上乗せ。 */
    data class Pending(
        val extraDelayMs: Long = 0L,
    ) : PollResult

    /** 失敗（期限切れ・拒否など）。やり直すには新しいコードを取り直す。 */
    data class Failed(
        val message: String,
    ) : PollResult
}

/**
 * 自前の Device Code Flow。**MSAL Android は使わない**（グラスにブラウザが無く、
 * MSAL の対話フローが成立しないため。経路は Python / TypeScript 実装で実証済み）。
 *
 * ```
 * POST {login}/{tenant}/oauth2/v2.0/devicecode   → user_code / device_code / interval
 *   （人が別端末のブラウザで user_code を入力する）
 * POST {login}/{tenant}/oauth2/v2.0/token        → interval 秒ごとにポーリング
 *   grant_type=urn:ietf:params:oauth:grant-type:device_code
 * ```
 *
 * サインイン後は `refresh_token` で自動更新するため、人の操作は初回だけで済む。
 *
 * [loginBase] と [now] はテスト（MockWebServer・仮想時刻）で差し替えるための口。
 * **client secret は存在しない**（パブリッククライアント）ので、このクラスが持つ秘密は取得したトークンだけ。
 */
class TeamsAuth(
    private val tokens: TokenStore,
    private val tenantId: String = BuildConfig.MS_TENANT_ID,
    private val clientId: String = BuildConfig.MS_CLIENT_ID,
    loginBase: String = MS_LOGIN_BASE,
    private val http: OkHttpClient = defaultHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
) : SignInFlow {
    private val loginBase = loginBase.trimEnd('/')

    /**
     * トークンの確認〜更新〜保存を 1 本に直列化する。ロビーの取得と写真の送信が同時に期限切れへ
     * 当たっても、更新要求は 1 回だけ飛ばし、後から来た方は保存済みの新しいトークンを使う。
     */
    private val refreshLock = Mutex()

    /** テナント／クライアントが `.env` から入っているか。未設定ならサインイン自体を出さない。 */
    override val configured: Boolean get() = tenantId.isNotBlank() && clientId.isNotBlank()

    /**
     * サインイン済みか（ネットワークを使わない即時判定）。
     * `refresh_token` があれば期限切れでも更新できるのでサインイン済みとみなす。
     */
    override fun signedIn(): Boolean {
        val saved = tokens.load() ?: return false
        if (saved.authVersion != CURRENT_AUTH_VERSION) return false
        return saved.refreshToken != null || saved.expiresAt - REFRESH_MARGIN_MS > now()
    }

    /** 保存済み Graph アクセストークンの oid。トークン形式が不明な場合は判定しない。 */
    fun signedInUserId(): String? =
        runCatching {
            val token = tokens.load()?.takeIf { it.authVersion == CURRENT_AUTH_VERSION }?.accessToken ?: return@runCatching null
            val payload = token.split('.').getOrNull(1) ?: return@runCatching null
            val decoded = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            org.json
                .JSONObject(decoded)
                .optString("oid")
                .takeIf { it.isNotBlank() }
        }.getOrNull()

    fun signOut() = tokens.clear()

    /** サインインを開始し、人に見せる [DeviceCode] を得る。 */
    override suspend fun requestDeviceCode(): Result<DeviceCode> {
        if (!configured) return Result.failure(IllegalStateException("Teams の設定がありません"))

        val body =
            postForm(
                "$loginBase/$tenantId/oauth2/v2.0/devicecode",
                mapOf("client_id" to clientId, "scope" to TEAMS_SCOPES),
            ).getOrElse { return Result.failure(it) }

        val userCode =
            JsonLite.string(body, "user_code")
                ?: return Result.failure(IOException(errorMessage(body) ?: "コードの取得に失敗しました"))

        return Result.success(
            DeviceCode(
                userCode = userCode,
                verificationUri = JsonLite.string(body, "verification_uri") ?: "https://microsoft.com/devicelogin",
                deviceCode = JsonLite.string(body, "device_code").orEmpty(),
                intervalMs = (JsonLite.number(body, "interval") ?: DEFAULT_INTERVAL_SEC) * 1_000L,
                expiresAtMs = now() + (JsonLite.number(body, "expires_in") ?: DEFAULT_CODE_EXPIRES_IN_SEC) * 1_000L,
            ),
        )
    }

    /**
     * ブラウザでの入力が済むまで待つ。成功時はトークンを保存して [Result.success]。
     * 呼び出し側（ViewModel）はこれを起動して、画面には [DeviceCode] を出しておくだけでよい。
     */
    override suspend fun awaitSignIn(code: DeviceCode): Result<Unit> {
        var interval = code.intervalMs
        while (true) {
            delay(interval)
            when (val result = pollOnce(code)) {
                PollResult.Granted -> {
                    Log.i(TAG, "サインイン完了")
                    return Result.success(Unit)
                }
                // slow_down は「以降ずっと間隔を空けろ」の指示なので加算して保つ。
                is PollResult.Pending -> interval += result.extraDelayMs
                is PollResult.Failed -> return Result.failure(IOException(result.message))
            }
        }
    }

    /**
     * ポーリングを 1 回だけ行う。
     *
     * 通信エラーは [PollResult.Pending] として扱う（グラスの Wi-Fi が一瞬切れただけで
     * サインインを台無しにしないため）。待ち続けるのはコードの期限までで、そこで打ち切る。
     */
    suspend fun pollOnce(code: DeviceCode): PollResult {
        if (now() > code.expiresAtMs) return PollResult.Failed("コードの有効期限が切れました")

        val body =
            postForm(
                tokenUrl(),
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId,
                    "device_code" to code.deviceCode,
                ),
            ).getOrElse { return PollResult.Pending() }

        val granted = JsonLite.string(body, "access_token")
        if (granted != null) {
            // 空のトークンを保存すると「完了」と出たのに未サインインへ戻る。保存せず失敗として見せる。
            if (granted.isBlank()) return PollResult.Failed("サインインに失敗しました")
            refreshLock.withLock { store(body, granted) }
            return PollResult.Granted
        }
        return when (val error = JsonLite.string(body, "error")) {
            "authorization_pending" -> PollResult.Pending()
            "slow_down" -> PollResult.Pending(SLOW_DOWN_EXTRA_MS)
            "expired_token" -> PollResult.Failed("コードの有効期限が切れました")
            "authorization_declined" -> PollResult.Failed("サインインが拒否されました")
            // 認証サーバの一時障害（`error` を持たない 5xx の HTML なども含む）は通信断と同じ扱い。
            // 人がブラウザで入力済みのコードを無駄にしない。待つのはコードの期限まで。
            null, in TRANSIENT_OAUTH_ERRORS -> PollResult.Pending()
            else -> PollResult.Failed(errorMessage(body) ?: error)
        }
    }

    /**
     * 投稿に使うアクセストークン。期限が近ければ `refresh_token` で更新する。
     * 取得できなければ理由を問わず null（サインイン画面の「素通りできるか」の判定用）。
     * 失敗の理由で振る舞いを変える呼び出し側は [authorize] を使う。
     *
     * [forceRefresh] は 401 を受けた呼び出し側から使う（期限内でも失効することがある）。
     */
    override suspend fun accessToken(forceRefresh: Boolean): String? = authorize(forceRefresh).getOrNull()

    /**
     * Graph 呼び出しに使うアクセストークン。[accessToken] と違い**失敗の種類を区別して返す**。
     *
     * - [SignInRequiredException] … 人の再サインインが必要（トークンなし／リフレッシュトークン失効／同意の取り直し）
     * - それ以外の [IOException] … 通信断や認証サーバの一時障害。**保存済みトークンは残る**ので次の呼び出しでまた試せる
     *
     * グラスの Wi-Fi は頻繁に切れる。更新のタイミングで通信が落ちただけの状態を「未サインイン」と
     * 報告すると、有効なリフレッシュトークンを持ったままデバイスコードの再入力へ送ってしまう。
     *
     * [rejected] は 401 を受けたトークン。別の呼び出しが先に更新を済ませていればそれを使い、二重に更新しない。
     */
    suspend fun authorize(
        forceRefresh: Boolean = false,
        rejected: String? = null,
    ): Result<String> =
        refreshLock.withLock {
            val saved = tokens.load() ?: return@withLock signInRequired()
            if (saved.authVersion != CURRENT_AUTH_VERSION) {
                // 現在のチャネル権限セットと違う古い access token を使わず再同意へ送る。
                tokens.clear()
                return@withLock signInRequired()
            }
            val fresh = saved.expiresAt - REFRESH_MARGIN_MS > now()
            val alreadyReplaced = rejected != null && saved.accessToken != rejected
            if (fresh && (!forceRefresh || alreadyReplaced)) return@withLock Result.success(saved.accessToken)

            val refreshToken = saved.refreshToken ?: return@withLock signInRequired()
            val body =
                postForm(
                    tokenUrl(),
                    mapOf(
                        "grant_type" to "refresh_token",
                        "client_id" to clientId,
                        "refresh_token" to refreshToken,
                        "scope" to TEAMS_SCOPES,
                    ),
                ).getOrElse { return@withLock Result.failure(it) }

            val access = JsonLite.string(body, "access_token")?.takeIf { it.isNotBlank() }
            if (access != null) return@withLock Result.success(store(body, access).accessToken)

            val error = JsonLite.string(body, "error")
            Log.w(TAG, "トークン更新に失敗: $error") // 値そのものは出さない
            when {
                // リフレッシュトークンが失効している。持っていても無意味なので消して再サインインへ促す。
                error == "invalid_grant" -> {
                    tokens.clear()
                    signInRequired()
                }
                // `error` を持たない応答（5xx の HTML など）と一時障害は、トークンを保ったまま再試行へ。
                error == null || error in TRANSIENT_OAUTH_ERRORS -> Result.failure(IOException("認証サーバに接続できません"))
                else -> signInRequired()
            }
        }

    private fun signInRequired(): Result<String> = Result.failure(SignInRequiredException("Teams 未サインイン"))

    /**
     * `token` 応答を保存する。`refresh_token` が省略された応答では既存のものを保つ。
     * [accessToken] は呼び出し側が空でないと確かめた値（空を保存すると未サインインへ倒れる）。
     */
    private fun store(
        body: String,
        accessToken: String,
    ): Tokens {
        val saved =
            Tokens(
                accessToken = accessToken,
                refreshToken = JsonLite.string(body, "refresh_token") ?: tokens.load()?.refreshToken,
                expiresAt = now() + (JsonLite.number(body, "expires_in") ?: DEFAULT_EXPIRES_IN_SEC) * 1_000L,
            )
        tokens.save(saved)
        return saved
    }

    private fun tokenUrl() = "$loginBase/$tenantId/oauth2/v2.0/token"

    /**
     * `application/x-www-form-urlencoded` で POST し、本文を返す。
     * **エラーでも JSON が返る**ため HTTP ステータスは見ず、本文の `error` で判定させる。
     */
    private suspend fun postForm(
        url: String,
        fields: Map<String, String>,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
            try {
                http
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .post(form)
                            .build(),
                    ).execute()
                    .use { res ->
                        Result.success(res.body?.string().orEmpty())
                    }
            } catch (e: IOException) {
                Log.w(TAG, "認証エンドポイントとの通信に失敗", e)
                Result.failure(IOException("ネットワークに接続できません", e))
            }
        }

    /** `error_description` は複数行の長文（AADSTS…）なので 1 行目だけ HUD 向けに切り出す。 */
    private fun errorMessage(body: String): String? =
        JsonLite
            .string(body, "error_description")
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.take(120)

    companion object {
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
