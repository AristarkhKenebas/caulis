package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import kotlin.math.roundToInt

/**
 * Effects around windows: corners, borders, shadows and paints that move; and telling you when a
 * shader of yours does not work.
 */
internal class DesktopLooks(private val desktop: Desktop) {

    // How one window or panel is drawn: its picture clipped and faded, effects below it.
    // [dim]: how much darker it is drawn (a window without the focus, see Effects.dimInactive).
    internal class Look(val clip: Rect?, val radius: Float, val alpha: Float, val dim: Float = 0f) {
        companion object {
            val PLAIN = Look(null, 0f, 1f)
        }
    }

    // Whether each window (or panel) had the focus, and since when (for the change of its look).
    private val focusLooks = java.util.WeakHashMap<Any, Pair<Boolean, Long>>()

    // Set while building the scene: a border or shadow changes by itself (a turning gradient,
    // a shader using time) or after a focus change — frames go on.
    internal var paintsMoving = false

    internal var focusChanging = false

    internal val paintStart = System.nanoTime()

    // When moving paints last moved on, and the frame asked for when they are due next.
    private var paintsMovedAt = 0L

    internal var paintsTimer: dev.caulis.event.EventLoop.Timer? = null

    // True if moving paints are due to move on now ([movingPaintsFps]); if not, a frame is asked for when they are.
    internal fun paintsDue(now: Long): Boolean {
        val fps = desktop.movingPaintsFps
        if (fps <= 0) return true
        val interval = 1_000_000_000L / fps
        if (now - paintsMovedAt >= interval) {
            paintsMovedAt = now
            return true
        }
        if (paintsTimer == null) {
            paintsTimer = desktop.compositor.display.loop.after(((interval - (now - paintsMovedAt)) / 1_000_000).coerceAtLeast(1)) {
                paintsTimer = null
                desktop.changed()
            }
        }
        return false
    }

    // The time a moving paint is drawn at (0 for one that does not move, or must not: [animate] false).
    private fun paintTime(paint: dev.caulis.render.Paint, animate: Boolean = true): Float {
        if (!animate || !paint.moving) return 0f
        paintsMoving = true
        return ((System.nanoTime() - paintStart) / 1e9).toFloat()
    }

    // Adds the effects that go below [owner]'s picture at [rect] (on screen, zoomed by [scale]),
    // and returns how the picture itself is drawn.
    internal fun addEffects(all: MutableList<DrawItem>, owner: Any, surface: Surface, local: Rect, rect: Rect, scale: Double, fx: Effects, focused: Boolean): Look {
        if (fx == Effects.NONE || rect.isEmpty) return Look.PLAIN
        val radius = (fx.cornerRadius * scale).toFloat()
        val alpha = if (focused) fx.opacity else fx.inactiveOpacity
        // When the focus moves, the look before fades into the new one.
        val now = System.nanoTime()
        val change = focusLooks[owner]
        if (change == null || change.first != focused) focusLooks[owner] = focused to if (change == null) 0L else now
        val fading = desktop.animation("border", fx.focusChangeMillis)
        val e = desktop.animator.animationProgress(focusLooks.getValue(owner).second, fading.effectiveMillis, now)?.let { fading.curve.safeAt(it).toFloat().coerceIn(0f, 1f) }
        if (e != null) focusChanging = true
        fun shadowOf(focus: Boolean) = (if (focus) fx.shadow else fx.inactiveShadow ?: fx.shadow)?.takeIf { it != Shadow.NONE }
        fun addShadow(shadow: Shadow, alpha: Float, key: String) {
            val spread = (shadow.spread * scale).roundToInt()
            val box = Rect(rect.x - spread, rect.y - spread, rect.width + 2 * spread, rect.height + 2 * spread)
                .translate((shadow.offsetX * scale).roundToInt(), (shadow.offsetY * scale).roundToInt())
            all += DrawItem.Shadow(EffectKey(key, owner), box, radius + spread, (shadow.softness * scale).toFloat().coerceAtLeast(1f), shadow.color, rect, radius, alpha, paintTime(shadow.color))
        }
        if (e != null) shadowOf(!focused)?.let { addShadow(it, 1 - e, "shadow before") }
        shadowOf(focused)?.let { addShadow(it, e ?: 1f, "shadow") }
        fx.blur?.let { blur ->
            // Only worth it where one can see through the window.
            val seeThrough = alpha < 1f || surface.opaqueRects().none { it.contains(local) }
            if (seeThrough) all += DrawItem.Blur(EffectKey("blur", owner), rect, radius, (blur.size * scale).roundToInt().coerceAtLeast(1), blur.passes, blur.tone)
        }
        fx.border?.let { border ->
            val width = (border.width * scale).roundToInt().coerceAtLeast(1)
            val outer = Rect(rect.x - width, rect.y - width, rect.width + 2 * width, rect.height + 2 * width)
            // Urgent, in a group, focused: the paint for each state (and the shader learns it).
            val window = owner as? Window
            val urgent = window?.urgent == true
            val grouped = window != null && userCode("Your windowGrouped", false) { desktop.windowGrouped(window) }
            fun stateOf(focus: Boolean) = dev.caulis.render.PaintState(focus, urgent, grouped)
            fun paintOf(focus: Boolean) = border.paintFor(stateOf(focus))
            // One pixel more, under the window's edge: its soft edge then blends into the border, not the background.
            val round = if (radius > 0f) radius + width else 0f
            // The border before stays below until the new one is all there.
            if (e != null) all += DrawItem.Border(EffectKey("border before", owner), outer, round, width + 1f, paintOf(!focused), 1f, paintTime(paintOf(!focused), border.animate), stateOf(!focused))
            all += DrawItem.Border(EffectKey("border", owner), outer, round, width + 1f, paintOf(focused), e ?: 1f, paintTime(paintOf(focused), border.animate), stateOf(focused))
        }
        fun dimOf(focus: Boolean) = if (focus) 0f else fx.dimInactive
        val dim = if (e != null) dimOf(!focused) + (dimOf(focused) - dimOf(!focused)) * e else dimOf(focused)
        return Look(if (radius > 0f) rect else null, radius, alpha, dim)
    }

