package dev.caulis.core

import dev.caulis.core.input.Seat
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer

/**
 * A simple floating window manager: windows open in a cascade, a click raises and focuses
 * a window, popups close when clicking elsewhere. It also draws everything.
 *
 * (Layouts — tiling, Hyprland-like, newm-like — replace this in stage 6.)
 */
class Desktop(
    private val renderer: Renderer,
    private val seat: Seat,
    var width: Int,
    var height: Int,
) : XdgShell.Listener {

    /** Mapped windows, bottom to top. */
    val windows = ArrayList<Window>()

    /** Mapped popups, in the order they opened (children after parents). */
    private val popups = ArrayList<Popup>()

    /** Popups that asked for a grab: a click outside them closes them. */
    private val grabs = ArrayList<Popup>()

    private var cascade = 0

    /** Pointer position on the output. */
    var pointerX = width / 2.0
        private set
    var pointerY = height / 2.0
        private set

    // Surfaces drawn in the last frame; they get "frame done" after it is shown.
    private val drawn = ArrayList<Surface>()

    // --- windows and popups -----------------------------------------------------------

    override fun windowMapped(window: Window) {
        val visible = window.shell.visibleRect
        // Cascade from the top left, wrapping around before leaving the output.
        val step = 32
        window.x = (40 + cascade * step).coerceAtMost((width - visible.width).coerceAtLeast(0))
        window.y = (40 + cascade * step).coerceAtMost((height - visible.height).coerceAtLeast(0))
        cascade = (cascade + 1) % 8
        windows += window
        focus(window)
    }

    override fun windowUnmapped(window: Window) {
        windows -= window
        if (seat.keyboardFocus === window.surface) focus(windows.lastOrNull())
    }

    override fun popupMapped(popup: Popup) {
        popups += popup
    }

    override fun popupUnmapped(popup: Popup) {
        popups -= popup
        grabs -= popup
        if (seat.keyboardFocus === popup.surface) {
            seat.focusKeyboard(grabs.lastOrNull()?.surface ?: windows.lastOrNull()?.surface)
        }
    }

    override fun popupGrab(popup: Popup) {
        grabs += popup
        seat.focusKeyboard(popup.surface)
    }

    /** Raises [window], marks it active and gives it the keyboard (null = nothing focused). */
    fun focus(window: Window?) {
        for (other in windows) if (other !== window) other.setActivated(false)
        if (window != null) {
            windows -= window
            windows += window
            window.setActivated(true)
        }
        seat.focusKeyboard(window?.surface)
    }

    // --- input ------------------------------------------------------------------------

    fun pointerMoved(timeMillis: Int, x: Double, y: Double) {
        pointerX = x.coerceIn(0.0, width - 1.0)
        pointerY = y.coerceIn(0.0, height - 1.0)
        val hit = surfaceAt(pointerX, pointerY)
        seat.pointerMotion(timeMillis, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
    }

    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        if (pressed) {
            val hit = surfaceAt(pointerX, pointerY)
            // A click outside the open menus closes them (and is not passed to the window below).
            if (grabs.isNotEmpty() && (hit == null || popups.none { it.surface === hit.root })) {
                grabs.toList().asReversed().forEach { it.dismiss() }
                grabs.clear()
                return
            }
            val window = hit?.root?.roleObject as? Window
            if (window != null && window !== windows.lastOrNull()) focus(window)
        }
        seat.pointerButton(timeMillis, button, pressed)
    }

    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) =
        seat.pointerAxis(timeMillis, vertical, value, value120)

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) = seat.key(timeMillis, evdevCode, pressed)

    /** What is under a point: the surface, the point inside it, and the window/popup surface it belongs to. */
    class Hit(val surface: Surface, val localX: Double, val localY: Double, val root: Surface)

    private fun surfaceAt(x: Double, y: Double): Hit? {
        for (popup in popups.asReversed()) {
            val (px, py) = popupOrigin(popup)
            hitTree(popup.surface, x - px, y - py, popup.surface)?.let { return it }
        }
        for (window in windows.asReversed()) {
            val visible = window.shell.visibleRect
            hitTree(window.surface, x - (window.x - visible.x), y - (window.y - visible.y), window.surface)?.let { return it }
        }
        return null
    }

    // Checks a surface and its subsurfaces, topmost first. x, y are relative to [surface].
    private fun hitTree(surface: Surface, x: Double, y: Double, root: Surface): Hit? {
        for (child in surface.stack.asReversed()) {
            if (child === surface) {
                if (surface.hasContent && surface.acceptsInput(x, y)) return Hit(surface, x, y, root)
            } else {
                val sub = child.subsurface ?: continue
                hitTree(child, x - sub.x, y - sub.y, root)?.let { return it }
            }
        }
        return null
    }

    // Where a popup's surface starts on the output.
    private fun popupOrigin(popup: Popup): Pair<Int, Int> {
        val (parentX, parentY) = visibleOrigin(popup.parent)
        val visible = popup.shell.visibleRect
        return (parentX + popup.x - visible.x) to (parentY + popup.y - visible.y)
    }

    // Where the visible top left corner of a window or popup is on the output.
    private fun visibleOrigin(shell: ShellSurface): Pair<Int, Int> = when (val role = shell.role) {
        is Window -> role.x to role.y
        is Popup -> {
            val (parentX, parentY) = visibleOrigin(role.parent)
            (parentX + role.x) to (parentY + role.y)
        }
        else -> 0 to 0
    }

    // --- drawing ----------------------------------------------------------------------

    /** Draws one frame into [target]. */
    fun render(target: GpuTarget) {
        drawn.clear()
        renderer.begin(target, BACKGROUND[0], BACKGROUND[1], BACKGROUND[2])
        for (window in windows) {
            val visible = window.shell.visibleRect
            drawTree(window.surface, window.x - visible.x, window.y - visible.y)
        }
        for (popup in popups) {
            val (x, y) = popupOrigin(popup)
            drawTree(popup.surface, x, y)
        }
        drawCursor()
    }

    private fun drawTree(surface: Surface, x: Int, y: Int) {
        for (child in surface.stack) {
            if (child === surface) {
                val texture = surface.texture ?: continue
                renderer.drawTexture(texture, x, y, surface.width, surface.height, surface.opaque)
                drawn += surface
            } else {
                val sub = child.subsurface ?: continue
                drawTree(child, x + sub.x, y + sub.y)
            }
        }
    }

    private fun drawCursor() {
        val x = pointerX.toInt()
        val y = pointerY.toInt()
        val cursor = seat.cursorSurface
        when {
            cursor != null && cursor.hasContent -> drawTree(cursor, x - seat.cursorHotspotX, y - seat.cursorHotspotY)
            seat.cursorIsDefault -> {
                // A simple default cursor until cursor themes are supported.
                renderer.drawColor(x, y, 12, 12, 0f, 0f, 0f)
                renderer.drawColor(x + 1, y + 1, 10, 10, 1f, 1f, 1f)
            }
        }
    }

    /** Tells every surface drawn in the last frame that it was shown. */
    fun frameShown(timeMillis: Int) {
        for (surface in drawn) surface.sendFrameDone(timeMillis)
        drawn.clear()
    }

    private companion object {
        val BACKGROUND = floatArrayOf(0.16f, 0.18f, 0.22f)
    }
}
