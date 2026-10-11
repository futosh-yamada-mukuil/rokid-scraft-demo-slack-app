package com.scraft.glassreport.capture.asr

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 認識モデルの先読みを、JNI を使わずに固める。ここで守るのは 3 点:
 * 二重に作らない／使われなければ必ず手放す／利用側がロックを持つ間は解放しない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreparedResourceTest {
    private class Model(
        val number: Int,
    )

    private class Fixture(
        scope: TestScope,
        loadMs: Long = 0,
        private val failure: Throwable? = null,
    ) {
        val lock = Mutex()
        val created = mutableListOf<Model>()
        val released = mutableListOf<Model>()
        val held = mutableListOf<Boolean>()
        val errors = mutableListOf<Throwable>()
        val resource =
            PreparedResource(
                scope = scope.backgroundScope,
                lock = lock,
                holdMs = 10_000,
                create = {
                    delay(loadMs)
                    failure?.let { throw it }
                    Model(created.size + 1).also(created::add)
                },
                release = released::add,
                onHeldChanged = held::add,
                onError = errors::add,
            )

        suspend fun take(): Model? = lock.withLock { resource.takeLocked() }
    }

    @Test
    fun preparedInstanceIsHandedOverOnceAndThenBelongsToTheUser() =
        runTest {
            val fixture = Fixture(this)
            fixture.resource.prepare()
            runCurrent()

            assertSame(fixture.created.single(), fixture.take())
            assertNull(fixture.take())
            advanceTimeBy(60_000)

            // 受け取った側が解放する。保持期限が後から解放し直すことはない。
            assertTrue(fixture.released.isEmpty())
            assertEquals(listOf(true), fixture.held)
        }

    @Test
    fun unusedInstanceIsReleasedWhenTheHoldTimeEnds() =
        runTest {
            val fixture = Fixture(this)
            fixture.resource.prepare()
            runCurrent()
            advanceTimeBy(9_999)
            assertTrue(fixture.released.isEmpty())

            advanceTimeBy(2)

            assertEquals(fixture.created, fixture.released)
            assertEquals(listOf(true, false), fixture.held)
            assertNull(fixture.take())
        }

    @Test
    fun repeatedPrepareCreatesOnceAndExtendsTheHold() =
        runTest {
            val fixture = Fixture(this)
            fixture.resource.prepare()
            runCurrent()
            advanceTimeBy(8_000)
            // 録音の再開（一時停止からの続き）。作り直さず、期限だけ延ばす。
            fixture.resource.prepare()
            runCurrent()
            advanceTimeBy(8_000)

            assertEquals(1, fixture.created.size)
            assertTrue(fixture.released.isEmpty())
            advanceTimeBy(2_001)
            assertEquals(fixture.created, fixture.released)
        }

    @Test
    fun discardReleasesAndALaterPrepareStillCreates() =
        runTest {
            val fixture = Fixture(this)
            fixture.resource.prepare()
            runCurrent()
            // 取り消してすぐ録り直す。先の解放が後の準備まで無効にしてはいけない。
            fixture.resource.discard()
            fixture.resource.prepare()
            runCurrent()

            assertEquals(listOf(1), fixture.released.map { it.number })
            assertEquals(2, fixture.take()?.number)
        }

    @Test
    fun userArrivingDuringALongLoadWaitsForItInsteadOfLoadingTwice() =
        runTest {
            val fixture = Fixture(this, loadMs = 3_000)
            fixture.resource.prepare()
            runCurrent()
            // 録音をすぐ止めた場合。読み込みはまだ終わっていない。
            val taken = async { fixture.take() }
            advanceTimeBy(3_001)

            assertEquals(1, taken.await()?.number)
            assertEquals(1, fixture.created.size)
        }

    @Test
    fun failedCreationHoldsNothingAndLeavesTheUserToCreateItsOwn() =
        runTest {
            val failure = IllegalStateException("オフライン認識用メモリが不足しています")
            val fixture = Fixture(this, failure = failure)
            fixture.resource.prepare()
            runCurrent()

            assertNull(fixture.take())
            assertEquals(listOf<Throwable>(failure), fixture.errors)
            assertEquals(listOf(true, false), fixture.held)
        }

    @Test
    fun prepareThatOnlyStartsAfterTheUserTookOverCreatesNothing() =
        runTest {
            val fixture = Fixture(this)
            fixture.lock.lock()
            fixture.resource.prepare()
            runCurrent()
            // 利用側が先にロックを取り、自分で作って使い終えた。
            assertNull(fixture.resource.takeLocked())
            fixture.lock.unlock()
            runCurrent()
            advanceTimeBy(60_000)

            // 遅れて動き出した準備が、誰も使わないモデルを残さない。
            assertTrue(fixture.created.isEmpty())
            assertTrue(fixture.held.isEmpty())
        }

    @Test
    fun nothingIsReleasedWhileTheUserHoldsTheLock() =
        runTest {
            val fixture = Fixture(this)
            fixture.resource.prepare()
            runCurrent()
            fixture.lock.lock()
            // 認識中（ロック保持中）に画面を閉じた、保持期限が来た、の両方。
            fixture.resource.discard()
            advanceTimeBy(60_000)
            assertTrue(fixture.released.isEmpty())

            fixture.lock.unlock()
            runCurrent()

            assertEquals(fixture.created, fixture.released)
        }
}
