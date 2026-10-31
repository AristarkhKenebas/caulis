package dev.caulis.wm

import dev.caulis.core.input.Gesture
import dev.caulis.tiling.Direction

/**
 * Touchpad gestures: a swipe or pinch going to the overview, your binding or the program.
 */
internal class RunningGestures(private val running: Running) {

    // What the gesture going on does: drag the view, open the overview, a swipe action, or
    // nothing of ours (then it goes to the program under the pointer).
    private enum class GestureUse { NONE, DRAG, SWIPE, PINCH, PROGRAM, CLAIMED }

    // The configuration's handler that took the gesture going on (onGesture).
    private var gestureClaimedBy: (Caulis.(Gesture) -> Boolean)? = null

    private var gestureUse = GestureUse.NONE

    private var gestureFingers = 0

    private var swipeX = 0.0

    private var swipeY = 0.0

    private var pinchStartedOpen = false

    private var pinchProgress = 0.0

    private val swipes: List<Triple<Int, Direction, Caulis.() -> Unit>> get() = buildList {
        val fingers = running.config.workspaceSwipeFingers
        if (fingers > 0) {
            add(Triple(fingers, Direction.LEFT) { desktop.switchTo(desktop.activeWorkspace.number + 1) })
            add(Triple(fingers, Direction.RIGHT) { desktop.switchTo((desktop.activeWorkspace.number - 1).coerceAtLeast(1)) })
        }
        addAll(running.config.swipes) // the user's own win (the last one fits)
    }

    /** A touchpad gesture: the compositor's own (see [Caulis.dragViewFingers] ...) or the program's. */
    fun gesture(gesture: Gesture) = running.safely("gesture") {
        running.userActive()
        running.gesturesLog.trace { "$gesture (fingers ${gestureFingers}, use $gestureUse)" }
        val begins = gesture is Gesture.SwipeBegin || gesture is Gesture.PinchBegin || gesture is Gesture.HoldBegin
        val ends = gesture is Gesture.SwipeEnd || gesture is Gesture.PinchEnd || gesture is Gesture.HoldEnd
        // A gesture of the configuration's own: asked at its start, then it has all of it.
        if (begins && !running.desktop.locked) {
            gestureClaimedBy = running.config.gestureHandlers.asReversed().firstOrNull { handler -> running.safely("onGesture", false) { handler(running.config, gesture) } }
            if (gestureClaimedBy != null) {
                running.gesturesLog.info { "$gesture: taken by onGesture" }
                gestureUse = GestureUse.CLAIMED
                return@safely
            }
        }
        if (gestureUse == GestureUse.CLAIMED) {
            gestureClaimedBy?.let { handler -> running.safely("onGesture", false) { handler(running.config, gesture) } }
            if (ends) {
                gestureUse = GestureUse.NONE
                gestureClaimedBy = null
            }
            return@safely
        }
        when (gesture) {
            is Gesture.SwipeBegin -> {
                gestureFingers = gesture.fingers
                swipeX = 0.0
                swipeY = 0.0
                gestureUse = when {
                    running.desktop.locked -> GestureUse.PROGRAM // the locker's
                    gesture.fingers == running.config.dragViewFingers -> GestureUse.DRAG
                    swipes.any { it.first == gesture.fingers } -> GestureUse.SWIPE
                    else -> GestureUse.PROGRAM
                }
            }
            is Gesture.SwipeUpdate -> when (gestureUse) {
                GestureUse.DRAG -> running.desktop.dragView(gesture.dx, gesture.dy)
                GestureUse.SWIPE -> {
                    swipeX += gesture.dx
                    swipeY += gesture.dy
                }
                else -> {}
            }
            is Gesture.SwipeEnd -> when (gestureUse) {
                GestureUse.DRAG -> running.desktop.endViewDrag()
                GestureUse.SWIPE -> if (!gesture.cancelled) swipeDirection()?.let { direction ->
                    swipes.lastOrNull { it.first == gestureFingers && it.second == direction }?.let { (_, _, action) -> action(running.config) }
                }
                else -> {}
            }
            is Gesture.PinchBegin -> {
                gestureFingers = gesture.fingers
                gestureUse = if (gesture.fingers == running.config.overviewPinchFingers && !running.desktop.locked) GestureUse.PINCH else GestureUse.PROGRAM
                pinchStartedOpen = running.desktop.inOverview
                pinchProgress = if (pinchStartedOpen) 1.0 else 0.0
            }
            is Gesture.PinchUpdate -> if (gestureUse == GestureUse.PINCH) {
                // Fingers half as far apart = fully open; twice as far = fully closed again.
                pinchProgress = if (pinchStartedOpen) 1 - (gesture.scale - 1) / 1.0 else (1 - gesture.scale) / 0.5
                running.desktop.setOverviewProgress(pinchProgress)
            }
            is Gesture.PinchEnd -> if (gestureUse == GestureUse.PINCH) {
                running.desktop.endOverviewGesture(open = if (gesture.cancelled) pinchStartedOpen else pinchProgress > 0.5)
            }
            else -> {}
        }
        if (gestureUse == GestureUse.PROGRAM || gesture is Gesture.HoldBegin || gesture is Gesture.HoldEnd) running.pointerGestures.send(gesture)
        if (gesture is Gesture.SwipeEnd || gesture is Gesture.PinchEnd) gestureUse = GestureUse.NONE
    }

    // The main direction of a finished swipe, if it went far enough to mean something.
    private fun swipeDirection(): Direction? {
        val horizontal = Math.abs(swipeX) >= Math.abs(swipeY)
        val distance = if (horizontal) Math.abs(swipeX) else Math.abs(swipeY)
        if (distance < running.config.swipeDistance) return null
        return when {
            horizontal && swipeX < 0 -> Direction.LEFT
            horizontal -> Direction.RIGHT
            swipeY < 0 -> Direction.UP
            else -> Direction.DOWN
        }
    }
}
