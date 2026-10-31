package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
import kotlin.math.roundToInt

/**
 * What moves by itself: windows opening, closing and minimizing, workspaces sliding, windows
 * gliding to their new places.
 */
internal class DesktopAnimations(private val desktop: Desktop) {

    // The animations' settings by name, made again when they change.
    internal var animationSettings: AnimationSettings? = null

    fun animation(name: String, usualMillis: Long? = null, usualCurve: Curve? = null): ResolvedAnimation {
        val settings = animationSettings ?: (
            if (desktop.animationMillis == 0L) AnimationSettings(AnimationTree.CAULIS, false, 0, desktop.animations.curve)
            else desktop.animations.settings(desktop.animationMillis)
        ).also { made ->
            animationSettings = made
            desktop.log.info { "Animations ${if (made.enabled) "on" else "off"}${if (made.entries.isEmpty()) "" else ", ${made.entries.size} set by name"}" }
            desktop.log.trace { "Animations by name:\n${made.describe()}" }
        }
        return settings.resolve(name, usualMillis, usualCurve)
    }

    // A window's animation by [name]: the code's look (or the one its settings name), its time and curve.
    internal class Picked<T>(val look: T, val millis: Long, val curve: Curve)

    // Window looks by their names, made once (asked every frame).
    private val namedLooks = HashMap<String, WindowAnimation?>()

    internal fun pick(name: String, code: WindowAnimation): Picked<WindowAnimation> {
        val resolved = animation(name, code.millis, code.curve)
        val style = resolved.style?.takeIf { it != "default" }
        val look = when {
            style == null -> code
            name == "windowsMove" || name == "scratchpads" -> if (style.trim().lowercase() == "none") WindowAnimation.NONE else code
            else -> namedLooks.getOrPut(style) { WindowAnimation.named(style) } ?: code
        }
        return Picked(look, if (look === WindowAnimation.NONE) 0L else resolved.effectiveMillis, resolved.curve)
    }

    internal fun pick(name: String, code: WorkspaceAnimation): Picked<WorkspaceAnimation> {
        val resolved = animation(name, code.millis, code.curve)
        val look = resolved.style?.takeIf { it != "default" }?.let(WorkspaceAnimation::named) ?: code
        return Picked(look, if (look === WorkspaceAnimation.NONE) 0L else resolved.effectiveMillis, resolved.curve)
    }

    // How much of an animation's time has passed (0 to 1), or null once it is over (or off).
    internal fun animationProgress(start: Long, millis: Long, now: Long): Double? {
        if (millis <= 0) return null
        val t = (now - start) / (millis * 1_000_000.0)
        return if (t >= 1.0) null else t.coerceAtLeast(0.0)
    }

    // Windows appearing, since when (and their picture, when a shader draws them).
    internal val appearing = HashMap<Window, Long>()

    internal val appearingPictures = HashMap<Window, GpuTarget>()

    // A picture of a window on its way — going away (its last look), into its dock icon or
    // back out of it — drawn by an animation from [from] to [to].
    internal class Flight(
        val target: GpuTarget, val texture: GpuTexture, val from: Rect, val to: Rect, val start: Long,
        val workspace: Workspace, val animation: WindowAnimation, val millis: Long, val curve: Curve,
        val kind: FlightKind, val window: Window?,
    )

    internal enum class FlightKind { CLOSE, MINIMIZE, RESTORE }

    internal val flights = ArrayList<Flight>()

    // Last looks taken just before a window's picture went (it may go away next).
    internal val lastLooks = HashMap<Window, Flight>()

    // A monitor changing its workspace: the one before goes out, the new one comes in.
    internal class Slide(val from: Workspace, val to: Workspace, val direction: Int, val start: Long)

    internal val slides = HashMap<Monitor, Slide>()

    // True while windows appear, go away or fly, or workspaces change: each frame is different.
    internal fun animating() = appearing.isNotEmpty() || flights.isNotEmpty() || slides.isNotEmpty()

    // A picture of [window] as it looks now (with its title bar, border and shadow), and where
    // it is: for animations that move the picture rather than the window. Null if it is not drawn.
    internal fun pictureOf(window: Window): Triple<GpuTarget, GpuTexture, Rect>? {
        val items = ArrayList<DrawItem>()
        desktop.sceneBuilder.addWindow(items, window, System.nanoTime(), plain = true)
        val parts = items.filter { it !is DrawItem.Blur } // (blur needs what is behind: not in a picture of its own)
        if (parts.none { it is DrawItem.Picture }) return null
        val box = parts.map { (it as? DrawItem.Shadow)?.box?.union(it.rect) ?: it.rect }.reduce(Rect::union)
        val scale = desktop.monitorOf(window).scale
        fun px(units: Int) = (units * scale).roundToInt()
        return try {
            val target = desktop.renderer.createTarget(px(box.width).coerceIn(1, 8192), px(box.height).coerceIn(1, 8192))
            val texture = target.texture ?: run {
                target.close()
                return null
            }
            desktop.renderer.begin(target, 0f, 0f, 0f, alpha = 0f)
            desktop.draw(parts, null, { r -> Rect(px(r.x - box.x), px(r.y - box.y), px(r.width).coerceAtLeast(1), px(r.height).coerceAtLeast(1)) }, scale)
            desktop.renderer.end()
            Triple(target, texture, box)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.renderLog.warn("A picture of $window for its animation could not be made (it changes without one): $e")
            null
        }
    }

