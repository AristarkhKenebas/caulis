package dev.caulis.wm

import dev.caulis.core.Curve
import dev.caulis.core.Drawing
import dev.caulis.core.DrawingLevel
import dev.caulis.core.Monitor
import dev.caulis.core.OverlayCanvas
import dev.caulis.log.Logger
import dev.caulis.render.Color
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * The start, smooth: the screens stay [color] (black) — no half-drawn frames, no panel popping
 * in — until the compositor is warm: its first frames drawn (shaders compiled on the way) and
 * the work you asked it to wait for done ([hold], [whenWarm]); then the desktop fades in over
 * [fade]. Never longer than [maxWait]. Programs of [Autostart] start after it.
 *
 * ```
 * reveal {
 *     enabled = true
 *     fade = 600.milliseconds
 *     maxWait = 4.seconds
 *     whenWarm("wallpaper") { done -> loadWallpaper(); done() }
 * }
 * onReveal { notify("Hello") }
 * ```
 */
class Reveal : StartupSettings() {

    /**
     * On or off (off by default: the desktop shows as soon as it is drawn). Only at the start:
     * a reload does not hide the desktop again.
     */
    var enabled = false

    /** How long the desktop takes to fade in, in milliseconds (0: at once). */
    var fadeMillis = 600L
        set(value) {
            require(value in 0..60_000) { "reveal: fade is 0 to 60 s, not $value ms" }
            field = value
        }

    /** [fadeMillis] as a Duration: `fade = 600.milliseconds`. */
    @get:JvmSynthetic
    @set:JvmSynthetic
    var fade: Duration
        get() = fadeMillis.toDuration(DurationUnit.MILLISECONDS)
        set(value) {
            fadeMillis = value.inWholeMilliseconds
        }

    /** The longest the screen stays dark, in milliseconds: then it fades in whatever is still being waited for. */
    var maxWaitMillis = 4_000L
        set(value) {
            require(value in 0..120_000) { "reveal: maxWait is 0 to 120 s, not $value ms" }
            field = value
        }

    /** [maxWaitMillis] as a Duration: `maxWait = 4.seconds`. */
    @get:JvmSynthetic
    @set:JvmSynthetic
    var maxWait: Duration
        get() = maxWaitMillis.toDuration(DurationUnit.MILLISECONDS)
        set(value) {
            maxWaitMillis = value.inWholeMilliseconds
        }

    /** The colour the screen is until then. */
    var color: Color = Color(0f, 0f, 0f)

    /** How the fade goes (see [Curve]): slow, fast, slow by default. */
    var curve: Curve = Curve.EASE_IN_OUT

    /** How many frames are drawn (dark) before it fades in: the first ones are the slow ones. */
    var warmFrames = 3
        set(value) {
            require(value in 0..120) { "reveal: warmFrames is 0 to 120, not $value" }
            field = value
        }

    /** The pointer hidden while the screen is dark (it shows again when the mouse moves). */
    var hideCursor = true

    internal val holds = ArrayList<Hold>()
    internal val warmWork = ArrayList<Pair<String, (done: () -> Unit) -> Unit>>()

    // The running start, once it runs (holds asked for then go to it at once).
    @Volatile
    internal var curtain: RevealCurtain? = null

    /** Something the reveal waits for until it is closed (see [hold]). */
    class Hold internal constructor(val name: String, private val released: (Hold) -> Unit) : AutoCloseable {
        @Volatile
        var closed = false
            private set

        override fun close() {
            if (closed) return
            closed = true
            released(this)
        }

        override fun toString() = name
    }

    /**
     * The screen stays dark until the result is closed (or [maxWait] passed): for work of
     * yours that must be done before the desktop shows (a wallpaper loaded, a bar drawn once).
     * Callable from any thread.
     */
    fun hold(name: String): AutoCloseable {
        // (Under the lock: the start, taking the holds over on its thread, misses none.)
        synchronized(holds) {
            curtain?.let { return it.hold(name) }
            return Hold(name) { }.also { holds += it }
        }
    }

