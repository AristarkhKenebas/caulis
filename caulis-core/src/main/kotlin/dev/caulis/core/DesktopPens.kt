package dev.caulis.core

import dev.caulis.core.input.Seat
import dev.caulis.core.input.Tablet
import dev.caulis.core.input.TabletAxes
import dev.caulis.core.input.TabletTool

/**
 * The desktop's pens on drawing tablets: over a program that takes tablet events it gets the
 * pen as a pen (through [Desktop.tablets]), elsewhere the pen is the pointer
 * ([Desktop.penPointer]). [Desktop.tabletProximity] and the rest hand over to it; it lives as
 * long as its desktop.
 */
internal class DesktopPens(private val desktop: Desktop, private val seat: Seat) {

    // Each tool near a tablet: its tablet, where it is, its tip, and the program's surface it is
    // over (null: it is the pointer there). While its tip or a button is down it stays where
    // that began — a program's surface, or the pointer — as a mouse with a button held does.
    private class Pen(val tablet: Tablet) {
        var x = 0.0
        var y = 0.0
        var tip = false
        var surface: Surface? = null
        var window: Window? = null
        // Where that surface's top left corner is (desktop units), and how stretched it is drawn.
        var originX = 0.0
        var originY = 0.0
        var scaleX = 1.0
        var scaleY = 1.0
        // Buttons held: each one's code as it was passed on (the pointer's button, or the pen's own).
        val buttons = HashMap<Int, Int>()
        // The tip went down only to close a menu of ours: its lifting is nobody's either.
        var swallowed = false
    }

    private val pens = HashMap<TabletTool, Pen>()

    /** A pen is over a program that takes it as a pen (that program has it, not the pointer). */
    val onPrograms: Boolean get() = pens.values.any { it.surface != null }

    fun proximity(timeMillis: Int, tablet: Tablet, tool: TabletTool, x: Double, y: Double, axes: TabletAxes, near: Boolean) {
        desktop.onUserActivity()
        if (near) {
            val pen = pens.getOrPut(tool) {
                desktop.inputLog.trace { "The $tool came near ${tablet.name}" }
                Pen(tablet)
            }
            moved(timeMillis, tool, pen, x, y, axes)
            return
        }
        val pen = pens[tool] ?: return
        if (pen.tip) tip(timeMillis, tool, false)
        for (held in pen.buttons.keys.toList()) button(timeMillis, tool, held, false)
        pens.remove(tool)
        if (pen.surface != null) desktop.tablets?.proximityOut(timeMillis, tool)
        desktop.inputLog.trace { "The $tool went away" }
    }

    fun motion(timeMillis: Int, tool: TabletTool, x: Double, y: Double, axes: TabletAxes) {
        val pen = pens[tool] ?: return
        desktop.onUserActivity()
        moved(timeMillis, tool, pen, x, y, axes)
    }

    fun tip(timeMillis: Int, tool: TabletTool, down: Boolean) {
        val pen = pens[tool] ?: return
        if (pen.tip == down) return
        desktop.onUserActivity()
        pen.tip = down
        if (pen.surface == null) return desktop.penPointer.button(timeMillis, BTN_LEFT, down)
        if (down) {
            // A touch outside our open menus closes them, as a click does (and only that).
            if (desktop.penPressedOutside(pen.x, pen.y)) {
                pen.swallowed = true
                return
            }
            pen.window?.let { if (it !== desktop.focusedWindow) desktop.focus(it) }
        } else if (pen.swallowed) {
            pen.swallowed = false
            return
        }
        desktop.tablets?.tip(timeMillis, tool, down)
    }

    fun button(timeMillis: Int, tool: TabletTool, button: Int, pressed: Boolean) {
        val pen = pens[tool] ?: return
        desktop.onUserActivity()
        if (pressed) {
            if (button in pen.buttons) return
            if (pen.surface != null) {
                pen.buttons[button] = button
                desktop.tablets?.button(timeMillis, tool, button, true)
            } else {
                val asPointer = pen.tablet.settings.pointerButtonOf(button)
                pen.buttons[button] = asPointer
                if (asPointer != 0) desktop.penPointer.button(timeMillis, asPointer, true)
            }
            return
        }
        val sent = pen.buttons.remove(button) ?: return
        if (pen.surface != null) desktop.tablets?.button(timeMillis, tool, button, false) else if (sent != 0) desktop.penPointer.button(timeMillis, sent, false)
    }

    private fun moved(timeMillis: Int, tool: TabletTool, pen: Pen, x: Double, y: Double, axes: TabletAxes) {
        pen.x = x
        pen.y = y
        if (pen.tip || pen.buttons.isNotEmpty()) {
            val surface = pen.surface ?: return desktop.penPointer.moved(timeMillis, x, y)
            if (surface.resource.isAlive && seat.allowed(surface) && desktop.tablets?.surfaceOf(tool) === surface) {
                desktop.showPenAt(x, y)
                desktop.tablets?.motion(timeMillis, tool, (x - pen.originX) / pen.scaleX, (y - pen.originY) / pen.scaleY, axes)
                return
            }
            // The surface went away (or the screen was locked) under the pen: what it held is let go.
            desktop.inputLog.trace { "The $tool lost the surface it touched" }
            pen.tip = false
            pen.swallowed = false
            pen.buttons.clear()
        }
        val hit = desktop.tabletTarget(pen.tablet, x, y)
        if (hit == null) {
            if (pen.surface != null) {
                desktop.tablets?.proximityOut(timeMillis, tool)
                pen.surface = null
                pen.window = null
            }
            desktop.penPointer.moved(timeMillis, x, y)
            return
        }
        // Onto a program's surface: the pointer leaves whatever it was over (programs, our widgets and bars).
        if (pen.surface == null) desktop.pointerAway(timeMillis)
        pen.surface = hit.surface
        pen.window = hit.root.roleObject as? Window
        pen.originX = x - hit.localX * hit.scaleX
        pen.originY = y - hit.localY * hit.scaleY
        pen.scaleX = hit.scaleX
        pen.scaleY = hit.scaleY
        desktop.showPenAt(x, y)
        desktop.tablets?.proximityIn(timeMillis, pen.tablet, tool, hit.surface, hit.localX, hit.localY, axes)
    }
}

// The left mouse button (a Linux code): what a pen's tip is when the pen is the pointer.
private const val BTN_LEFT = 0x110
