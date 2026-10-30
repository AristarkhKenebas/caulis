package dev.caulis.core

import dev.caulis.render.GpuTarget

/**
 * A monitor's cursor plane: a small picture the display shows over everything by itself, at
 * a place it is told — moving the pointer then needs no new frame, only a new place. The
 * backend gives one where the display has it ([DesktopView.hardwareCursor]); the desktop
 * draws the cursor there when it fits, else into the frames as before.
 */
interface HardwareCursor {
    /** The plane's picture size in pixels (64, 128, 256, ...): a bigger cursor goes into the frames. */
    val width: Int
    val height: Int

    /**
     * Shows the cursor with its picture's top left corner at [x], [y] (the monitor's pixels;
     * outside it too). [draw] draws a new picture into the target it gets ([width] x [height]);
     * null: the same picture, only moved. Returns false if the display refuses it — the
     * cursor goes into the frames then.
     */
    fun show(x: Int, y: Int, draw: ((GpuTarget) -> Unit)?): Boolean

    /** No cursor on the plane (hidden, on another monitor, or in the frames). */
    fun hide()
}

/**
 * A cursor plane that can also be moved from another thread than the compositor's (the input
 * thread: Desktop.earlyPointer) — only its place, the picture it shows now. Returns false
 * when it cannot now (not shown, the monitor off, another terminal in front, refused): the
 * compositor's thread places it with the next frame as usual.
 */
internal interface MovableFromAnyThread {
    fun moveFromAnyThread(x: Int, y: Int): Boolean
}
