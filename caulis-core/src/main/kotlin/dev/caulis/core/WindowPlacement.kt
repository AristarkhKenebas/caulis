package dev.caulis.core

/**
 * Where a new window goes and how it starts, decided before its first frame (so it never
 * shows up somewhere else first): see [Desktop.placementFor]. Null means "as usual".
 * Sizes and positions are in desktop units; a position is from the top left corner of the
 * monitor's free area (inside panels).
 */
data class WindowPlacement @JvmOverloads constructor(
    /** The workspace it opens on (it does not switch there unless [goToWorkspace]). */
    val workspace: Int? = null,
    /** The monitor it opens on, by connector ("DP-2"); its shown workspace unless [workspace] is given. */
    val monitor: String? = null,
    /** True: it floats above the tiled windows; false: it is tiled (even a dialog). */
    val floating: Boolean? = null,
    /** Its size while floating. */
    val width: Int? = null,
    val height: Int? = null,
    /** Its place while floating, or [center] it on its monitor. */
    val x: Int? = null,
    val y: Int? = null,
    val center: Boolean = false,
    val maximized: Boolean? = null,
    val fullscreen: Boolean? = null,
    /** Show its workspace (and give it the keyboard) when it opens on another one. */
    val goToWorkspace: Boolean = false,
    /** False: it opens without taking the keyboard from the window that has it. */
    val focus: Boolean = true,
    /** True: pinned — it floats on whichever workspace its monitor shows (see [Desktop.setPinned]). */
    val pinned: Boolean? = null,
) {
    /** This placement with [other]'s settings on top (where [other] says something). */
    fun and(other: WindowPlacement) = WindowPlacement(
        workspace = other.workspace ?: workspace,
        monitor = other.monitor ?: monitor,
        floating = other.floating ?: floating,
        width = other.width ?: width,
        height = other.height ?: height,
        x = other.x ?: x,
        y = other.y ?: y,
        center = other.center || center,
        maximized = other.maximized ?: maximized,
        fullscreen = other.fullscreen ?: fullscreen,
        goToWorkspace = other.goToWorkspace || goToWorkspace,
        focus = other.focus && focus,
        pinned = other.pinned ?: pinned,
    )

    companion object {
        /** Everything as usual. */
        @JvmField val USUAL = WindowPlacement()
    }
}

/**
 * Extra space around one window inside its place in the layout, each side on its own, in
 * desktop units (see [Desktop.marginsFor]). Negative: the window reaches into the gap
 * beside it (e.g. a video touching the screen's edges) — never past its monitor's free area.
 */
data class Margins @JvmOverloads constructor(val top: Int = 0, val right: Int = 0, val bottom: Int = 0, val left: Int = 0) {
    init {
        for ((name, value) in listOf("top" to top, "right" to right, "bottom" to bottom, "left" to left)) {
            require(value in -MAX..MAX) { "the $name margin is -$MAX to $MAX, not $value" }
        }
    }

    companion object {
        /** The widest a margin can be. */
        const val MAX = 500

        /** The same on every side. */
        @JvmStatic fun all(size: Int) = Margins(size, size, size, size)
    }
}
