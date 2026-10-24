package dev.caulis.core.input

/**
 * A touchpad gesture with several fingers: swipe (move), pinch (zoom/rotate) or hold
 * (fingers resting). Each one comes as Begin, any number of Updates, and End.
 * Times are in milliseconds.
 */
sealed interface Gesture {
    val timeMillis: Int

    data class SwipeBegin(override val timeMillis: Int, val fingers: Int) : Gesture
    /** The fingers moved by ([dx], [dy]) pixels since the last update. */
    data class SwipeUpdate(override val timeMillis: Int, val dx: Double, val dy: Double) : Gesture
    data class SwipeEnd(override val timeMillis: Int, val cancelled: Boolean) : Gesture

    data class PinchBegin(override val timeMillis: Int, val fingers: Int) : Gesture
    /**
     * [scale] is the distance between the fingers compared to the start (0.5 = half as far
     * apart, 2 = twice); [rotation] the turn in degrees since the last update.
     */
    data class PinchUpdate(override val timeMillis: Int, val dx: Double, val dy: Double, val scale: Double, val rotation: Double) : Gesture
    data class PinchEnd(override val timeMillis: Int, val cancelled: Boolean) : Gesture

    data class HoldBegin(override val timeMillis: Int, val fingers: Int) : Gesture
    data class HoldEnd(override val timeMillis: Int, val cancelled: Boolean) : Gesture
}