    /**
     * [work] runs when the compositor starts (on its thread); the screen stays dark until it
     * calls `done` (from any thread) — or [maxWait] passed.
     */
    @JvmSynthetic
    fun whenWarm(name: String, work: (done: () -> Unit) -> Unit) {
        warmWork += name to work
    }

    /** [whenWarm] for Java: [work] gets a Runnable to run when it is done. */
    fun whenWarm(name: String, work: java.util.function.Consumer<Runnable>) = whenWarm(name) { done -> work.accept(Runnable { done() }) }

    // Java's chained setters.
    fun enabled(on: Boolean): Reveal = apply { enabled = on }
    fun fadeMillis(millis: Long): Reveal = apply { fadeMillis = millis }
    fun maxWaitMillis(millis: Long): Reveal = apply { maxWaitMillis = millis }
    fun color(color: Color): Reveal = apply { this.color = color }

    // Its settings and work into [target] (a copy of a configuration).
    internal fun copyInto(target: Reveal) {
        target.enabled = enabled
        target.fadeMillis = fadeMillis
        target.maxWaitMillis = maxWaitMillis
        target.color = color
        target.curve = curve
        target.warmFrames = warmFrames
        target.hideCursor = hideCursor
        synchronized(target.holds) {
            target.holds.clear()
            synchronized(holds) { target.holds += holds }
        }
        target.warmWork.clear()
        target.warmWork += warmWork
    }
}

/**
 * The dark screen of [Reveal] over everything (but notices and the cursor) on every monitor,
 * from the very first frame until it is warm, then fading out. [revealing] runs when the fade
 * begins, [revealed] once it is over (on the compositor's thread). Off ([on] false), it only
 * notes the first frame and shows the desktop at once.
 */
