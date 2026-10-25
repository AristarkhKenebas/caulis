package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.render.GpuTexture

/**
 * Something of the compositor's own above everything — a screenshot chooser, a launcher or a
 * menu drawn by the compositor itself — that has the keyboard and the pointer while it is
 * open ([Desktop.openOverlay]). Programs get no input meanwhile (keys let go still reach
 * them, so none stays stuck); quitting and the text consoles still work. Locking the screen
 * closes it.
 */
interface Overlay {

    /** What it shows on [monitor], bottom first, in desktop units (the monitor's [Monitor.area]). */
    fun draw(monitor: Monitor, canvas: OverlayCanvas)

    /** A key pressed or let go (its evdev code: 1 is Escape, 28 Enter, ...). */
    fun key(evdevCode: Int, pressed: Boolean) {}

    /** The pointer moved to [x], [y] (desktop units). */
    fun pointerMoved(x: Double, y: Double) {}

    /** A mouse button (272 left, 273 right, 274 middle) pressed or let go where the pointer is. */
    fun pointerButton(button: Int, pressed: Boolean) {}

    /** The wheel turned by [steps] (one notch is 1; negative: up or left). */
    fun scroll(vertical: Boolean, steps: Double) {}

    /** The cursor over it, by name ("default", "crosshair", "move", "pointer", ...). */
    val cursor: String get() = "default"

    /** It was closed ([Desktop.closeOverlay], or the screen was locked). */
    fun closed() {}
}

/** Where an [Overlay] draws: pictures and plain colours, in desktop units. */
class OverlayCanvas internal constructor(private val monitor: Monitor, private val key: Any) {

    internal val items = ArrayList<DrawItem>()

    /** [texture] stretched over [rect], [alpha] as strong, its corners rounded by [radius]. */
    @JvmOverloads
    fun image(texture: GpuTexture, rect: Rect, alpha: Float = 1f, radius: Float = 0f) {
        items += DrawItem.Image(listOf(key, "image", items.size), texture, rect, clip = if (radius > 0f) rect else null, radius = radius, alpha = alpha).also { it.only = monitor }
    }

    /** [rect] filled with a colour (0..1 each), [alpha] as strong. */
    @JvmOverloads
    fun fill(rect: Rect, red: Float, green: Float, blue: Float, alpha: Float = 1f) {
        if (rect.width <= 0 || rect.height <= 0) return
        items += DrawItem.Color(listOf(key, "fill", items.size), rect, red, green, blue, alpha = alpha).also { it.only = monitor }
    }

    /** A frame [width] thick just inside [rect]. */
    @JvmOverloads
    fun frame(rect: Rect, width: Int, red: Float, green: Float, blue: Float, alpha: Float = 1f) {
        fill(Rect(rect.x, rect.y, rect.width, width), red, green, blue, alpha)
        fill(Rect(rect.x, rect.bottom - width, rect.width, width), red, green, blue, alpha)
        fill(Rect(rect.x, rect.y + width, width, rect.height - 2 * width), red, green, blue, alpha)
        fill(Rect(rect.right - width, rect.y + width, width, rect.height - 2 * width), red, green, blue, alpha)
    }
}
