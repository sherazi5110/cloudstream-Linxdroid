package android.os

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

open class Looper {
    companion object {
        private val main = Looper()
        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper = main
    }
}

/**
 * Android's Handler for extensions: posted work runs at once (post) or later on a background timer (postDelayed),
 * and can be cancelled (removeCallbacks), e.g. for timeouts and retries.
 */
open class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper? = null) {
    private val pending = ConcurrentHashMap<Runnable, MutableList<ScheduledFuture<*>>>()

    open fun post(r: Runnable): Boolean { r.run(); return true }

    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        lateinit var future: ScheduledFuture<*>
        future = timer.schedule({
            pending[r]?.remove(future)
            r.run()
        }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        pending.getOrPut(r) { java.util.Collections.synchronizedList(mutableListOf()) } += future
        return true
    }

    open fun postDelayed(r: Runnable, @Suppress("UNUSED_PARAMETER") token: Any?, delayMillis: Long): Boolean = postDelayed(r, delayMillis)

    open fun postAtTime(r: Runnable, uptimeMillis: Long): Boolean = postDelayed(r, uptimeMillis - SystemClock.uptimeMillis())

    open fun removeCallbacks(r: Runnable) {
        pending.remove(r)?.forEach { it.cancel(false) }
    }

    open fun removeCallbacks(r: Runnable, @Suppress("UNUSED_PARAMETER") token: Any?) = removeCallbacks(r)

    /** With null, cancels everything this handler has waiting (like Android). */
    open fun removeCallbacksAndMessages(@Suppress("UNUSED_PARAMETER") token: Any?) {
        pending.values.forEach { list -> list.forEach { it.cancel(false) } }
        pending.clear()
    }

    open fun hasCallbacks(r: Runnable): Boolean = pending[r]?.any { !it.isDone } == true

    open fun getLooper(): Looper = Looper.getMainLooper()

    companion object {
        private val timer = Executors.newScheduledThreadPool(2) { r -> Thread(r, "android-handler").apply { isDaemon = true } }
    }
}

/** android.os.SystemClock: the clocks extensions use for timeouts. */
object SystemClock {
    private val start = System.nanoTime()
    @JvmStatic fun elapsedRealtime(): Long = (System.nanoTime() - start) / 1_000_000 + 1_000
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - start + 1_000_000_000
    @JvmStatic fun uptimeMillis(): Long = elapsedRealtime()
    @JvmStatic fun currentThreadTimeMillis(): Long = elapsedRealtime()
    @JvmStatic fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }
}
