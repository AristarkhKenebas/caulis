package dev.caulis.core

import dev.caulis.tiling.Floating
import dev.caulis.tiling.Layout
import dev.caulis.tiling.TilingModel

/**
 * One virtual desktop: its windows and how they are arranged ([layout]). Each monitor shows
 * one workspace; windows on the others keep running slowly (see [Desktop]).
 */
class Workspace internal constructor(
    /** 1, 2, 3, ... — what Super+1, Super+2, ... switch to. */
    val number: Int,
    layout: Layout,
    /**
     * The monitor it belongs to when every monitor has its own part of each workspace
     * ([WorkspaceMode.SPANNING]); null when workspaces go wherever they are shown.
     */
    internal val homeMonitor: String? = null,
) {
    // The name of the monitor that showed it last (it goes back there when that monitor returns).
    internal var lastMonitorName: String? = null

    /** The monitor it is shown on now, or was last (its windows are placed in its area). */
    var monitor: Monitor? = null
        internal set
    /** How tiled windows are placed; change it with [Desktop.setLayout]. */
    var layout: Layout = layout
        internal set(value) {
            field = value
            model = value.createModel()
        }

    /** This workspace's own arrangement (layouts that remember things keep their state here). */
    var model: TilingModel = layout.createModel()
        private set

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

    override fun toString() = "Workspace($number${homeMonitor?.let { " on $it" } ?: ""}, ${layout.name}, ${windows.size} windows)"
}
