package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.tiling.TilingCommand
import kotlin.math.roundToInt

/**
 * The overview: the camera zooming out over the windows, gestures that open it, dragging the
 * view, clicking a window in it.
 */
internal class DesktopOverview(private val desktop: Desktop) {

    // What the screen shows of the desktop: screen = world × scale + (dx, dy).
    internal class Camera(val scale: Double, val dx: Double, val dy: Double) {
        fun apply(r: Rect) = Rect(
            (r.x * scale + dx).roundToInt(), (r.y * scale + dy).roundToInt(),
            (r.width * scale).roundToInt().coerceAtLeast(1), (r.height * scale).roundToInt().coerceAtLeast(1),
        )

        fun mix(other: Camera, t: Double) = Camera(scale + (other.scale - scale) * t, dx + (other.dx - dx) * t, dy + (other.dy - dy) * t)

        companion object {
            val NONE = Camera(1.0, 0.0, 0.0)
        }
    }

    internal var cameraFrom = Camera.NONE

    internal var cameraTo = Camera.NONE

    private var cameraStart = 0L

    fun setOverview(on: Boolean) {
        if (on == desktop.inOverview || (on && desktop.locked)) return
        desktop.layoutLog.info { if (on) "Overview opens" else "Overview closes" }
        desktop.pointerGrab.end()
        val now = System.nanoTime()
        cameraFrom = cameraAt(now)
        cameraTo = if (on) overviewCamera() else Camera.NONE
        cameraStart = now
        desktop.inOverview = on
        // Programs do not see the pointer while the overview is open.
        if (on) {
            desktop.seat.pointerMotion((now / 1_000_000).toInt(), null, 0.0, 0.0)
            desktop.widgetHost.leaveWidgets()
        }
        desktop.changed()
    }

    // The overview's zoom: its own time, else 1.5 × the global one.
    private fun overviewAnimation() = desktop.animation("overview", desktop.animations.overviewMillis ?: (desktop.animationMillis * 3 / 2))

    private val cameraMillis: Long get() = overviewAnimation().effectiveMillis.coerceAtLeast(1)

    internal fun cameraAt(now: Long): Camera {
        val zoom = overviewAnimation()
        val t = (now - cameraStart) / (zoom.effectiveMillis.coerceAtLeast(1) * 1_000_000.0)
        if (t >= 1.0 || !zoom.on) return cameraTo
        return cameraFrom.mix(cameraTo, zoom.curve.safeAt(t))
    }

    internal fun cameraMoving(now: Long) = cameraTo !== cameraFrom && (now - cameraStart) < cameraMillis * 1_000_000

    // Fits all windows of the workspace (and its monitor) into 90% of the monitor with the focus.
    private fun overviewCamera(): Camera {
        val area = desktop.focusedMonitor.area
        var box = area
        for (window in desktop.activeWorkspace.windows) box = box.union(desktop.windowRoles.currentRect(window))
        val scale = minOf(1.0, area.width * desktop.overviewFill / box.width, area.height * desktop.overviewFill / box.height)
        return Camera(
            scale,
            area.x + area.width / 2.0 - (box.x + box.width / 2.0) * scale,
            area.y + area.height / 2.0 - (box.y + box.height / 2.0) * scale,
        )
    }

    // The overview's camera while a pinch opens or closes it (null = no pinch going on).
    internal var overviewTarget: Camera? = null

    fun setOverviewProgress(progress: Double) {
        if (desktop.locked) return
        val target = overviewTarget ?: overviewCamera().also { overviewTarget = it }
        val camera = Camera.NONE.mix(target, progress.coerceIn(0.0, 1.0))
        cameraFrom = camera
        cameraTo = camera // no animation: it follows the fingers exactly
        desktop.changed()
    }

    fun endOverviewGesture(open: Boolean) {
        // (Locked meanwhile: the lock closed the overview, and it does not open behind it.)
        if (desktop.locked) {
            overviewTarget = null
            return
        }
        val now = System.nanoTime()
        val target = overviewTarget ?: overviewCamera()
        overviewTarget = null
        cameraFrom = cameraAt(now)
        cameraTo = if (open) target else Camera.NONE
        cameraStart = now
        desktop.inOverview = open
        if (open) desktop.seat.pointerMotion((now / 1_000_000).toInt(), null, 0.0, 0.0)
        desktop.changed()
    }

    // How far the fingers dragged the view so far (tiled windows move along).
    internal var dragX = 0.0

    internal var dragY = 0.0

    fun dragView(dx: Double, dy: Double) {
        dragX += dx
        dragY += dy
        desktop.changed()
    }

    fun endViewDrag() {
        val dx = dragX.roundToInt()
        val dy = dragY.roundToInt()
        dragX = 0.0
        dragY = 0.0
        val workspace = desktop.activeWorkspace
        workspace.model.command(TilingCommand.PanBy(-dx, -dy), desktop.focusedWindow?.takeIf { it.tiled })
        desktop.arranger.arrange(workspace)
        val screen = desktop.focusedMonitor.area
        fun visibleShare(window: Window): Double {
            val target = desktop.arranger.pending[window] ?: desktop.windowRoles.currentRect(window)
            val inView = target.intersect(screen) ?: return 0.0
            return inView.width.toDouble() * inView.height / (target.width.toDouble() * target.height)
        }
        val focused = desktop.focusedWindow
        if (focused == null || (focused.tiled && visibleShare(focused) < 0.5)) {
            workspace.windows.filter { it.tiled }.maxByOrNull(::visibleShare)?.takeIf { visibleShare(it) > 0 }?.let(desktop::focus)
        }
        desktop.changed()
    }

    // A click in the overview: on a window = go there; elsewhere = just leave.
    internal fun overviewClick() {
        val camera = cameraAt(System.nanoTime())
        val point = Rect(desktop.pointerX.toInt(), desktop.pointerY.toInt(), 1, 1)
        val chosen = desktop.sceneBuilder.stackingOrder().asReversed().firstOrNull { camera.apply(desktop.windowRoles.currentRect(it)).contains(point) }
        setOverview(false)
        if (chosen != null) desktop.focus(chosen)
    }
}
