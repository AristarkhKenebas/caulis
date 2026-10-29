package dev.caulis.event

/**
 * Watches an [EventLoop] from a separate thread. If the loop stops coming round (an
 * endless loop or a deadlock somewhere), the screen would freeze for good. Instead:
 *
 * - after [warnMillis]: the loop thread's stack is written to the log (where it hangs);
 * - after [giveUpMillis]: [onStuck] runs, by default ending the process. The kernel then
 *   gives the screen and input devices back to the system, so the user gets a working
 *   text console instead of a frozen picture.
 */
class Watchdog(
    private val loop: EventLoop,
    private val loopThread: Thread = Thread.currentThread(),
    private val warnMillis: Long = 2_000,
    private val giveUpMillis: Long = 10_000,
    private val onStuck: () -> Unit = { Runtime.getRuntime().halt(EXIT_STUCK) },
) : AutoCloseable {

    private val logger = dev.caulis.log.Logger("native")

    @Volatile
    private var running = true

    private val thread = Thread.ofPlatform().daemon().name("caulis-watchdog").start {
        var warned = false
        // What the whole program went through since the loop last came round: to tell a stuck
        // loop from a paused program (a long garbage collection, the system swapping).
        var calm = Pause.now()
        var latest = 0L
        while (running) {
            val slept = System.nanoTime()
            try {
                Thread.sleep(CHECK_MILLIS)
            } catch (e: InterruptedException) {
                return@start
            }
            // (Asleep much longer than asked: this thread was not run either.)
            latest = maxOf(latest, (System.nanoTime() - slept) / 1_000_000 - CHECK_MILLIS)
            val stuckFor = (System.nanoTime() - loop.lastIterationNanos) / 1_000_000
            when {
                stuckFor >= giveUpMillis -> {
                    logger.error("The main loop has not responded for $stuckFor ms; stopping")
                    onStuck()
                    return@start
                }
                stuckFor >= warnMillis && !warned -> {
                    warned = true
                    val stack = loopThread.stackTrace.joinToString("\n") { "    at $it" }
                    logger.warn("The main loop is stuck for $stuckFor ms (${Pause.now().since(calm, latest)}):\n$stack")
                }
                stuckFor < warnMillis -> {
                    warned = false
                    calm = Pause.now()
                    latest = 0L
                }
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    // The program's garbage collection time and the pages it had to read back (from swap or
    // disk: "major faults") so far.
    internal class Pause(val gcMillis: Long, val majorFaults: Long?) {
        /** What happened since [before], in words; [watchdogLate]: how late this thread itself woke at most. */
        fun since(before: Pause, watchdogLate: Long): String {
            val faults = if (majorFaults != null && before.majorFaults != null) majorFaults - before.majorFaults else null
            val gc = gcMillis - before.gcMillis
            val why = when {
                gc >= 1000 -> "; mostly garbage collection"
                faults != null && faults >= 1000 -> "; the system was swapping"
                watchdogLate >= 1000 -> "; the whole program was not run (the system was busy)"
                else -> ""
            }
            return "meanwhile: garbage collection $gc ms, ${faults ?: "?"} pages read back from swap or disk, this watchdog woke up to $watchdogLate ms late$why"
        }

        companion object {
            fun now(): Pause = Pause(
                runCatching { java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) } }.getOrDefault(0L),
                majorFaults(runCatching { java.nio.file.Files.readString(java.nio.file.Path.of("/proc/self/stat")) }.getOrNull()),
            )

            /** The major faults in a /proc/<pid>/stat line (its 12th field; the name in brackets may hold spaces). */
            fun majorFaults(stat: String?): Long? = stat?.substringAfterLast(')')?.trim()?.split(' ')?.getOrNull(9)?.toLongOrNull()
        }
    }

    companion object {
        private const val CHECK_MILLIS = 500L

        /** Exit code when the watchdog ended the process. */
        const val EXIT_STUCK = 70
    }
}
