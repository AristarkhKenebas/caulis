package dev.caulis.core

import dev.caulis.tiling.Floating
import dev.caulis.tiling.Layout

/**
 * One virtual desktop: its windows and how they are arranged ([layout]). Only the active
 * workspace is on screen; windows on the others keep running slowly (see [Desktop]).
 */
class Workspace internal constructor(
    /** 1, 2, 3, ... — what Super+1, Super+2, ... switch to. */
    val number: Int,
    layout: Layout,
) {
    /** How tiled windows are placed; change it with [Desktop.setLayout]. */
    var layout: Layout = layout
        internal set

    /** Windows shown on this workspace, bottom to top (drawing order). */
    val windows = ArrayList<Window>()

    /** Minimized windows of this workspace (hidden until brought back). */
    val minimized = ArrayList<Window>()

    // Oldest first: the order layouts place windows in (focus does not change it).
    internal val tilingOrder = ArrayList<Window>()

    // Windows the user (or a rule) took out of the layout: they float above the others.
    internal val floating = HashSet<Window>()

    val isEmpty: Boolean get() = windows.isEmpty() && minimized.isEmpty()

    /** Windows the layout places right now, in tiling order. */
    internal fun tiledWindows(): List<Window> =
        if (layout === Floating) {
            emptyList()
        } else {
            tilingOrder.filter { it in windows && it !in floating && !it.maximized && !it.fullscreen }
        }

    internal fun add(window: Window) {
        windows += window
        tilingOrder += window
    }

    internal fun remove(window: Window) {
        windows -= window
        minimized -= window
        tilingOrder -= window
        floating -= window
    }

    override fun toString() = "Workspace($number, ${layout.name}, ${windows.size} windows)"
}
