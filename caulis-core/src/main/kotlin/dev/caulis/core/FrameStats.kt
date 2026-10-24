package dev.caulis.core

import jdk.jfr.consumer.RecordingStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures how smoothly one screen runs and prints a summary every few seconds:
 *
 * ```
 * eDP-1: 143.8 fps | draw 0.21 ms avg, 0.80 max | late 0 | GC pauses 2, longest 0.04 ms
 * ```
 *
 * - draw: time spent preparing a frame (CPU side);
 * - late: frames that reached the screen one or more refreshes later than they could have
 *   (each one is a visible stutter);
 * - GC pauses: moments the garbage collector stopped the program (should be well under a
 *   millisecond with ZGC).
 *
 * Call [drawStarted] / [drawFinished] around drawing and [presented] when the frame is on
 * screen. Measuring itself costs almost nothing, so it can stay on.
 */
class FrameStats(
    private val name: String,
    private val intervalNanos: Long = 5_000_000_000,
    private val print: (String) -> Unit = ::println,
) : AutoCloseable {

    private var frames = 0
    private var late = 0
    private var drawTotalNanos = 0L
    private var drawMaxNanos = 0L
    private var drawStart = 0L
    private var lastDrawStart = 0L
    private var periodStart = System.nanoTime()

    /** A frame is being drawn now. */
    fun drawStarted() {
        drawStart = System.nanoTime()
    }

    /** The frame is drawn and handed to the GPU. */
    fun drawFinished() {
        val took = System.nanoTime() - drawStart
        drawTotalNanos += took
        drawMaxNanos = maxOf(drawMaxNanos, took)
        lastDrawStart = drawStart
    }

    /**
     * The frame reached the screen at [nanos] (monotonic clock). [refreshNanos] is the
     * monitor's period; 0 if unknown (then lateness is not judged).
     */
    fun presented(nanos: Long, refreshNanos: Long) {
        frames++
        // Drawing starts right after a refresh, so the picture is due at the next one.
        if (refreshNanos > 0 && lastDrawStart > 0 && nanos - lastDrawStart > refreshNanos * 3 / 2) late++
        val now = System.nanoTime()
        if (now - periodStart >= intervalNanos) report(now)
    }

    private fun report(now: Long) {
        val seconds = (now - periodStart) / 1e9
        val pauses = gcPauses.getAndSet(0)
        val longest = gcLongestMicros.getAndSet(0)
        print(
            "%s: %.1f fps | draw %.2f ms avg, %.2f max | late %d | GC pauses %d, longest %.2f ms".format(
                name, frames / seconds,
                if (frames > 0) drawTotalNanos / frames / 1e6 else 0.0, drawMaxNanos / 1e6,
                late, pauses, longest / 1000.0,
            ),
        )
        frames = 0
        late = 0
        drawTotalNanos = 0
        drawMaxNanos = 0
        periodStart = now
    }

    override fun close() = GcWatch.stop()

    init {
        GcWatch.start()
    }

    private val gcPauses get() = GcWatch.pauses
    private val gcLongestMicros get() = GcWatch.longestMicros

    /**
     * Watches the garbage collector, once for all [FrameStats], through Java Flight
     * Recorder: it reports every collection with its pauses in nanoseconds.
     */
    private object GcWatch {
        val pauses = AtomicLong()
        val longestMicros = AtomicLong()
        private var users = 0
        private var stream: RecordingStream? = null

        @Synchronized
        fun start() {
            if (users++ > 0) return
            stream = RecordingStream().apply {
                enable("jdk.GarbageCollection").withThreshold(Duration.ZERO)
                onEvent("jdk.GarbageCollection") { event ->
                    val longest = event.getDuration("longestPause")
                    if (longest.isZero) return@onEvent // a collection without stopping the program
                    pauses.incrementAndGet()
                    longestMicros.accumulateAndGet(longest.toNanos() / 1000) { a, b -> maxOf(a, b) }
                }
                startAsync()
            }
        }

        @Synchronized
        fun stop() {
            if (users == 0 || --users > 0) return
            stream?.close()
            stream = null
        }
    }
}
