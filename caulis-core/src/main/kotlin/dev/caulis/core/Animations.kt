package dev.caulis.core

import dev.caulis.geometry.Rect
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How an animation moves over its time: [at] gets how much of the time has passed (0 to 1)
 * and gives how far along it is (0 to 1; a little past 1 for a lively overshoot).
 *
 * ```
 * Curve.EASE_OUT                        // fast at first, gently slowing down (the default)
 * Curve.bezier(0.05, 0.9, 0.1, 1.05)    // as Hyprland's (and CSS) cubic-bezier
 * Curve.spring(stiffness = 300.0, damping = 18.0) // a spring: it takes as long as it needs
 * Curve { t -> t * t }                  // your own
 * ```
 */
fun interface Curve {
    fun at(t: Double): Double

    /** How long an animation along it takes when the animation does not say (a spring's own time), or null. */
    val naturalMillis: Long? get() = null

    companion object {
        /**
         * A cubic Bézier curve from (0, 0) to (1, 1) with the control points ([x1], [y1]) and
         * ([x2], [y2]) — the same numbers as Hyprland's `bezier` and CSS's `cubic-bezier`. A
         * [y1] or [y2] past 0..1 overshoots.
         */
        @JvmStatic
        fun bezier(x1: Double, y1: Double, x2: Double, y2: Double): Curve {
            require(x1 in 0.0..1.0 && x2 in 0.0..1.0) { "x1 and x2 are 0 to 1 (time cannot go back), not $x1 and $x2" }
            require(y1.isFinite() && y2.isFinite()) { "y1 and y2 must be numbers, not $y1 and $y2" }
            fun along(a: Double, b: Double, s: Double) = 3 * a * s * (1 - s) * (1 - s) + 3 * b * s * s * (1 - s) + s * s * s
            return Curve { t ->
                if (t <= 0.0) return@Curve 0.0
                if (t >= 1.0) return@Curve 1.0
                // The point of the curve at time t: x(s) = t, by halving (x grows with s).
                var low = 0.0
                var high = 1.0
                var s = t
                for (i in 0 until 40) {
                    val x = along(x1, x2, s)
                    if (Math.abs(x - t) < 1e-7) break
                    if (x < t) low = s else high = s
                    s = (low + high) / 2
                }
                along(y1, y2, s)
            }
        }

        /**
         * A spring pulling to the end from rest: [stiffness] how strongly, [damping] how much
         * it is held back (less: it bounces past the end), [mass] how heavy. An animation along
         * it lasts until the spring is still ([naturalMillis]), unless it says otherwise.
         */
        @JvmStatic
        @JvmOverloads
        fun spring(stiffness: Double = 200.0, damping: Double = 20.0, mass: Double = 1.0): Curve {
            require(stiffness > 0 && damping > 0 && mass > 0) { "a spring's stiffness, damping and mass are more than 0" }
            // Moved step by step (half a millisecond) until it rests; at most 5 seconds.
            val step = 0.0005
            val samples = ArrayList<Double>()
            var x = 0.0
            var v = 0.0
            var still = 0
            while (samples.size < 10_000) {
                samples += x
                val a = (-stiffness * (x - 1) - damping * v) / mass
                v += a * step
                x += v * step
                still = if (Math.abs(x - 1) < 0.001 && Math.abs(v) < 0.01) still + 1 else 0
                if (still > 20) break
            }
            samples += 1.0
            val millis = Math.round(samples.size * step * 1000).coerceIn(1, 5000)
            return object : Curve {
                override val naturalMillis: Long = millis
                override fun at(t: Double): Double {
                    if (t >= 1.0) return 1.0
                    val at = t.coerceAtLeast(0.0) * (samples.size - 1)
                    val i = at.toInt()
                    return samples[i] + (samples[i + 1] - samples[i]) * (at - i)
                }
            }
        }

        /** The same speed all the way. */
        @JvmField val LINEAR = Curve { it }

        /** Fast at first, gently slowing down: things arrive softly (the default). */
        @JvmField val EASE_OUT = Curve { t -> 1 - (1 - t).pow(3) }

        /** Slow, fast, slow. */
        @JvmField val EASE_IN_OUT = Curve { t -> if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2 }

        /** Slow at both ends (smoothstep): how pictures melt into each other. */
        @JvmField val SMOOTH = Curve { t -> t * t * (3 - 2 * t) }

        /**
         * A ready curve by its name: "linear", "ease-out" (also "default"), "ease-in-out",
         * "smooth", "overshoot"; null for another name.
         */
        @JvmStatic
        fun named(name: String): Curve? = when (name.trim().lowercase()) {
            "linear" -> LINEAR
            "ease-out", "easeout", "default" -> EASE_OUT
            "ease-in-out", "easeinout" -> EASE_IN_OUT
            "smooth" -> SMOOTH
            "overshoot" -> OVERSHOOT
            else -> null
        }

        /** Goes a little past the end and comes back: lively. */
        @JvmField val OVERSHOOT = Curve { t ->
            val c = 1.70158
            1 + (c + 1) * (t - 1).pow(3) + c * (t - 1).pow(2)
        }
    }
}

