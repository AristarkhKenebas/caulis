package dev.caulis.plugins

import dev.caulis.log.Logger
import java.lang.ref.WeakReference
import java.util.concurrent.locks.LockSupport

/**
 * Watches the calls into plugins on the compositor's thread, from a thread of its own. It only
 * exists while a plugin is loaded or a class loader is waited for: with no plugin there is no
 * thread at all. It also looks whether the class loaders of unloaded plugins were collected.
 *
 * The compositor's thread must not wait here: entering and leaving write a few fields of arrays
 * made once, with no allocation. A thread on the JVM cannot be killed: a call that never returns
 * can be reported, interrupted and written down (see [PluginOptions]), not ended.
 */
class PluginWatchdog internal constructor(
    private val options: PluginOptions,
    private val log: Logger,
    /** A call took [PluginOptions.hangReportMillis] and goes on: from this thread. */
    private val reportHang: (id: String, what: String, millis: Long, stack: String) -> Unit,
    /** A call took [PluginOptions.hangQuarantineMillis]: from this thread. */
    private val quarantine: (id: String) -> Unit,
    /** Class loaders left behind: from this thread. */
    private val reportLeak: (id: String, name: String) -> Unit,
) {
    @Volatile
    private var hostThread: Thread? = null

    // The calls running on the compositor's thread, inner ones last. Written by that thread alone;
    // read by the watchdog after [depth] (a volatile).
    private val guards = arrayOfNulls<PluginGuard>(MAX_DEPTH)
    private val whats = arrayOfNulls<String>(MAX_DEPTH)
    private val starts = LongArray(MAX_DEPTH)
    private val reported = BooleanArray(MAX_DEPTH)

    @Volatile
    private var depth = 0

    private var interruptSent = false
    private val lock = Any()

    /** Set when a call went on past the report limit; the manager acts when it has returned. */
    internal var onHungCallDone: ((PluginGuard, String, Long) -> Unit)? = null

    /** Calls from now on made on [thread] are watched. */
    internal fun watch(thread: Thread) {
        hostThread = thread
    }

    /** A call begins; true if it is timed (then [leave] must follow). */
    @PublishedApi
    internal fun enter(guard: PluginGuard, what: String): Boolean {
        if (Thread.currentThread() !== hostThread) return false
        val at = depth
        if (at >= MAX_DEPTH) return false // (never in real use: only that it is not timed)
        guards[at] = guard
        whats[at] = what
        starts[at] = System.nanoTime()
        reported[at] = false
        depth = at + 1
        return true
    }

    /** The call begun last ended. */
    @PublishedApi
    internal fun leave() {
        val at: Int
        val wasReported: Boolean
        // (With the watchdog's check of the same call: an interrupt never hits the code after it.)
        synchronized(lock) {
            at = depth - 1
            wasReported = reported[at]
            depth = at
            if (interruptSent) {
                interruptSent = false
                Thread.interrupted()
            }
        }
        val guard = guards[at]
        val what = whats[at]
        guards[at] = null
        whats[at] = null
        if (wasReported && guard != null) onHungCallDone?.invoke(guard, what.orEmpty(), (System.nanoTime() - starts[at]) / 1_000_000)
    }

    // --- its thread: only while there is something to watch ------------------------------------

    private var watchers = 0
    private var thread: Thread? = null
    private val leaks = ArrayList<Leak>()

    private class Leak(val id: String, val name: String, val reference: WeakReference<ClassLoader>, val at: Long, var tries: Int = 0)

    /** A plugin is loaded: the watchdog runs at least until the matching [stopWatching]. */
    @Synchronized
    internal fun startWatching() {
        watchers++
        ensureThread()
    }

    /** A plugin is gone. */
    @Synchronized
    internal fun stopWatching() {
        watchers--
    }

    /** Looks later whether [loader] (of an unloaded plugin) was collected. */
    @Synchronized
    internal fun checkCollected(id: String, loader: ClassLoader) {
        if (options.leakCheckMillis <= 0) return
        leaks += Leak(id, loader.name ?: id, WeakReference(loader), System.nanoTime() + options.leakCheckMillis * 1_000_000)
        ensureThread()
    }

    @Synchronized
    private fun ensureThread() {
        if (thread != null) return
        thread = Thread.ofPlatform().daemon().name("caulis-plugin-watchdog").start {
            log.trace { "The watchdog runs" }
            while (keepRunning()) {
                LockSupport.parkNanos(PERIOD_NANOS)
                try {
                    lookAtCalls()
                    lookAtLeaks()
                } catch (e: RuntimeException) {
                    log.warn("The watchdog: $e")
                }
            }
            log.trace { "The watchdog ended: nothing to watch" }
        }
    }

    // (False once: it ends; the next plugin starts a new one.)
    @Synchronized
    private fun keepRunning(): Boolean {
        if (watchers > 0 || leaks.isNotEmpty()) return true
        thread = null
        return false
    }

    private var interruptedStart = 0L
    private var quarantinedStart = 0L

    private fun lookAtCalls() {
        val host = hostThread ?: return
        val at = depth - 1
        if (at < 0) return
        val guard = guards[at] ?: return
        val start = starts[at]
        val what = whats[at].orEmpty()
        val millis = (System.nanoTime() - start) / 1_000_000
        // (Each only once per call; a start time says which call it is.)
        if (options.hangReportMillis in 1..millis && !reported[at]) {
            val stack = host.stackTrace.take(STACK_LINES).joinToString("\n") { "    at $it" }
            val same = synchronized(lock) {
                (depth - 1 == at && starts[at] == start).also { if (it) reported[at] = true }
            }
            if (same) {
                reportHang(guard.pluginId, what, millis, stack)
            }
        }
        if (options.hangInterruptMillis in 1..millis && interruptedStart != start) {
            interruptedStart = start
            synchronized(lock) {
                if (depth - 1 == at && starts[at] == start) {
                    interruptSent = true
                    host.interrupt()
                }
            }
            log.warn("Plugin ${guard.pluginId}: interrupted its call ($what) after $millis ms")
        }
        if (options.hangQuarantineMillis in 1..millis && quarantinedStart != start) {
            quarantinedStart = start
            quarantine(guard.pluginId)
        }
    }

    private var nextGc = 0L

    private fun lookAtLeaks() {
        val now = System.nanoTime()
        val due = synchronized(this) { leaks.filter { now - it.at >= 0 } }
        if (due.isEmpty() || now - nextGc < 0) return
        // (Only after a plugin was really unloaded, and not often: class loaders go in a full collection.)
        System.gc()
        nextGc = now + LEAK_TRY_GAP_NANOS
        for (leak in due) {
            val left = leak.reference.get() != null
            if (left && ++leak.tries < LEAK_TRIES) continue
            synchronized(this) { leaks.remove(leak) }
            if (left) reportLeak(leak.id, leak.name) else log.trace { "${leak.name} was collected" }
        }
    }

    internal companion object {
        const val MAX_DEPTH = 64
        const val PERIOD_NANOS = 200_000_000L
        const val STACK_LINES = 24
        const val LEAK_TRIES = 3
        const val LEAK_TRY_GAP_NANOS = 2_000_000_000L
    }
}
