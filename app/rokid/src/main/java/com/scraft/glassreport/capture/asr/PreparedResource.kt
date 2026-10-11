package com.scraft.glassreport.capture.asr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 作るのに時間が掛かるもの（認識モデル）を、**使う前に作っておく**ための入れ物。
 *
 * 5秒の録音が終わってからモデルを読み込むと、その秒数がそのまま「文字起こし中」の待ちになる。
 * 録音の開始と同時に [prepare] しておけば、読み込みは録音時間の裏で終わる。
 *
 * 取り決め:
 * - 生成・受け渡し・解放はすべて [lock] の内側で行う。利用側も同じ [lock] を保持して使うので、
 *   使用中に解放が走ることはない（JNI の認識器は使用中に解放すると落ちる）。
 * - [takeLocked] で受け取った側が所有者になり、解放も受け取った側が行う。
 * - 使われなかったものは [holdMs] 後に自動で解放する（メモリを抱えたままにしない）。
 * - [create] の失敗は握って [onError] へ渡すだけ。利用側は [takeLocked] が null なら自分で作る。
 *
 * [onHeldChanged] は「作成中〜保持中」に true、解放・失敗で false。受け渡した後は利用側の責任。
 */
internal class PreparedResource<T : Any>(
    private val scope: CoroutineScope,
    private val lock: Mutex,
    private val holdMs: Long,
    private val create: suspend () -> T,
    private val release: (T) -> Unit,
    private val onHeldChanged: (Boolean) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    /** [prepare] の通し番号。[settled] 以下の要求は、処理される前に不要になったもの。 */
    private val requested = AtomicInteger()

    // 以下は [lock] の内側でだけ触る。
    private var settled = 0
    private var held: T? = null
    private var expiry: Job? = null

    /** 近いうちに使うので作り始める。保持中なら保持期限だけ延ばす。 */
    fun prepare() {
        val ticket = requested.incrementAndGet()
        scope.launch {
            lock.withLock {
                if (ticket <= settled) return@withLock
                val existing = held
                if (existing != null) {
                    holdFor(existing)
                    return@withLock
                }
                onHeldChanged(true)
                val created =
                    try {
                        create()
                    } catch (cancelled: CancellationException) {
                        onHeldChanged(false)
                        throw cancelled
                    } catch (error: Throwable) {
                        onHeldChanged(false)
                        onError(error)
                        return@withLock
                    }
                held = created
                holdFor(created)
            }
        }
    }

    /**
     * **[lock] を保持した状態で呼ぶ。** 用意できていれば渡し、無ければ null。
     * これより前の [prepare] はここで用済みになる（遅れて動き出しても作らない）。
     */
    fun takeLocked(): T? = takeUpTo(requested.get())

    /** 使わないことになった。保持していれば解放する。この呼び出しより後の [prepare] は有効。 */
    fun discard() {
        val cutoff = requested.get()
        scope.launch {
            lock.withLock {
                takeUpTo(cutoff)?.let {
                    release(it)
                    onHeldChanged(false)
                }
            }
        }
    }

    private fun takeUpTo(cutoff: Int): T? {
        settled = maxOf(settled, cutoff)
        expiry?.cancel()
        expiry = null
        return held.also { held = null }
    }

    private fun holdFor(resource: T) {
        expiry?.cancel()
        expiry =
            scope.launch {
                delay(holdMs)
                lock.withLock {
                    if (held === resource) {
                        held = null
                        expiry = null
                        release(resource)
                        onHeldChanged(false)
                    }
                }
            }
    }
}
