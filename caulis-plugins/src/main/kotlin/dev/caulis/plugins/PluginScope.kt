package dev.caulis.plugins

import dev.caulis.core.rethrowIfFatal
import dev.caulis.log.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What one enabling of a plugin registered, taken away when it is disabled, the last first. Every
 * API a plugin gets adds to it; for what the API does not know (a socket, a thread of a library),
 * [add] and [onClose]. Thread-safe. What is added after the end is closed at once, so a thread of
 * the plugin that was still busy cannot leave something behind.
 */
class PluginScope internal constructor(private val log: Logger) : AutoCloseable {
    private class Item(val what: String, val resource: AutoCloseable)

    private val items = ArrayList<Item>()
    private var closed = false

    /** How many things it holds now. */
    val size: Int @Synchronized get() = items.size

    /** What it holds, by kind: "key" to 3, "timer" to 1. */
    val held: Map<String, Int> @Synchronized get() = items.groupingBy { it.what }.eachCount().toSortedMap()

    /** Keeps [resource] until the end; [what] names its kind in `plugin list`. */
    @JvmOverloads
    fun <T : AutoCloseable> add(resource: T, what: String = "resource"): T {
        val late = synchronized(this) {
            if (!closed) items += Item(what, resource)
            closed
        }
        if (late) closeOne(what, resource)
        return resource
    }

    /**
     * Like [add], and closing the result takes [resource] away at once and forgets it (a timer the
     * plugin stops itself: nothing piles up).
     */
    @JvmOverloads
    fun tracked(resource: AutoCloseable, what: String = "resource"): AutoCloseable {
        val item = Item(what, resource)
        val late = synchronized(this) {
            if (!closed) items += item
            closed
        }
        if (late) closeOne(what, resource)
        return AutoCloseable {
            val mine = synchronized(this) { items.remove(item) }
            if (mine) resource.close()
        }
    }

    /** Runs [action] at the end. */
    fun onClose(action: () -> Unit): AutoCloseable = tracked(AutoCloseable(action), "cleanup")

    /** The same for Java. */
    fun onClose(action: Runnable): AutoCloseable = tracked(AutoCloseable { action.run() }, "cleanup")

    /** A daemon thread running [work]; interrupted at the end, and waited for a moment. */
    fun thread(name: String, work: Runnable): Thread {
        val thread = Thread.ofPlatform().daemon().name(name).unstarted(work)
        add(
            AutoCloseable {
                thread.interrupt()
                if (Thread.currentThread() !== thread) thread.join(THREAD_WAIT_MILLIS)
                if (thread.isAlive) log.warn("The thread \"$name\" did not end when asked to")
            },
            "thread",
        )
        thread.start()
        return thread
    }

    /** A pool of [threads] daemon threads; shut down at the end. */
    @JvmOverloads
    fun executor(name: String, threads: Int = 1): ExecutorService {
        val pool = Executors.newFixedThreadPool(threads) { task -> Thread(task, name).apply { isDaemon = true } }
        add(
            AutoCloseable {
                pool.shutdownNow()
                pool.awaitTermination(THREAD_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            },
            "threads",
        )
        return pool
    }

    /** Takes everything away, the last first; one that fails is logged, the rest still goes. Twice does nothing. */
    override fun close() {
        val all = synchronized(this) {
            closed = true
            items.toList().also { items.clear() }
        }
        for (item in all.asReversed()) closeOne(item.what, item.resource)
    }

    private fun closeOne(what: String, resource: AutoCloseable) {
        try {
            resource.close()
            log.trace { "Taken away: $what" }
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.warn("Taking away a $what failed: $e")
        }
    }

    private companion object {
        const val THREAD_WAIT_MILLIS = 300L
    }
}