    // A paint was drawn (or its shader did not compile): said like other shaders.
    internal fun paintWorks(paint: dev.caulis.render.Paint, drawn: Boolean) {
        if (paint !is dev.caulis.render.Paint.Shader) return
        val code = paint.code
        shaderWorks(paint.name, code, if (drawn) null else desktop.renderer.effectProblem(code) ?: "it does not compile")
    }

    // Shaders that do not compile now, by name: their code then, and the notice that says so.
    private val shaderNotices = HashMap<String, Pair<String, Notice>>()

    // A shader was used: a notice says what is wrong with it (once per version of its code),
    // and goes once a new version works.
    internal fun shaderWorks(name: String, code: String, problem: String?) {
        val known = shaderNotices[name]
        if (problem == null) {
            if (known != null && known.first != code) {
                desktop.dismiss(known.second)
                shaderNotices -= name
                desktop.renderLog.info { "$name works now" }
            }
            return
        }
        if (known?.first == code) return
        known?.let { desktop.dismiss(it.second) }
        desktop.renderLog.warn("$name does not compile (drawn without it): $problem")
        shaderNotices[name] = code to desktop.notify(readableShaderProblem(problem), NoticeLevel.ERROR, "$name does not compile")
    }

    // "Shader failed to compile: 0:15(2): error: ..." → "line 15: ...".
    private fun readableShaderProblem(problem: String): String =
        problem.removePrefix("Shader failed to compile: ").lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            Regex("""^\d+:(\d+)\(\d+\): (?:error: )?(.*)$""").find(line)?.let { "line ${it.groupValues[1]}: ${it.groupValues[2]}" } ?: line
        }.take(4).joinToString("\n")

    // [fx] at [alpha] of its strength (a window fading in or out).
    internal fun faded(fx: Effects, alpha: Float): Effects {
        if (alpha >= 1f) return fx
        if (fx == Effects.NONE) return Effects(opacity = alpha, inactiveOpacity = alpha)
        return fx.copy(
            opacity = fx.opacity * alpha,
            inactiveOpacity = fx.inactiveOpacity * alpha,
            shadow = fx.shadow?.let { it.copy(color = it.color.faded(alpha)) },
            inactiveShadow = fx.inactiveShadow?.takeIf { it != Shadow.NONE }?.let { it.copy(color = it.color.faded(alpha)) } ?: fx.inactiveShadow,
            border = fx.border?.faded(alpha),
            blur = null,
        )
    }
}