internal class RevealCurtain(
    private val settings: Reveal,
    private val on: Boolean,
    private val desktop: dev.caulis.core.Desktop,
    private val later: (millis: Long, action: () -> Unit) -> AutoCloseable,
    private val post: (() -> Unit) -> Unit,
    private val revealing: () -> Unit,
    private val revealed: () -> Unit,
    /** Puts the cursor back as the configuration has it (after hiding it while dark). */
    private val restoreCursor: () -> Unit,
) : Drawing, AutoCloseable {

    private val log = Logger("reveal")
    private val begun = System.nanoTime()
    private val pending = LinkedHashSet<Reveal.Hold>()
    private var frames = 0
    private var framesWhenFree = -1
    private var firstFrameNanos = 0L
    private var fadeStart = 0L
    private var done = false
    private var started = false
    private var maxWaitTimer: AutoCloseable? = null
    private var hidCursor = false

    // Drawn from the start (before the loop runs too: the first picture on screen is dark).
    private var drawing: AutoCloseable? = desktop.addDrawing(DrawingLevel.ON_TOP, this)

    /** True once the desktop is shown (the fade began). */
    val shown: Boolean get() = fadeStart != 0L || done

    /** When the first frame was drawn (System.nanoTime), or 0. */
    val firstFrameAt: Long get() = firstFrameNanos

    /** Starts waiting until it is warm (or shows the desktop at once when off). */
    fun begin() {
        if (started) return
        started = true
        synchronized(settings.holds) {
            for (hold in settings.holds) if (!hold.closed) pending += hold
            settings.holds.clear()
            settings.curtain = this
        }
        if (!on) {
            // Off: the work asked for still runs (nothing waits for it), and the desktop is
            // shown at once (the drawing only notes the first frame).
            for ((name, work) in settings.warmWork) {
                try {
                    work {}
                } catch (e: Exception) {
                    log.warn("Your work before the reveal ($name) failed: $e")
                }
            }
            post {
                done = true
                safely("revealing", revealing)
                safely("revealed", revealed)
            }
            return
        }
        if (settings.hideCursor) {
            hidCursor = true
            desktop.hideCursorAfterMillis = 1
        }
        for ((name, work) in settings.warmWork) {
            val hold = hold(name)
            try {
                work { hold.close() }
            } catch (e: Exception) {
                log.warn("Your work before the reveal ($name) failed: $e")
                hold.close()
            }
        }
        log.info { "Dark until warm (${settings.warmFrames} frames${if (pending.isNotEmpty()) ", ${pending.joinToString()}" else ""}; at most ${settings.maxWaitMillis} ms)" }
        maxWaitTimer = later(settings.maxWaitMillis) {
            if (shown) return@later
            log.info { "Shown after the longest wait (${settings.maxWaitMillis} ms)${if (pending.isNotEmpty()) "; still waiting for ${pending.joinToString()}" else ""}" }
            startFade()
        }
        desktop.requestRedraw()
    }

    /** Something to wait for (see [Reveal.hold]); from any thread. */
    fun hold(name: String): AutoCloseable {
        lateinit var hold: Reveal.Hold
        hold = Reveal.Hold(name) { post { released(hold) } }
        if (shown) {
            log.trace { "$name: the desktop is shown already" }
            return hold
        }
        post { if (!hold.closed && !shown) pending += hold }
        return hold
    }

    private fun released(hold: Reveal.Hold) {
        if (!pending.remove(hold)) return
        log.info { "${hold.name} ready after ${(System.nanoTime() - begun) / 1_000_000} ms" }
        desktop.requestRedraw()
    }

    override fun draw(monitor: Monitor, canvas: OverlayCanvas) {
        val now = System.nanoTime()
        if (firstFrameNanos == 0L) {
            firstFrameNanos = now
            val jvm = runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean().uptime }.getOrNull()
            log.info { "First frame ${(now - begun) / 1_000_000} ms after the compositor began${jvm?.let { ", $it ms after Java started" } ?: ""}" }
            if (!on) post { removeDrawing() }
        }
        if (!on || done) return
        // Frames counted once each (on the first monitor), until it fades.
        if (started && fadeStart == 0L && monitor === desktop.monitors.firstOrNull()) countFrame()
        val alpha = alphaAt(now)
        if (alpha <= 0f) return
        val c = settings.color
        canvas.fill(monitor.area, c.red, c.green, c.blue, alpha)
    }

    private fun countFrame() {
        frames++
        if (pending.isNotEmpty()) framesWhenFree = -1 // (a hold asked for meanwhile)
        else if (framesWhenFree < 0) framesWhenFree = frames
        if (framesWhenFree >= 0 && frames - framesWhenFree >= settings.warmFrames) post { startFade() }
    }

    // How dark it is now: wholly until the fade, then less and less along the curve.
    private fun alphaAt(now: Long): Float {
        if (fadeStart == 0L) return 1f
        val fade = settings.fadeMillis * 1_000_000
        if (fade <= 0) return 0f
        val t = ((now - fadeStart).toDouble() / fade).coerceIn(0.0, 1.0)
        if (t >= 1.0) post { finish() }
        val curved = try {
            settings.curve.at(t)
        } catch (e: Exception) {
            t // (a curve of your own that failed: straight)
        }
        return (1.0 - curved).coerceIn(0.0, 1.0).toFloat()
    }

    private fun startFade() {
        if (shown) return
        fadeStart = System.nanoTime()
        maxWaitTimer?.close()
        if (hidCursor) {
            hidCursor = false
            safely("the cursor", restoreCursor)
        }
        log.info { "Warm after ${(fadeStart - begun) / 1_000_000} ms ($frames frames): fading in over ${settings.fadeMillis} ms" }
        desktop.requestRedraw()
        safely("revealing", revealing)
        if (settings.fadeMillis <= 0) finish()
    }

    private fun finish() {
        if (done) return
        if (fadeStart == 0L) return startFade()
        done = true
        removeDrawing()
        settings.curtain = null
        log.info { "Shown ${(System.nanoTime() - begun) / 1_000_000} ms after the compositor began" }
        safely("revealed", revealed)
    }

    private fun removeDrawing() {
        drawing?.close()
        drawing = null
    }

    // Frames keep coming while dark (counting them) and while fading.
    override fun animating(now: Long): Boolean = on && started && !done

    override fun close() {
        maxWaitTimer?.close()
        removeDrawing()
        if (hidCursor) safely("the cursor", restoreCursor)
        hidCursor = false
        done = true
        settings.curtain = null
    }

    private fun safely(what: String, action: () -> Unit) = try {
        action()
    } catch (e: Exception) {
        log.warn("The reveal ($what) failed: $e")
    }
}
