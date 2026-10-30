package dev.caulis.core.input

/**
 * Told of the pointer's movement on the input thread, before the compositor's thread handles
 * it (InputDevices.ownThread): the cursor can move at once even while the compositor's thread
 * is busy. [Desktop.earlyPointer][dev.caulis.core.Desktop.earlyPointer] is one: it moves the
 * cursor plane. All three are called by InputDevices; another backend reading input on a
 * thread of its own may call them the same way.
 */
interface EarlyPointer {
    /** On the input thread: the mouse or touchpad moved by [dx], [dy] (as onPointerMotion will be told). */
    fun movedEarly(dx: Double, dy: Double)

    /** On the input thread: what was read now is queued; the cursor may be shown where the pointer is now. */
    fun showEarly()

    /** On the compositor's thread, right after one movement was handled (in order). */
    fun caughtUp()
}