/**
 * One moment of a window's animation, what a [WindowAnimation] is given. Rectangles are in
 * desktop units.
 */
class Motion(
    /** The window (null for a window that is gone: only its last look is left). */
    val window: Window?,
    /** Where it starts and where it ends; for appearing and going away both are its place. */
    val from: Rect,
    val to: Rect,
    /** How far along it is (0 to 1, through the animation's [Curve]); for going away, 1 → 0. */
    val progress: Double,
    /** How much of its time has passed (0 to 1, without the curve). */
    val time: Double,
    /** The free area of its monitor (inside panels). */
    val area: Rect,
) {
    /** The rectangle [progress] of the way from [from] to [to]. */
    fun between(): Rect = mix(from, to, progress)

    /** A copy with another start or end. */
    fun with(from: Rect = this.from, to: Rect = this.to) = Motion(window, from, to, progress, time, area)

    companion object {
        /** The rectangle [t] of the way from [a] to [b]. */
        @JvmStatic
        fun mix(a: Rect, b: Rect, t: Double): Rect {
            fun m(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
            return Rect(m(a.x, b.x), m(a.y, b.y), m(a.width, b.width).coerceAtLeast(1), m(a.height, b.height).coerceAtLeast(1))
        }
    }
}

/**
 * How a window is drawn at one moment: its picture stretched into [rect], at [alpha], and
 * through [shader] if it has one (a [WindowShader] gets [shaderFrom], [shaderTo] and the
 * progress to bend the picture, e.g. the genie).
 */
class WindowFrame @JvmOverloads constructor(
    val rect: Rect,
    val alpha: Float = 1f,
    val shader: WindowShader? = null,
    /** For the shader: where the picture starts and ends (default: [rect]). */
    val shaderFrom: Rect = rect,
    val shaderTo: Rect = rect,
    val progress: Double = 1.0,
)

/**
 * A window's animation: given a [Motion] (where it goes, how far along), it says how the
 * window is drawn ([WindowFrame]). Use the ready ones, put them together with `+`, give
 * them their own time and curve — or write your own in one line:
 *
 * ```
 * WindowAnimation.pop()                                   // fades in growing from 90%
 * WindowAnimation.fade() + WindowAnimation.slide(dy = 60) // fades in rising from below
 * WindowAnimation.fade() + WindowAnimation.scale(0.5)    // fades in growing from half its size
 * WindowAnimation.pop(0.6).lasting(400).curved(Curve.OVERSHOOT)
 * WindowAnimation.genie()                                 // minimizing: flows into the dock icon
 * WindowAnimation { m -> WindowFrame(m.between(), alpha = m.progress.toFloat()) } // your own
 * ```
 */
fun interface WindowAnimation {
    fun frame(motion: Motion): WindowFrame

    /** How long it takes in milliseconds (null: [Desktop.animationMillis]). */
    val millis: Long? get() = null

    /** Its curve (null: the one of [Animations]). */
    val curve: Curve? get() = null

    /** The same, taking [millis] milliseconds. */
    fun lasting(millis: Long): WindowAnimation {
        require(millis in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $millis" }
        return Timed(this, millis, curve)
    }

    /** The same, along [curve]. */
    fun curved(curve: Curve): WindowAnimation = Timed(this, millis, curve)

    /** This, then [other] on top: rectangles changed by both, opacities multiplied, [other]'s shader if it has one. */
    operator fun plus(other: WindowAnimation): WindowAnimation {
        val first = this
        return Timed(WindowAnimation { motion ->
            val a = first.frame(motion)
            val b = other.frame(motion.with(from = motion.from, to = a.rect))
            if (b.shader != null) b else WindowFrame(b.rect, a.alpha * b.alpha, a.shader, a.shaderFrom, a.shaderTo, a.progress)
        }, other.millis ?: millis, other.curve ?: curve)
    }

    companion object {
        /** Nothing moves: it is there (or gone) at once. */
        @JvmField val NONE: WindowAnimation = Timed(WindowAnimation { WindowFrame(it.to) }, 0, null)

        /** It moves and resizes from where it was to where it goes (how windows glide by default). */
        @JvmStatic
        fun move(): WindowAnimation = WindowAnimation { WindowFrame(it.between()) }

        /** It fades in (or out). */
        @JvmStatic
        fun fade(): WindowAnimation = WindowAnimation { WindowFrame(it.to, alpha = it.progress.toFloat().coerceIn(0f, 1f)) }

        /** It grows from [from] of its size (or shrinks to it), around its middle. */
        @JvmStatic
        @JvmOverloads
        fun scale(from: Double = 0.9): WindowAnimation = WindowAnimation { m ->
            WindowFrame(scaled(m.to, from + (1 - from) * m.progress))
        }

        /** It comes in from [dx], [dy] units away (or goes there). */
        @JvmStatic
        @JvmOverloads
        fun slide(dx: Int = 0, dy: Int = 40): WindowAnimation = WindowAnimation { m ->
            val away = 1 - m.progress
            WindowFrame(m.to.translate((dx * away).roundToInt(), (dy * away).roundToInt()))
        }

        /** It fades in growing from [from] of its size (or shrinks away): `fade() + scale(from)`, as on macOS and GNOME. */
        @JvmStatic
        @JvmOverloads
        fun pop(from: Double = 0.9): WindowAnimation = fade() + scale(from)

        /** It flies from where it is into [Motion.to] (minimizing: its dock icon), shrinking. */
        @JvmStatic
        fun flyTo(): WindowAnimation = move()

        /**
         * The genie: the window flows into its dock icon, its bottom first, narrowing like
         * smoke into a lamp (and out again when restored). Bends the picture with a shader.
         */
        @JvmStatic
        fun genie(): WindowAnimation = WindowAnimation { m ->
            val box = m.from.union(m.to)
            WindowFrame(box, shader = WindowShader.GENIE, shaderFrom = m.from, shaderTo = m.to, progress = m.progress)
        }

        /**
         * A look by name, with what it may take after it (as Hyprland's styles): "popin 80%"
         * (fades in growing from 80%), "pop", "fade", "scale 50%", "slide" (from below),
         * "slide left 120" (from 120 units to the left; also right, up, down, top, bottom),
         * "slide-up", "genie", "fly", "move", "none". Null for something else.
         */
        @JvmStatic
        fun named(style: String): WindowAnimation? {
            val words = style.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            fun percent(at: Int, usual: Double): Double? {
                val word = words.getOrNull(at) ?: return usual
                val n = word.removeSuffix("%").toDoubleOrNull() ?: return null
                return (if (word.endsWith("%") || n > 1.0) n / 100 else n).takeIf { it in 0.0..1.0 }
            }
            return when (words[0]) {
                "popin", "pop" -> percent(1, if (words[0] == "popin") 0.8 else 0.9)?.let { pop(it) }
                "fade", "fadein" -> fade()
                "scale" -> percent(1, 0.9)?.let { fade() + scale(it) }
                "slide" -> {
                    var dx = 0
                    var dy = 40
                    var distance = 40
                    var side = "down"
                    for (word in words.drop(1)) {
                        val n = word.toIntOrNull()
                        if (n != null && n in 0..5000) distance = n else if (word in listOf("left", "right", "up", "down", "top", "bottom")) side = word else return null
                    }
                    when (side) {
                        "left" -> { dx = -distance; dy = 0 }
                        "right" -> { dx = distance; dy = 0 }
                        "up", "top" -> dy = -distance
                        else -> dy = distance
                    }
                    fade() + slide(dx, dy)
                }
                "slide-up" -> fade() + slide()
                "genie" -> genie()
                "fly" -> flyTo()
                "move" -> move()
                "none" -> NONE
                else -> null
            }
        }

        /** [rect] grown or shrunk by [scale] around its middle. */
        @JvmStatic
        fun scaled(rect: Rect, scale: Double): Rect {
            val width = (rect.width * scale).roundToInt().coerceAtLeast(1)
            val height = (rect.height * scale).roundToInt().coerceAtLeast(1)
            return Rect(rect.x + (rect.width - width) / 2, rect.y + (rect.height - height) / 2, width, height)
        }
    }

    private class Timed(val inner: WindowAnimation, override val millis: Long?, override val curve: Curve?) : WindowAnimation {
        override fun frame(motion: Motion) = inner.frame(motion)
    }
}

/**
 * A shader that draws a window's picture during an animation: GLSL code (OpenGL ES 3)
 * with one function, which gets the place in the drawn rectangle and returns the colour:
 *
 * ```
 * vec4 effect(vec2 at) {
 *     // at: 0..1 in the drawn rectangle; window(uv) reads the window's picture (uv 0..1,
 *     // see-through outside); progress: 0..1; from, to: the picture's start and end as
 *     // (x, y, width, height) in the drawn rectangle's 0..1; size: its size in pixels.
 *     return window(at) * progress;
 * }
 * ```
 *
 * A shader that does not compile is reported in the log; the window is then drawn without.
 */
class WindowShader private constructor(private val inline: String?, private val file: WatchedText?) {

    /** A shader written in your code. */
    constructor(code: String) : this(code, null)

    /** The GLSL code with the `effect` function (and any helpers it needs); a file's as it is now. */
    val code: String get() = inline ?: file!!.text

    /** Why its file cannot be read now (null: it can, or it is written in code). */
    val fileProblem: String? get() = file?.problem

    /** The file it comes from (null: written in code). */
    val path: java.nio.file.Path? get() = file?.path

    /** What to call it in messages: its file's name, or "a window shader". */
    val name: String get() = file?.path?.fileName?.toString() ?: "a window shader"

    // The same code is the same shader (compiled once, a mistake in it said once).
    override fun equals(other: Any?) = other is WindowShader && other.code == code
    override fun hashCode() = code.hashCode()
    override fun toString() = "WindowShader(${file?.path ?: "${code.length} characters"})"

    companion object {
        /**
         * A shader from a file (e.g. next to your configuration): read again whenever you
         * save it, and used at once — no restart, no reload of anything else.
         */
        @JvmStatic
        fun file(path: java.nio.file.Path): WindowShader = WindowShader(null, WatchedText.of(path))

        /** The genie: rows of the picture move towards [to] one after another, the far edge last. */
        @JvmField val GENIE = WindowShader(
            """
            vec4 effect(vec2 at) {
                // Turned so that it always flows down (into a dock below, above, left or right).
                vec2 dir = (to.xy + to.zw * 0.5) - (from.xy + from.zw * 0.5);
                bool vertical = abs(dir.y) >= abs(dir.x);
                vec2 p = vertical ? at : at.yx;
                vec4 f = vertical ? from : from.yxwz;
                vec4 t = vertical ? to : to.yxwz;
                bool down = (vertical ? dir.y : dir.x) >= 0.0;
                if (!down) { p.y = 1.0 - p.y; f.y = 1.0 - f.y - f.w; t.y = 1.0 - t.y - t.w; }
                // First half: its far edge stretches to the icon, narrowing; second half: it slides in.
                float squeeze = clamp(progress * 2.0, 0.0, 1.0);
                float slide = clamp(progress * 2.0 - 1.0, 0.0, 1.0);
                float top = mix(f.y, t.y, slide);
                float bottom = mix(f.y + f.w, t.y + t.w, squeeze);
                if (p.y < top || p.y > bottom || bottom - top < 0.0001) return vec4(0.0);
                float v = (p.y - top) / (bottom - top);
                // Rows nearer the icon are narrower (a soft S from the window's top to the icon).
                float near = smoothstep(0.0, 1.0, (p.y - f.y) / max(t.y + t.w - f.y, 0.0001));
                float k = mix(squeeze * near, 1.0, slide);
                float left = mix(f.x, t.x, k);
                float right = mix(f.x + f.z, t.x + t.z, k);
                if (p.x < left || p.x > right || right - left < 0.0001) return vec4(0.0);
                vec2 uv = vec2((p.x - left) / (right - left), down ? v : 1.0 - v);
                return window(vertical ? uv : uv.yx);
            }
            """.trimIndent(),
        )
    }
}

/**
 * How a workspace change looks on one monitor: given how far along it is (0 to 1, through
 * the curve), the direction (1: to a higher number, -1: back) and the monitor, where the
 * new and the old workspace are drawn (moved by dx, dy units) and how strongly.
 */
fun interface WorkspaceAnimation {
    fun frame(progress: Double, direction: Int, area: Rect): WorkspaceFrame

    /** How long it takes in milliseconds (null: [Desktop.animationMillis]). */
    val millis: Long? get() = null

    /** Its curve (null: the one of [Animations]). */
    val curve: Curve? get() = null

    fun lasting(millis: Long): WorkspaceAnimation {
        require(millis in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $millis" }
        return Timed(this, millis, curve)
    }

    fun curved(curve: Curve): WorkspaceAnimation = Timed(this, millis, curve)

    companion object {
        /** At once. */
        @JvmField val NONE: WorkspaceAnimation = Timed(WorkspaceAnimation { _, _, _ -> WorkspaceFrame() }, 0, null)

        /** Side by side: the next workspace comes in from the right, the one before from the left. */
        @JvmField val SLIDE = WorkspaceAnimation { e, direction, area ->
            WorkspaceFrame(incomingDx = direction * (1 - e) * area.width, outgoingDx = -direction * e * area.width)
        }

        /** One above the other: the next comes in from below. */
        @JvmField val SLIDE_VERTICAL = WorkspaceAnimation { e, direction, area ->
            WorkspaceFrame(incomingDy = direction * (1 - e) * area.height, outgoingDy = -direction * e * area.height)
        }

        /** The old workspace fades into the new one. */
        @JvmField val FADE = WorkspaceAnimation { e, _, _ -> WorkspaceFrame(incomingAlpha = e.toFloat().coerceIn(0f, 1f), outgoingAlpha = (1 - e).toFloat().coerceIn(0f, 1f)) }

        /** A look by name, as Hyprland's: "slide", "slidevert" (also "slide-vertical"), "fade", "none"; null for another. */
        @JvmStatic
        fun named(style: String): WorkspaceAnimation? = when (style.trim().lowercase()) {
            "slide" -> SLIDE
            "slidevert", "slide-vertical", "slidevertical" -> SLIDE_VERTICAL
            "fade" -> FADE
            "none" -> NONE
            else -> null
        }
    }

    private class Timed(val inner: WorkspaceAnimation, override val millis: Long?, override val curve: Curve?) : WorkspaceAnimation {
        override fun frame(progress: Double, direction: Int, area: Rect) = inner.frame(progress, direction, area)
    }
}

/** Where the new and the old workspace are drawn during a change (see [WorkspaceAnimation]). */
class WorkspaceFrame @JvmOverloads constructor(
    val incomingDx: Double = 0.0,
    val incomingDy: Double = 0.0,
    val incomingAlpha: Float = 1f,
    val outgoingDx: Double = 0.0,
    val outgoingDy: Double = 0.0,
    val outgoingAlpha: Float = 1f,
)

/**
 * All of the compositor's animations, Hyprland-like: a global switch ([enabled], off by
 * default: everything changes at once), named animations in a tree ([AnimationTree.CAULIS]:
 * windows → windowsIn, windowsOut, windowsMove, scratchpads, minimize, restore; workspaces; overview;
 * border; screenshot; fade → fadeScale, fadeBackground, fadeNotices, fadeOsd) with settings by name
 * ([entries]: on/off, time, curve, look), and the looks themselves as code ([WindowAnimation],
 * [WorkspaceAnimation]). Times are [Desktop.animationMillis] unless an animation has its own.
 *
 * ```
 * Animations(enabled = true)                                        // the usual, on
 * Animations(enabled = true, minimize = WindowAnimation.genie())    // macOS's genie
 * Animations(enabled = true, windowOpen = WindowAnimation.fade() + WindowAnimation.slide(dy = 80),
 *            workspaceSwitch = WorkspaceAnimation.SLIDE_VERTICAL.lasting(300))
 * Animations(enabled = true)
 *     .with("windows", millis = 300, curve = Curve.bezier(0.05, 0.9, 0.1, 1.05))
 *     .with("windowsIn", style = "popin 80%")
 *     .with("fade", enabled = false)
 * Animations().with("workspaces", enabled = true)                   // just this one
 * Animations.NONE                                                   // nothing moves
 * ```
 */
data class Animations @JvmOverloads constructor(
    /** A window appearing ([Motion.progress] 0 → 1). */
    val windowOpen: WindowAnimation = WindowAnimation.pop(),
    /** A window going away (its last look; [Motion.progress] 1 → 0). */
    val windowClose: WindowAnimation = WindowAnimation.pop(),
    /** A window gliding to a new place or size (a layout change, maximize, a neighbour closing). */
    val windowMove: WindowAnimation = WindowAnimation.move(),
    /** A window minimized: [Motion.to] is its dock icon (or a small place at the bottom). */
    val minimize: WindowAnimation = WindowAnimation.genie(),
    /** A minimized window coming back ([Motion.from] is its dock icon). */
    val restore: WindowAnimation = WindowAnimation.genie(),
    /** Another workspace shown on a monitor. */
    val workspaceSwitch: WorkspaceAnimation = WorkspaceAnimation.SLIDE,
    /** The overview zooming out and in (its time is 1.5 × [Desktop.animationMillis] unless given). */
    val overviewMillis: Long? = null,
    /** The curve of every animation that has none of its own. */
    val curve: Curve = Curve.EASE_OUT,
    /** How long the picture before a scale change melts into the new one (milliseconds; 0: at once). */
    val scaleChangeMillis: Long = 250,
    /**
     * Whether animations run unless [entries] say otherwise. False (the default): nothing
     * moves, everything is where it goes at once (following fingers is not an animation: a
     * swipe or pinch still follows them, only what comes after they lift is at once).
     */
    val enabled: Boolean = false,
    /** Settings by animation name ([AnimationTree.CAULIS]), as Hyprland's `animation = …` lines. */
    val entries: Map<String, AnimationEntry> = emptyMap(),
) {
    init {
        require(scaleChangeMillis in 0..10_000) { "scaleChangeMillis is 0 to 10000" }
        for ((name, entry) in entries) {
            require(name in AnimationTree.CAULIS) { AnimationTree.CAULIS.unknown(name) }
            entry.style?.let { style -> styleProblem(name, style)?.let { throw IllegalArgumentException(it) } }
        }
    }

    /**
     * The same with settings for the animation [name] (of [AnimationTree.CAULIS]) — for it
     * and all under it that do not say otherwise; nulls are left as they were.
     */
    @JvmOverloads
    fun with(name: String, enabled: Boolean? = null, millis: Long? = null, curve: Curve? = null, style: String? = null): Animations {
        require(name in AnimationTree.CAULIS) { AnimationTree.CAULIS.unknown(name) }
        val entry = AnimationEntry(enabled, millis, curve, style)
        return copy(entries = entries + (name to (entries[name]?.and(entry) ?: entry)))
    }

    // Caulis 0.1.1's copy (code built against it calls it).
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    fun copy(
        windowOpen: WindowAnimation = this.windowOpen, windowClose: WindowAnimation = this.windowClose,
        windowMove: WindowAnimation = this.windowMove, minimize: WindowAnimation = this.minimize,
        restore: WindowAnimation = this.restore, workspaceSwitch: WorkspaceAnimation = this.workspaceSwitch,
        overviewMillis: Long? = this.overviewMillis, curve: Curve = this.curve, scaleChangeMillis: Long = this.scaleChangeMillis,
    ): Animations = Animations(windowOpen, windowClose, windowMove, minimize, restore, workspaceSwitch, overviewMillis, curve, scaleChangeMillis, enabled, entries)

    /** These as [AnimationSettings], with [millis] as the global time ([Desktop.animationMillis]). */
    fun settings(millis: Long): AnimationSettings = AnimationSettings(AnimationTree.CAULIS, enabled, millis, curve, entries)

    companion object {
        /** The usual animations, turned on. */
        @JvmField val ON = Animations(enabled = true)

        // The animations whose look is a window's, and those that only move windows.
        private val WINDOW_LOOKS = setOf("windows", "windowsIn", "windowsOut", "minimize", "restore")
        private val MOVES = setOf("windowsMove", "scratchpads")

        /**
         * Why [style] is not a look the animation [name] can take, or null when it is (a
         * window's look such as "popin 80%" for windows, "slide"/"slidevert"/"fade" for
         * workspaces; "move" or "none" for windows moving; animations with one look take none).
         */
        @JvmStatic
        fun styleProblem(name: String, style: String): String? = when (name) {
            in WINDOW_LOOKS -> if (WindowAnimation.named(style) == null) "$name: there is no window look \"$style\" (there are: ${AnimationTree.WINDOW_STYLES.joinToString()})" else null
            in MOVES -> if (style.trim().lowercase() !in setOf("move", "none")) "$name: its look is \"move\" or \"none\", not \"$style\"" else null
            "workspaces" -> if (WorkspaceAnimation.named(style) == null) "$name: there is no workspace look \"$style\" (there are: ${AnimationTree.WORKSPACE_STYLES.joinToString()})" else null
            else -> "$name has one look only (no style), not \"$style\""
        }

        /** Nothing moves: windows and workspaces change at once. */
        @JvmField val NONE = Animations(
            WindowAnimation.NONE, WindowAnimation.NONE, WindowAnimation.NONE, WindowAnimation.NONE, WindowAnimation.NONE,
            WorkspaceAnimation.NONE, overviewMillis = 0, scaleChangeMillis = 0,
        )
    }
}

/**
 * [Animations] put together line by line — what `caulis.animations { … }` gives, as
 * Hyprland's `animations { … }` section:
 *
 * ```
 * animations {
 *     enabled = true                                   // all of them (off by default)
 *     millis = 250                                     // the global time
 *     curve = Curve.bezier(0.05, 0.9, 0.1, 1.05)       // the global curve
 *     animation("windowsIn", style = "popin 80%")
 *     animation("workspaces", millis = 300, style = "slidevert")
 *     off("fade")                                      // and everything under it
 * }
 * ```
 */
class AnimationsBuilder(start: Animations) {
    /** What it has become so far. */
    var animations: Animations = start
        private set

    /** All animations on (unless one says otherwise) or off (the default). */
    var enabled: Boolean
        get() = animations.enabled
        set(value) {
            animations = animations.copy(enabled = value)
        }

    /** The global time in milliseconds (null: as it was; Desktop.animationMillis). */
    var millis: Long? = null
        set(value) {
            require(value == null || value in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $value" }
            field = value
        }

    /** The curve of every animation that has none of its own. */
    var curve: Curve
        get() = animations.curve
        set(value) {
            animations = animations.copy(curve = value)
        }

    /**
     * Settings for the animation [name] (see [AnimationTree.CAULIS]) and those under it: on
     * or off, its time ([millis]; or [speed] in Hyprland's tenths of a second), curve and look.
     */
    @JvmOverloads
    fun animation(name: String, enabled: Boolean? = null, millis: Long? = null, curve: Curve? = null, style: String? = null, speed: Double? = null) {
        require(speed == null || speed in 0.0..100.0) { "speed is 0 to 100 (tenths of a second), not $speed" }
        animations = animations.with(name, enabled, millis ?: speed?.let { Math.round(it * 100) }, curve, style)
    }

    /** Just [name] (and those under it) on, even while the rest is off. */
    fun on(name: String) = animation(name, enabled = true)

    /** [name] (and those under it) off. */
    fun off(name: String) = animation(name, enabled = false)

    /** Everything as code: the looks of windows and workspaces (see [Animations]). */
    fun looks(change: (Animations) -> Animations) {
        animations = change(animations)
    }
}
