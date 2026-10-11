package com.scraft.glassreport.net.teams

import com.scraft.glassreport.data.prefs.InMemoryTokenStore
import com.scraft.glassreport.data.prefs.Tokens
import com.scraft.glassreport.feature.notification.notificationTitle
import com.scraft.glassreport.net.SignInRequiredException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * 受信（Feed）の HTTP 契約を MockWebServer で確認する。
 * **お客様のチャネルは実キーで叩かない**（読み取り権限はあるが、必要以上に触らない方針）。
 * 応答の形は送付された `read_Teams_Message.py` の実データに合わせている。
 */
class TeamsClientListMessagesTest {
    private lateinit var server: MockWebServer
    private val clock = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        store: InMemoryTokenStore = signedInStore(),
        teamId: String = "team-1",
        channelId: String = "19:abc@thread.tacv2",
    ) = TeamsClient(
        auth =
            TeamsAuth(
                tokens = store,
                tenantId = "tenant-1",
                clientId = "client-1",
                loginBase = server.url("/").toString(),
                now = { clock },
            ),
        teamId = teamId,
        channelId = channelId,
        graphBase = server.url("/v1.0").toString(),
    )

    private fun signedInStore() = InMemoryTokenStore(Tokens("at-1", "rt-1", expiresAt = clock + 600_000L))

    private fun messages(vararg items: String) = """{"@odata.context":"…","value":[${items.joinToString(",")}]}"""

    private fun message(
        id: String,
        author: String = "山田 太郎",
        created: String = "2026-08-14T09:00:00.123Z",
        content: String = "<div>これはテストです</div>",
        type: String = "message",
    ) = """
        {"id":"$id","messageType":"$type","createdDateTime":"$created",
         "from":{"user":{"id":"u-1","displayName":"$author"}},
         "body":{"contentType":"html","content":"$content"},"attachments":[]}
        """.trimIndent()

    @Test
    fun requestsTheNewestFiveWithBearerToken() =
        runTest {
            server.enqueue(MockResponse().setBody(messages(message("1"))))

            client().listMessages().getOrThrow()
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/v1.0/teams/team-1/channels/19%3Aabc%40thread.tacv2/messages?\$top=5&\$expand=replies", request.path)
            assertEquals("Bearer at-1", request.getHeader("Authorization"))
        }

    @Test
    fun configuredTeamAndChannelAreUsedForFeed() =
        runTest {
            server.enqueue(MockResponse().setBody(messages(message("chat-1"))))

            val posts = client(teamId = "team 2", channelId = "19:field/channel@thread.tacv2").listMessages().getOrThrow()
            val request = server.takeRequest()

            assertEquals(listOf("chat-1"), posts.map { it.id })
            assertEquals(
                "/v1.0/teams/team%202/channels/19%3Afield%2Fchannel%40thread.tacv2/messages?\$top=5&\$expand=replies",
                request.path,
            )
            assertEquals("Bearer at-1", request.getHeader("Authorization"))
        }

    @Test
    fun notificationsFailWithoutAChannelId() =
        runTest {
            val result = client(channelId = "").listMessages()

            assertEquals("Teams チャネルが未設定", result.exceptionOrNull()?.message)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun topIsConfigurable() =
        runTest {
            server.enqueue(MockResponse().setBody(messages(message("1"))))

            client().listMessages(top = 3).getOrThrow()

            assertTrue(
                server
                    .takeRequest()
                    .path
                    .orEmpty()
                    .endsWith("?\$top=3&\$expand=replies"),
            )
        }

    @Test
    fun mapsAuthorBodyAndTimestamp() =
        runTest {
            val html = "<div>これはテストです</div><div><img src=\\\"…/hostedContents/1/\$value\\\"></div>"
            server.enqueue(MockResponse().setBody(messages(message("1755000000000", content = html))))

            val posts = client().listMessages().getOrThrow()

            assertEquals(1, posts.size)
            assertEquals("1755000000000", posts[0].id)
            assertEquals("山田 太郎", posts[0].author)
            // HTML は剥がしてプレーンテキストに。画像は本文に展開せず件数だけ。
            assertEquals("これはテストです", posts[0].text)
            assertEquals(1, posts[0].attachmentCount)
            assertEquals(Instant.parse("2026-08-14T09:00:00.123Z").toEpochMilli(), posts[0].createdAtMs)
        }

    @Test
    fun imageOnlyMessageKeepsSenderAsNotificationTitle() =
        runTest {
            val html = "<div><img src=\\\"hostedContents/1/\$value\\\" alt=\\\"画像\\\"></div>"
            server.enqueue(MockResponse().setBody(messages(message("image-1", author = "田中", content = html))))

            val post = client().listMessages().getOrThrow().single()

            assertEquals("田中", post.author)
            assertEquals("", post.text)
            assertEquals(1, post.imageCount)
            assertEquals("田中", notificationTitle(post))
        }

    @Test
    fun imageLabelInMessageBodyDoesNotBecomeNotificationSender() =
        runTest {
            server.enqueue(MockResponse().setBody(messages(message("text-1", author = "田中", content = "<p>画像</p>"))))

            val post = client().listMessages().getOrThrow().single()

            assertEquals("画像", post.text)
            assertEquals("田中", notificationTitle(post))
        }

    @Test
    fun skipsSystemMessagesAndDeletedOnes() =
        runTest {
            val deleted =
                """
                {"id":"3","messageType":"message","createdDateTime":"2026-08-14T08:00:00Z",
                 "deletedDateTime":"2026-08-14T08:30:00Z","from":null,
                 "body":{"contentType":"html","content":"<div></div>"}}
                """.trimIndent()
            server.enqueue(
                MockResponse().setBody(
                    messages(message("1"), message("2", type = "systemEventMessage"), deleted),
                ),
            )

            val posts = client().listMessages().getOrThrow()

            assertEquals(listOf("1"), posts.map { it.id })
        }

    @Test
    fun missingSenderFallsBackToPlaceholder() =
        runTest {
            val noSender =
                """
                {"id":"1","messageType":"message","createdDateTime":"2026-08-14T09:00:00Z",
                 "from":{"application":{"displayName":"bot"}},
                 "body":{"contentType":"html","content":"<div>通知</div>"}}
                """.trimIndent()
            server.enqueue(MockResponse().setBody(messages(noSender)))

            assertEquals("(不明な送信者)", client().listMessages().getOrThrow()[0].author)
        }

    @Test
    fun sortsNewestFirstRegardlessOfServerOrder() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    messages(
                        message("old", created = "2026-08-14T08:00:00Z"),
                        message("new", created = "2026-08-14T09:00:00Z"),
                    ),
                ),
            )

            assertEquals(listOf("new", "old"), client().listMessages().getOrThrow().map { it.id })
        }

    @Test
    fun emptyChannelIsSuccessWithNoPosts() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"value":[]}"""))

            assertEquals(emptyList<Any>(), client().listMessages().getOrThrow())
        }

    private fun thread(
        id: String,
        replies: List<String>,
        next: String? = null,
    ): String =
        JSONObject(message(id, created = "2026-08-14T07:00:00Z"))
            .put("replies", JSONArray(replies.map(::JSONObject)))
            .apply { if (next != null) put("replies@odata.nextLink", next) }
            .toString()

    private fun replyPageUrl(parent: String = "photo-1") =
        server.url("/v1.0/teams/team-1/channels/19%3Aabc%40thread.tacv2/messages/$parent/replies?\$skiptoken=next").toString()

    @Test
    fun expandedPhotoReplyIsIncludedWithItsOwnSenderTimeAndNamespacedId() =
        runTest {
            server.enqueue(
                MockResponse().setBody(messages(thread("photo-1", listOf(message("r-1", author = "担当者", content = "<p>写真を確認しました</p>"))))),
            )

            val posts = client().listMessages().getOrThrow()

            assertEquals(listOf("reply:photo-1:r-1", "photo-1"), posts.map { it.id })
            assertEquals("担当者", posts.first().author)
            assertEquals("写真を確認しました", posts.first().text)
            assertEquals(Instant.parse("2026-08-14T09:00:00.123Z").toEpochMilli(), posts.first().createdAtMs)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun pagesRepliesAndMergesEditedDuplicateWithoutRenotifyingItsId() =
        runTest {
            val next = replyPageUrl()
            val edited =
                JSONObject(message("r-1", content = "<p>訂正</p>"))
                    .put("lastModifiedDateTime", "2026-08-14T10:00:00Z")
                    .toString()
            server.enqueue(MockResponse().setBody(messages(thread("photo-1", listOf(message("r-1")), next))))
            server.enqueue(MockResponse().setBody(messages(edited, message("r-2", created = "2026-08-14T11:00:00Z"))))

            val posts = client().listMessages().getOrThrow()

            assertEquals(listOf("reply:photo-1:r-2", "reply:photo-1:r-1", "photo-1"), posts.map { it.id })
            assertEquals("訂正", posts[1].text)
            server.takeRequest()
            val pageRequest = server.takeRequest()
            assertEquals("GET", pageRequest.method)
            assertEquals("Bearer at-1", pageRequest.getHeader("Authorization"))
            assertTrue(pageRequest.path.orEmpty().endsWith("/photo-1/replies?\$skiptoken=next"))
        }

    @Test
    fun latestReplyWindowDoesNotEvictRootPostsOrCollideAcrossThreads() =
        runTest {
            val replies = (1..6).map { message("r-$it", created = "2026-08-14T09:00:0${it}Z") }
            server.enqueue(
                MockResponse().setBody(
                    messages(thread("photo-1", replies), thread("photo-2", listOf(message("r-6", created = "2026-08-14T10:00:00Z")))),
                ),
            )

            val posts = client().listMessages(top = 2).getOrThrow()

            assertEquals(4, posts.size)
            assertEquals(listOf("reply:photo-2:r-6", "reply:photo-1:r-6"), posts.take(2).map { it.id })
            assertEquals(setOf("photo-1", "photo-2"), posts.takeLast(2).map { it.id }.toSet())
        }

    @Test
    fun dropsDeletedSystemAndMismatchedThreadRepliesIncludingPagedDeletions() =
        runTest {
            val deleted =
                JSONObject(message("r-1"))
                    .put("deletedDateTime", "2026-08-14T10:00:00Z")
                    .put("lastModifiedDateTime", "2026-08-14T10:00:00Z")
                    .toString()
            val wrong = JSONObject(message("wrong")).put("replyToId", "another-parent").toString()
            server.enqueue(
                MockResponse().setBody(
                    messages(
                        thread("photo-1", listOf(message("r-1"), message("system", type = "systemEventMessage"), wrong), replyPageUrl()),
                    ),
                ),
            )
            server.enqueue(MockResponse().setBody(messages(deleted)))

            assertEquals(listOf("photo-1"), client().listMessages().getOrThrow().map { it.id })
        }

    @Test
    fun refusesNextLinksOutsideGraphOriginOrTheCurrentThread() =
        runTest {
            for (next in listOf("https://example.com/replies", replyPageUrl("another-parent"))) {
                server.enqueue(MockResponse().setBody(messages(thread("photo-1", emptyList(), next))))
                assertTrue(client().listMessages().isFailure)
            }
            assertEquals(2, server.requestCount)
        }

    @Test
    fun failsOnRepeatedNextLinkInsteadOfSilentlyLosingReplies() =
        runTest {
            val next = replyPageUrl()
            server.enqueue(MockResponse().setBody(messages(thread("photo-1", emptyList(), next))))
            server.enqueue(MockResponse().setBody(JSONObject(messages(message("r-1"))).put("@odata.nextLink", next).toString()))

            assertTrue(client().listMessages().isFailure)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun replyPageFailureDoesNotReportPartialFeedAsSuccess() =
        runTest {
            server.enqueue(MockResponse().setBody(messages(thread("photo-1", listOf(message("r-1")), replyPageUrl()))))
            server.enqueue(MockResponse().setResponseCode(429))

            assertEquals("Teams が混雑中", client().listMessages().exceptionOrNull()?.message)
        }

    @Test
    fun refusesOversizedExpandedResponse() =
        runTest {
            server.enqueue(MockResponse().setBody(" ".repeat(2 * 1024 * 1024 + 1)))

            assertTrue(client().listMessages().isFailure)
        }

    @Test
    fun unauthorizedRefreshesTokenOnceAndRetries() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"InvalidAuthenticationToken"}}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"at-2","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody(messages(message("1"))))
            val store = signedInStore()

            val posts = client(store).listMessages()

            assertTrue(posts.isSuccess)
            assertEquals(3, server.requestCount)
            assertEquals("Bearer at-1", server.takeRequest().getHeader("Authorization"))
            assertTrue(
                server
                    .takeRequest()
                    .body
                    .readUtf8()
                    .contains("grant_type=refresh_token"),
            )
            assertEquals("Bearer at-2", server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun missingTokensAskForSignInWithoutCallingGraph() =
        runTest {
            val result = client(InMemoryTokenStore()).listMessages()

            assertTrue(result.exceptionOrNull() is SignInRequiredException)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun networkLossWhileRefreshingAnExpiredTokenIsAConnectionErrorNotASignOut() =
        runTest {
            val store = InMemoryTokenStore(Tokens("at-old", "rt-1", expiresAt = clock - 1))
            val teams = client(store)
            server.shutdown()

            val error = teams.listMessages().exceptionOrNull()

            // ロビーは「再試行」を出すだけで、サインイン画面へ自動遷移しない。
            assertFalse(error is SignInRequiredException)
            assertEquals("ネットワークに接続できません", error?.message)
            assertEquals("rt-1", store.load()?.refreshToken)
        }

    @Test
    fun forbiddenExplainsThatTheChannelCannotBeRead() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(403).setBody(
                    """{"error":{"code":"Forbidden","message":"Caller does not have access to the channel"}}""",
                ),
            )

            val result = client().listMessages()

            assertEquals("Teams にアクセスできません", result.exceptionOrNull()?.message)
        }

    @Test
    fun serverErrorIsReportedWithStatus() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"ServiceUnavailable"}}"""))

            assertEquals("Teams サーバ障害", client().listMessages().exceptionOrNull()?.message)
        }

    @Test
    fun brokenJsonIsReportedInsteadOfCrashing() =
        runTest {
            server.enqueue(MockResponse().setBody("これはJSONではない"))

            assertEquals("投稿を読み取れませんでした", client().listMessages().exceptionOrNull()?.message)
        }

    @Test
    fun networkFailureSaysTheGlassCannotReachTeams() =
        runTest {
            server.shutdown()

            assertEquals("ネットワークに接続できません", client().listMessages().exceptionOrNull()?.message)
        }

    @Test
    fun notConfiguredDoesNotCallGraph() =
        runTest {
            val client =
                TeamsClient(
                    auth = TeamsAuth(signedInStore(), tenantId = "tenant-1", clientId = "client-1", loginBase = server.url("/").toString()),
                    teamId = "",
                    channelId = "",
                    graphBase = server.url("/v1.0").toString(),
                )

            assertTrue(client.listMessages().isFailure)
            assertEquals(0, server.requestCount)
        }
}
