package dev.caulis.core

import dev.caulis.core.input.Seat

/**
 * The desktop's touch screens: which finger touches which program, and the one finger that is
 * the pointer where programs do not take touches. [Desktop.touchDown] and the rest hand over
 * to it; it lives as long as its desktop.
 */
internal class DesktopTouch(private val desktop: Desktop, private val seat: Seat) {

    // Each finger down: the surface it touches and where that surface is (desktop units), or
    // null: the finger is the pointer (on our widgets, a menu, programs without touch).
    private class TouchPoint(val surface: Surface?, val x: Double, val y: Double, val scaleX: Double, val scaleY: Double)

    private val touchPoints = HashMap<Int, TouchPoint>()

    // The finger that is the pointer now, if any (only one at a time).
    private var pointerFinger: Int? = null

    /** A finger touches a program now (not one that is the pointer). */
    val touchingPrograms: Boolean get() = touchPoints.values.any { it.surface != null }

    fun down(timeMillis: Int, id: Int, x: Double, y: Double) {
        desktop.onUserActivity()
        val plain = !desktop.locked && desktop.overlay == null && !desktop.inOverview && desktop.grab == null && desktop.grabs.isEmpty()
        val hit = if (plain) (desktop.pointedAt(x, y, widgetsToo = true) as? Desktop.Pointed.AtSurface)?.hit else null
        if (hit != null && seat.takesTouch(hit.surface)) {
            (hit.root.roleObject as? Window)?.let { if (it !== desktop.focusedWindow) desktop.focus(it) }
            touchPoints[id] = TouchPoint(hit.surface, x - hit.localX * hit.scaleX, y - hit.localY * hit.scaleY, hit.scaleX, hit.scaleY)
            desktop.inputLog.trace { "Touch $id on ${hit.root.roleObject ?: hit.surface}" }
            seat.touchDown(timeMillis, hit.surface, id, hit.localX, hit.localY)
            return
        }
        if (pointerFinger != null) return
        pointerFinger = id
        touchPoints[id] = TouchPoint(null, 0.0, 0.0, 1.0, 1.0)
        desktop.inputLog.trace { "Touch $id as the pointer" }
        desktop.pointerMoved(timeMillis, x, y)
        desktop.pointerButton(timeMillis, BTN_LEFT, true)
    }

    fun motion(timeMillis: Int, id: Int, x: Double, y: Double) {
        val point = touchPoints[id] ?: return
        desktop.onUserActivity()
        if (point.surface == null) {
            if (pointerFinger == id) desktop.pointerMoved(timeMillis, x, y)
            return
        }
        seat.touchMotion(timeMillis, id, (x - point.x) / point.scaleX, (y - point.y) / point.scaleY)
    }

    fun up(timeMillis: Int, id: Int) {
        val point = touchPoints.remove(id) ?: return
        if (point.surface == null) {
            if (pointerFinger == id) {
                pointerFinger = null
                desktop.pointerButton(timeMillis, BTN_LEFT, false)
            }
            return
        }
        seat.touchUp(timeMillis, id)
    }

    fun cancel(timeMillis: Int) {
        seat.touchCancel()
        if (pointerFinger != null) desktop.pointerButton(timeMillis, BTN_LEFT, false)
        pointerFinger = null
        touchPoints.clear()
    }
}

// The left mouse button (a Linux code): what a finger that is the pointer presses.
private const val BTN_LEFT = 0x110
