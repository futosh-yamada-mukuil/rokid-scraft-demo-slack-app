package com.scraft.glassreport.net.teams

import com.scraft.glassreport.data.prefs.InMemoryTokenStore
import com.scraft.glassreport.data.prefs.Tokens
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Device Code Flow の契約を MockWebServer で確認する。
 * **お客様のテナント／チャネルへは一切アクセスしない**（実サインインは人が 1 度だけ行う）。
 */
class TeamsAuthTest {
    private lateinit var server: MockWebServer
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun auth(store: InMemoryTokenStore = InMemoryTokenStore()) =
        TeamsAuth(
            tokens = store,
            tenantId = "tenant-1",
            clientId = "client-1",
            loginBase = server.url("/").toString(),
            now = { clock },
        )

    @Test
    fun requestDeviceCode_parsesUserCodeAndPostsClientIdAndScopes() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"user_code":"F7KQ2M9XZ","device_code":"dev-123",
                     "verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5}
                    """.trimIndent(),
                ),
            )

            val code = auth().requestDeviceCode().getOrThrow()
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals("/tenant-1/oauth2/v2.0/devicecode", request.path)
            assertTrue(body.contains("client_id=client-1"))
            assertTrue(body.contains("offline_access"))
            assertTrue(body.contains("ChannelMessage.Read.All"))
            assertTrue(body.contains("ChannelMessage.Send"))
            assertFalse(body.contains("ChatMessage"))
            assertEquals("F7KQ2M9XZ", code.userCode)
            assertEquals("dev-123", code.deviceCode)
            assertEquals("https://microsoft.com/devicelogin", code.verificationUri)
            assertEquals(5_000L, code.intervalMs)
            assertEquals(clock + 900_000L, code.expiresAtMs)
        }

    @Test
    fun requestDeviceCode_reportsFirstLineOfErrorDescription() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"error":"invalid_client","error_description":"AADSTS7000218: 説明1行目\r\nTrace ID: x"}""",
                ),
            )

            val result = auth().requestDeviceCode()

            assertTrue(result.isFailure)
            assertEquals("AADSTS7000218: 説明1行目", result.exceptionOrNull()?.message)
        }

    @Test
    fun requestDeviceCode_failsFastWhenNotConfigured() =
        runTest {
            val notConfigured = TeamsAuth(InMemoryTokenStore(), tenantId = "", clientId = "", loginBase = server.url("/").toString())

            assertTrue(notConfigured.requestDeviceCode().isFailure)
            assertFalse(notConfigured.configured)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun requestDeviceCode_showsJapaneseNetworkErrorWhenConnectionFails() =
        runTest {
            val teams = auth()
            server.shutdown()

            val result = teams.requestDeviceCode()

            assertEquals("ネットワークに接続できません", result.exceptionOrNull()?.message)
        }

    @Test
    fun awaitSignIn_pollsUntilGrantedAndStoresTokens() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
            server.enqueue(MockResponse().setBody("""{"error":"slow_down"}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3599,"token_type":"Bearer"}""",
                ),
            )
            val store = InMemoryTokenStore()

            val result = auth(store).awaitSignIn(deviceCode())

            assertTrue(result.isSuccess)
            assertEquals(3, server.requestCount)
            val tokenRequest = server.takeRequest()
            assertEquals("/tenant-1/oauth2/v2.0/token", tokenRequest.path)
            val body = tokenRequest.body.readUtf8()
            assertTrue(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
            assertTrue(body.contains("device_code=dev-123"))
            assertEquals("at-1", store.load()?.accessToken)
            assertEquals("rt-1", store.load()?.refreshToken)
            assertEquals(clock + 3_599_000L, store.load()?.expiresAt)
        }

    @Test
    fun awaitSignIn_stopsOnDeclineWithJapaneseMessage() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"error":"authorization_declined"}"""))

            val result = auth().awaitSignIn(deviceCode())

            assertEquals("サインインが拒否されました", result.exceptionOrNull()?.message)
        }

    @Test
    fun pollOnce_treatsNetworkFailureAsPendingButExpiryAsFailure() =
        runTest {
            val teams = auth()
            server.shutdown() // 通信できない状態を作る

            // Wi-Fi が一瞬切れただけでサインインを捨てないよう、通信エラーは待ちとして扱う。
            assertTrue(teams.pollOnce(deviceCode()) is PollResult.Pending)

            // ただしコードの期限を過ぎたら打ち切る（通信もしない）。
            val expired = teams.pollOnce(deviceCode(expiresInMs = -1_000L))
            assertEquals("コードの有効期限が切れました", (expired as PollResult.Failed).message)
        }

    @Test
    fun pollOnce_keepsWaitingThroughAuthServerOutage() =
        runTest {
            // 人がブラウザでコードを入力済みでも、認証サーバの一時障害 1 回でサインインを捨てない。
            server.enqueue(MockResponse().setResponseCode(503).setBody("<html>Service Unavailable</html>"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"temporarily_unavailable"}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_client","error_description":"設定が不正"}"""))
            val teams = auth()

            assertTrue(teams.pollOnce(deviceCode()) is PollResult.Pending)
            assertTrue(teams.pollOnce(deviceCode()) is PollResult.Pending)
            // 待っても直らない誤りは従来どおり理由を出して打ち切る。
            assertEquals("設定が不正", (teams.pollOnce(deviceCode()) as PollResult.Failed).message)
        }

    @Test
    fun pollOnce_doesNotReportSignInDoneForAnEmptyToken() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"access_token":"","refresh_token":"rt-1","expires_in":3600}"""))
            val store = InMemoryTokenStore()
            val teams = auth(store)

            assertTrue(teams.pollOnce(deviceCode()) is PollResult.Failed)
            assertNull(store.load())
            assertFalse(teams.signedIn())
        }

    @Test
    fun accessToken_returnsSavedTokenWhileFresh() =
        runTest {
            val store = InMemoryTokenStore(Tokens("at-fresh", "rt-1", expiresAt = clock + 600_000L))

            assertEquals("at-fresh", auth(store).accessToken())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun accessToken_refreshesWhenExpiringSoon() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"access_token":"at-2","expires_in":3600}"""))
            // 期限まで 30 秒（余裕 60 秒を切っている）。
            val store = InMemoryTokenStore(Tokens("at-old", "rt-1", expiresAt = clock + 30_000L))

            val token = auth(store).accessToken()
            val body = server.takeRequest().body.readUtf8()

            assertEquals("at-2", token)
            assertTrue(body.contains("grant_type=refresh_token"))
            assertTrue(body.contains("refresh_token=rt-1"))
            // 応答に refresh_token が無いので既存のものを保つ。
            assertEquals("rt-1", store.load()?.refreshToken)
            assertEquals(clock + 3_600_000L, store.load()?.expiresAt)
        }

    @Test
    fun accessToken_forceRefreshIgnoresRemainingLifetime() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"access_token":"at-3","refresh_token":"rt-2","expires_in":3600}"""))
            val store = InMemoryTokenStore(Tokens("at-old", "rt-1", expiresAt = clock + 600_000L))

            assertEquals("at-3", auth(store).accessToken(forceRefresh = true))
            assertEquals("rt-2", store.load()?.refreshToken)
        }

    @Test
    fun accessToken_clearsStoreWhenRefreshTokenIsRevoked() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(400).setBody(
                    """{"error":"invalid_grant","error_description":"AADSTS50173: 失効"}""",
                ),
            )
            val store = InMemoryTokenStore(Tokens("at-old", "rt-dead", expiresAt = clock - 1))
            val teams = auth(store)

            assertNull(teams.accessToken())
            assertNull(store.load())
            assertFalse(teams.signedIn())
        }

    @Test
    fun authorize_reportsNetworkLossDuringRefreshWithoutAskingForSignIn() =
        runTest {
            val store = InMemoryTokenStore(Tokens("at-old", "rt-1", expiresAt = clock - 1))
            val teams = auth(store)
            server.shutdown() // 更新のタイミングで Wi-Fi が切れている

            val error = teams.authorize().exceptionOrNull()

            // 有効なリフレッシュトークンを持ったままデバイスコードの再入力へ送らない。
            assertFalse(error is SignInRequiredException)
            assertEquals("ネットワークに接続できません", error?.message)
            assertEquals("rt-1", store.load()?.refreshToken)
            assertTrue(teams.signedIn())
            assertNull(teams.accessToken())
        }

    @Test
    fun authorize_keepsTokensWhenAuthServerIsTemporarilyDown() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503).setBody("<html>Service Unavailable</html>"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"temporarily_unavailable"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"at-2","expires_in":3600}"""))
            val store = InMemoryTokenStore(Tokens("at-old", "rt-1", expiresAt = clock - 1))
            val teams = auth(store)

            repeat(3) {
                val error = teams.authorize().exceptionOrNull()
                assertFalse(error is SignInRequiredException)
                assertEquals("認証サーバに接続できません", error?.message)
                assertEquals("rt-1", store.load()?.refreshToken)
            }
            // 障害が明ければ、同じリフレッシュトークンでそのまま復帰する。
            assertEquals("at-2", teams.authorize().getOrThrow())
        }

    @Test
    fun authorize_asksForSignInWhenTheGrantCannotBeRefreshed() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"interaction_required"}"""))

            val revoked = InMemoryTokenStore(Tokens("at-old", "rt-dead", expiresAt = clock - 1))
            assertTrue(auth(revoked).authorize().exceptionOrNull() is SignInRequiredException)
            assertNull(revoked.load())

            val needsConsent = InMemoryTokenStore(Tokens("at-old", "rt-1", expiresAt = clock - 1))
            assertTrue(auth(needsConsent).authorize().exceptionOrNull() is SignInRequiredException)
            assertTrue(auth(InMemoryTokenStore()).authorize().exceptionOrNull() is SignInRequiredException)
        }

    @Test
    fun authorize_sharesOneRefreshBetweenConcurrentCallers() =
        runTest {
            // ロビーの取得と写真の送信が同時に期限切れへ当たる状況。更新は 1 回で足りる。
            server.enqueue(
                MockResponse()
                    .setBody("""{"access_token":"at-2","refresh_token":"rt-2","expires_in":3600}""")
                    .setBodyDelay(200, TimeUnit.MILLISECONDS),
            )
            val store = InMemoryTokenStore(Tokens("at-old", "rt-1", expiresAt = clock - 1))
            val teams = auth(store)

            val tokens = listOf(async { teams.authorize() }, async { teams.authorize() }).awaitAll()

            assertEquals(listOf("at-2", "at-2"), tokens.map { it.getOrThrow() })
            assertEquals(1, server.requestCount)
        }

    @Test
    fun authorize_reusesATokenAnotherCallerAlreadyReplacedAfter401() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"access_token":"at-3","expires_in":3600}"""))
            val store = InMemoryTokenStore(Tokens("at-2", "rt-2", expiresAt = clock + 600_000L))
            val teams = auth(store)

            // 401 を受けたのは古い at-1。保存済みは既に at-2 なので取り直さない。
            assertEquals("at-2", teams.authorize(forceRefresh = true, rejected = "at-1").getOrThrow())
            assertEquals(0, server.requestCount)
            // 保存済みのトークン自身が 401 なら更新する。
            assertEquals("at-3", teams.authorize(forceRefresh = true, rejected = "at-2").getOrThrow())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun accessToken_isNullWithoutRefreshToken() =
        runTest {
            val store = InMemoryTokenStore(Tokens("at-old", refreshToken = null, expiresAt = clock - 1))

            assertNull(auth(store).accessToken())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun signedIn_isTrueWhileRefreshTokenExists() {
        assertFalse(auth().signedIn())
        assertTrue(auth(InMemoryTokenStore(Tokens("at", "rt", expiresAt = clock - 1))).signedIn())
        assertTrue(auth(InMemoryTokenStore(Tokens("at", null, expiresAt = clock + 600_000L))).signedIn())
        assertFalse(auth(InMemoryTokenStore(Tokens("at", null, expiresAt = clock + 1_000L))).signedIn())
    }

    @Test
    fun legacyToken_requiresConsentAgainAndIsClearedBeforeUse() =
        runTest {
            val store = InMemoryTokenStore(Tokens("at-old", "rt-old", expiresAt = clock + 600_000L, authVersion = 0))
            val teams = auth(store)

            assertFalse(teams.signedIn())
            assertNull(teams.accessToken())
            assertNull(store.load())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun signOut_dropsSavedTokens() {
        val store = InMemoryTokenStore(Tokens("at", "rt", expiresAt = clock + 600_000L))
        assertNotNull(store.load())

        auth(store).signOut()

        assertNull(store.load())
    }

    private fun deviceCode(expiresInMs: Long = 900_000L) =
        DeviceCode(
            userCode = "F7KQ2M9XZ",
            verificationUri = "https://microsoft.com/devicelogin",
            deviceCode = "dev-123",
            intervalMs = 5_000L,
            expiresAtMs = clock + expiresInMs,
        )
}
