package com.scraft.glassreport.di

import com.scraft.glassreport.capture.camera.PHOTO_TEAMS_MAX_EDGE
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.net.MIME_MP4
import com.scraft.glassreport.net.MediaSink
import com.scraft.glassreport.net.SendOutcome
import com.scraft.glassreport.test.Jpegs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宛先の組み立て。**Teams だけ長辺を落とし、受信モニタへは無加工で渡す**ことを固める。
 *
 * 縮小そのものは `android.graphics` なので、ここでは同じ契約の ImageIO 実装
 * （[Jpegs.downscaler]）を差し込み、フェイク宛先が受け取ったバイト列を実際に読んで確かめる。
 */
class MediaSinksTest {
    /** 設定「無加工」で撮れる実寸。この寸法のまま Teams へ投げると本文が数 MB になる。 */
    private val original = Jpegs.jpeg(4032, 3024)

    private val teams = RecordingSink("Teams", setOf(MIME_JPEG))

    private fun sinks() = mediaSinks(teams, Jpegs.downscaler)

    @Test
    fun teams_receivesAPhotoWithinTheHostedContentsLimit() =
        runTest {
            sinks()[0].send(original, MIME_JPEG, "配管に漏れ")

            val sent = teams.sent.single()
            assertTrue(
                "Teams へ渡った長辺 ${Jpegs.longEdge(sent.bytes)}px",
                Jpegs.longEdge(sent.bytes) <= PHOTO_TEAMS_MAX_EDGE,
            )
            assertTrue(sent.bytes.size < original.size)
            // 縮小してもコメントと Content-Type はそのまま届く。
            assertEquals(MIME_JPEG, sent.mime)
            assertEquals("配管に漏れ", sent.comment)
        }

    @Test
    fun photoAlreadyWithinTheLimit_isNotReEncoded() =
        runTest {
            val small = Jpegs.jpeg(1920, 1440)

            sinks()[0].send(small, MIME_JPEG, "")

            assertSame(small, teams.sent.single().bytes)
        }

    @Test
    fun wrappingTeams_keepsItsLabelAndAcceptedTypes() =
        runTest {
            val wrapped = sinks()[0]

            // 撮影画面・設定画面は label で宛先を選ぶので、包んでも見え方が変わってはいけない。
            assertEquals("Teams", wrapped.label)
            assertEquals("Teams チャネル", wrapped.detail)
            assertTrue(wrapped.accepts(MIME_JPEG))
            assertFalse(wrapped.accepts(MIME_MP4))
        }

    @Test
    fun missingTeams_producesNoDestinationOrHttpFallback() {
        val only = mediaSinks(teams = null, downscaler = Jpegs.downscaler)

        assertTrue(only.isEmpty())
    }

    @Test
    fun teams_resolvesTheRecordedVoiceIntoAComment() =
        runTest {
            val wav = byteArrayOf(1, 2, 3)
            val wrapped =
                teams.withVoiceComment { bytes ->
                    assertSame(wav, bytes)
                    Result.success("音声コメント")
                }

            wrapped.send(original, MIME_JPEG, "", wav)

            assertEquals("音声コメント", teams.sent.single().comment)
        }

    @Test
    fun teams_doesNotRecognizeAgainWhenACommentAlreadyExists() =
        runTest {
            val wrapped = teams.withVoiceComment { Result.failure(AssertionError("呼ばれない")) }

            wrapped.send(original, MIME_JPEG, "既存コメント", byteArrayOf(1))

            assertEquals("既存コメント", teams.sent.single().comment)
        }

    /** 送られたものを控えるだけのフェイク宛先。 */
    private class RecordingSink(
        override val label: String,
        private val accepted: Set<String>,
    ) : MediaSink {
        override val detail: String = "$label チャネル"
        val sent = mutableListOf<Sent>()

        override fun accepts(mime: String): Boolean = mime in accepted

        override suspend fun send(
            bytes: ByteArray,
            mime: String,
            comment: String,
            voice: ByteArray?,
        ): Result<SendOutcome> {
            sent += Sent(bytes, mime, comment)
            return Result.success(SendOutcome(elapsedMs = 10L))
        }

        class Sent(
            val bytes: ByteArray,
            val mime: String,
            val comment: String,
        )
    }
}
