package com.scraft.glassreport.net

import com.scraft.glassreport.test.Jpegs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 宛先を包んで上限を掛ける decorator そのものの契約。 */
class DownscalingSinkTest {
    private val received = mutableListOf<ByteArray>()

    private val delegate =
        object : MediaSink {
            override val label: String = "宛先"
            override val detail: String = "詳細"
            var comment: String = ""

            override fun accepts(mime: String): Boolean = true

            override suspend fun send(
                bytes: ByteArray,
                mime: String,
                comment: String,
                voice: ByteArray?,
            ): Result<SendOutcome> {
                received += bytes
                this.comment = comment
                return Result.success(SendOutcome(elapsedMs = 1L))
            }
        }

    private fun sink(maxEdge: Int = 1_920) = DownscalingSink(delegate, maxEdge, Jpegs.downscaler)

    @Test
    fun photoOverTheLimit_isShrunkBeforeItReachesTheDelegate() =
        runTest {
            val big = Jpegs.jpeg(3_000, 4_000)

            sink().send(big, MIME_JPEG, "コメント")

            assertEquals(1_920, Jpegs.longEdge(received.single()))
            assertEquals("コメント", delegate.comment)
        }

    @Test
    fun video_isPassedThroughUntouched() =
        runTest {
            // 動画は縮小の対象外（そもそも Teams へは送れないが、decorator 自体は宛先を選ばない）。
            val mp4 = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte())

            sink().send(mp4, MIME_MP4, "")

            assertSame(mp4, received.single())
        }

    @Test
    fun labelDetailAndAccepts_areDelegated() {
        val wrapped = sink()

        assertEquals("宛先", wrapped.label)
        assertEquals("詳細", wrapped.detail)
        assertTrue(wrapped.accepts(MIME_MP4))
    }
}
