package dev.caulis.core

import dev.caulis.geometry.Rect

/** A key as a [Widget] gets it: its code, what it types, and the modifiers held. */
class WidgetKey(
    /** The Linux key code (1 Escape, 28 Enter, 14 Backspace, 103 Up, ...). */
    val evdevCode: Int,
    val pressed: Boolean,
    /** What it types with the layout and the modifiers now ("a", "A", "ж", "€"), or null (arrows, Enter, F1). */
    val text: String?,
    val shift: Boolean,
    val ctrl: Boolean,
    val alt: Boolean,
    val logo: Boolean,
    val timeMillis: Int,
) {
    override fun toString() = "WidgetKey($evdevCode ${if (pressed) "down" else "up"}${text?.let { " \"$it\"" } ?: ""})"
}

/** An edge of a monitor. */
enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

/**
 * Room a [Widget] keeps free at an [edge] of its monitor, [size] units deep: windows and panels
 * make way. The room is right at the edge (after the desktop's own banners), the widgets'
 * strips side by side in the order they were added, panels after them.
 */
data class Reserve(val edge: Edge, val size: Int) {
    init {
        require(size >= 0) { "a reserve cannot be negative ($size)" }
    }
}

/**
 * A part of the compositor's own interface — a bar, a dock, a volume pill, a launcher, a
 * notification — drawn by your code and getting the pointer where it is, and the keyboard
 * when it asks ([Desktop.addWidget]). Unlike an [Overlay] it takes nothing from the programs
 * elsewhere; unlike a [Drawing] it gets input. Coordinates it is told are its own (0, 0 at the
 * top left of [area]).
 *
 * caulis-compose makes one from Compose; anything else that draws pictures works too.
 */
interface Widget {

    /** Where it is on [monitor] now (desktop units), or null: not on that monitor. */
    fun area(monitor: Monitor): Rect?

    /** Among windows, panels and the rest: on top (the default), above the windows, or below them. */
    val level: DrawingLevel get() = DrawingLevel.ON_TOP

    /** Room it keeps free at an edge of [monitor] (a bar), or null: none — it floats above. */
    fun reserve(monitor: Monitor): Reserve? = null

    /** What it shows on [monitor] inside [area] (desktop units). */
    fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect)

    /** True while it changes by itself (an animation): frames keep coming. Else [Desktop.requestRedraw] after a change. */
    fun animating(now: Long): Boolean = false

    /** The pointer is at [x], [y] (its own units) of its part on [monitor] — entering or moving. */
    fun pointerMoved(monitor: Monitor, x: Double, y: Double) {}

    /** The pointer left it. */
    fun pointerLeft() {}

    /** A mouse button (272 left, 273 right, 274 middle) pressed or let go over it (or after a press in it, anywhere). */
    fun pointerButton(button: Int, pressed: Boolean) {}

    /** The wheel or two fingers: [dx], [dy] in units (positive: right, down). */
    fun scroll(dx: Double, dy: Double) {}

    /** The cursor over it, by name ("default", "pointer", "text", ...). */
    val cursor: String get() = "default"

    /** True if a click gives it the keyboard (a text field, a launcher). It keeps it until [Desktop.focusWidget] (null) or a click elsewhere. */
    val takesKeyboard: Boolean get() = false

    /** A key while it has the keyboard. */
    fun key(key: WidgetKey) {}

    /** It got the keyboard (true) or lost it. */
    fun keyboardFocus(has: Boolean) {}
}
