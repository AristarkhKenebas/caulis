package dev.caulis.core

import dev.caulis.tiling.Floating
import dev.caulis.tiling.Layout
import dev.caulis.tiling.TilingModel

/**
 * One virtual desktop: its windows and how they are arranged ([layout]). Each monitor shows
 * one workspace; windows on the others keep running slowly (see [Desktop]).
 */
class Workspace internal constructor(
    /** 1, 2, 3, ... — what Super+1, Super+2, ... switch to; 0: a monitor's own screen ([WorkspaceMode.PRIMARY_ONLY]). */
    val number: Int,
    layout: Layout,
    /**
     * The monitor it belongs to when every monitor has workspaces of its own
     * ([WorkspaceMode.SPANNING], [WorkspaceMode.SEPARATE], a monitor's own screen with
     * [WorkspaceMode.PRIMARY_ONLY]); null when workspaces go wherever they are shown.
     */
    internal val homeMonitor: String? = null,
) {
    // The name of the monitor that showed it last (it goes back there when that monitor returns).
    internal var lastMonitorName: String? = null

    /** Its name from its rule ("web"), or null. */
    var name: String? = null
        internal set

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

    // (Changed only by the desktop: what others see is read-only.)
    internal val windowList = ArrayList<Window>()
    internal val minimizedList = ArrayList<Window>()

    /** Windows shown on this workspace, bottom to top (drawing order). */
    val windows: List<Window> get() = windowList

    /** Minimized windows of this workspace (hidden until brought back). */
    val minimized: List<Window> get() = minimizedList

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
        // Twice in the list it would be drawn, counted and listed twice: never (said, with
        // where it came from, so the cause can be found).
        if (window in windowList || window in minimizedList) {
            log.warn("$window was added to workspace $number again (ignored)", IllegalStateException("added again"))
            return
        }
        windowList += window
        tilingOrder += window
    }

    private companion object {
        val log = dev.caulis.log.Logger("windows")
    }

    internal fun remove(window: Window) {
        windowList -= window
        minimizedList -= window
        tilingOrder -= window
        floating -= window
    }

    override fun toString() = "Workspace($number${homeMonitor?.let { " on $it" } ?: ""}, ${layout.name}, ${windows.size} windows)"
}
