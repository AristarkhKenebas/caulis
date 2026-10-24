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

    private val logger = System.getLogger("caulis.watchdog")

    @Volatile
    private var running = true

    private val thread = Thread.ofPlatform().daemon().name("caulis-watchdog").start {
        var warned = false
        while (running) {
            try {
                Thread.sleep(CHECK_MILLIS)
            } catch (e: InterruptedException) {
                return@start
            }
            val stuckFor = (System.nanoTime() - loop.lastIterationNanos) / 1_000_000
            when {
                stuckFor >= giveUpMillis -> {
                    logger.log(System.Logger.Level.ERROR, "The main loop has not responded for $stuckFor ms; stopping")
                    onStuck()
                    return@start
                }
                stuckFor >= warnMillis && !warned -> {
                    warned = true
                    val stack = loopThread.stackTrace.joinToString("\n") { "    at $it" }
                    logger.log(System.Logger.Level.WARNING, "The main loop is stuck for $stuckFor ms:\n$stack")
                }
                stuckFor < warnMillis -> warned = false
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    companion object {
        private const val CHECK_MILLIS = 500L

        /** Exit code when the watchdog ended the process. */
        const val EXIT_STUCK = 70
    }
}