    // [window] on its way as a picture (see Flight), if it is on screen and the animation is on.
    internal fun flightOf(window: Window, kind: FlightKind, name: String, code: WindowAnimation, to: Rect? = null, workspace: Workspace? = desktop.workspaceOf(window)): Flight? {
        val picked = pick(name, code)
        val millis = picked.millis
        if (millis <= 0 || desktop.locked || desktop.inOverview || workspace == null || desktop.monitorList.none { it.workspace === workspace } || desktop.scratchpadOf(window) != null) return null
        if (!desktop.behaviorOf(window).animated) return null
        val (target, texture, box) = pictureOf(window) ?: return null
        return Flight(target, texture, box, to ?: box, System.nanoTime(), workspace, picked.look, millis, picked.curve, kind, window)
    }

    // A window's pictures on their way are let go (it does something else now).
    internal fun stopFlights(window: Window) {
        for (flight in flights.filter { it.window === window }) {
            flights -= flight
            flight.target.close()
        }
    }

    // The last look of a window that is going away (taken while its picture is still there).
    internal fun lastLookOf(window: Window): Flight? {
        val workspace = desktop.workspaceOf(window) ?: return null
        if (window !in workspace.windows) return null
        return flightOf(window, FlightKind.CLOSE, "windowsOut", desktop.animations.windowClose, workspace = workspace)
    }

    // How a flight is drawn now (null once it is over).
    private fun flightItem(flight: Flight, now: Long): DrawItem? {
        val t = animationProgress(flight.start, flight.millis, now) ?: return null
        val e = flight.curve.safeAt(t)
        // Going away and coming back out of the dock run backwards (1 → 0).
        val progress = if (flight.kind == FlightKind.MINIMIZE) e else 1 - e
        val area = desktop.monitorList.firstOrNull { it.workspace === flight.workspace }?.usableArea ?: desktop.focusedMonitor.usableArea
        val frame = try {
            flight.animation.frame(Motion(flight.window, flight.from, flight.to, progress, t, area))
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.log.error("Your window animation failed (it is drawn without): $e", e)
            WindowFrame(flight.to)
        }
        return frameItem(flight, flight.texture, frame)
    }

    // The item that draws [texture] as [frame] says (through its shader, if it has one).
    internal fun frameItem(key: Any, texture: GpuTexture, frame: WindowFrame): DrawItem {
        val shader = frame.shader ?: return DrawItem.Image(key, texture, frame.rect, alpha = frame.alpha)
        val box = frame.rect
        fun part(r: Rect) = floatArrayOf(
            (r.x - box.x).toFloat() / box.width, (r.y - box.y).toFloat() / box.height,
            r.width.toFloat() / box.width, r.height.toFloat() / box.height,
        )
        return DrawItem.Effect(
            key, texture, box, frame.alpha, shader, part(frame.shaderFrom), part(frame.shaderTo), frame.progress.toFloat(),
            fallback = Motion.mix(frame.shaderFrom, frame.shaderTo, frame.progress),
        )
    }

    // A window on its way: where it started; the end is wherever the window is now.
    internal class Glide(val from: Rect, val startNanos: Long, val to: Rect? = null, val name: String = "windowsMove")

    internal val glides = HashMap<Window, Glide>()

    // [window] stops gliding where it is (a drag takes it from there).
    internal fun stopGlide(window: Window) {
        glides -= window
    }

    // Where each window was drawn last (with any glide), so a new glide starts there.
    internal val shownRects = HashMap<Window, Rect>()

    // Where each window appears on screen (with the overview's zoom).
    internal val screenRects = HashMap<Window, Rect>()

    // Moves [window] to ([x], [y]); if it was on screen, it glides there instead of jumping.
    internal fun moveSmoothly(window: Window, x: Int, y: Int) {
        val shown = shownRects[window]
        window.x = x
        window.y = y
        val name = desktop.scratchpadOf(window)?.let(desktop.pads::padAnimation) ?: "windowsMove"
        if (shown != null && (window in desktop.activeWorkspace.windows || desktop.scratchpadOf(window) != null) && desktop.behaviorOf(window).animated && pick(name, desktop.animations.windowMove).millis > 0) {
            glides[window] = Glide(shown, System.nanoTime(), name = name)
        }
    }

    // Where a gliding window is drawn now: from its start towards its current place, fast
    // at first and gently slowing down. Null when the glide is over.
    internal fun glidePosition(glide: Glide, current: Rect, nowNanos: Long): Rect? {
        val target = glide.to ?: current
        val picked = pick(glide.name, desktop.animations.windowMove)
        val t = animationProgress(glide.startNanos, picked.millis, nowNanos) ?: return null
        return try {
            picked.look.frame(Motion(null, glide.from, target, picked.curve.safeAt(t), t, desktop.focusedMonitor.usableArea)).rect
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            desktop.log.error("Your window move animation failed (windows jump): $e", e)
            null
        }
    }

    // The pictures of windows on their way of [kind] (those that arrived are let go).
    internal fun addFlights(all: MutableList<DrawItem>, now: Long, kind: FlightKind) {
        for (flight in flights.toList()) {
            if (flight.kind != kind) continue
            val item = flightItem(flight, now)
            if (item == null) {
                flights -= flight
                flight.target.close()
                if (kind == FlightKind.RESTORE) desktop.changed() // the window itself is drawn again
                continue
            }
            if (desktop.monitorList.any { it.workspace === flight.workspace }) all += item
            // Where the window is drawn now, for shownRect (going away: it is not there any more).
            if (flight.window != null && kind != FlightKind.CLOSE) {
                shownRects[flight.window] = (item as? DrawItem.Effect)?.fallback ?: item.rect
            }
        }
    }
}
