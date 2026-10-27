package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import dev.caulis.tiling.Layout
import dev.caulis.tiling.Side
import kotlin.math.roundToInt

/**
 * A special workspace: windows that are shown and hidden with one key, above whatever
 * workspace is on screen (a drop-down terminal, a music player, a notes window, ...).
 * Create them with [Desktop.addScratchpad]; windows join when they match [match].
 */
class Scratchpad internal constructor(
    val name: String,
    val style: Style,
    /** Which windows belong here (e.g. by app id). */
    val match: (Window) -> Boolean,
    /**
     * True for a special workspace ([Desktop.toggleSpecialWorkspace]): made when first named,
     * shown even without windows, looking as [Desktop.specialWorkspaces] says.
     */
    val isSpecial: Boolean = false,
) {
    /** How a scratchpad looks when shown. */
    sealed interface Style

    /**
     * A workspace of its own above the current one, everything else dimmed by [dim]
     * (0..1) — like Hyprland's special workspace. Its windows use [layout].
     */
    data class Overlay(val layout: Layout = Dwindle(Gaps(inner = 12, outer = 48)), val dim: Float = 0.4f) : Style

    /**
     * A compact window that slides in from a screen edge — like a drop-down terminal.
     * [depth] is how far it reaches into the screen, [length] how much of the edge it
     * covers (centred), both as shares of the screen; [margin] keeps it off the edge.
     */
    data class Edge(val side: Side = Side.TOP, val depth: Double = 0.4, val length: Double = 0.6, val margin: Int = 0) : Style

    /** A floating window in the middle of the screen (like i3's scratchpad). */
    data class Center(val width: Double = 0.6, val height: Double = 0.6) : Style

    /** Its windows, oldest first. */
    val windows = ArrayList<Window>()

    /** True while it is on screen. */
    var shown = false
        internal set

    /** The monitor it shows on (the one with the focus when it was shown). */
    var monitor: Monitor? = null
        internal set

    // Its place and its hiding place on a monitor of [area] (desktop units).
    internal fun shownRect(area: Rect): Rect = shownRect(area.width, area.height).translate(area.x, area.y)

    internal fun hiddenRect(shown: Rect, area: Rect): Rect =
        hiddenRect(shown.translate(-area.x, -area.y), area.width, area.height).translate(area.x, area.y)

    // Hiding: still drawn while its windows glide away.
    internal var leaving = false

    // A special workspace: the window that had the focus before it was shown (it gets it back).
    internal var focusBefore: java.lang.ref.WeakReference<Window>? = null

    // For Overlay: its own arrangement.
    internal val workspace = Workspace(0, (style as? Overlay)?.layout ?: dev.caulis.tiling.Floating)

    /** Where a window of this scratchpad goes on a desktop of [width] x [height] when shown. */
    internal fun shownRect(width: Int, height: Int): Rect = when (style) {
        is Edge -> {
            val horizontalEdge = style.side == Side.TOP || style.side == Side.BOTTOM
            val along = ((if (horizontalEdge) width else height) * style.length).roundToInt()
            val deep = ((if (horizontalEdge) height else width) * style.depth).roundToInt()
            when (style.side) {
                Side.TOP -> Rect((width - along) / 2, style.margin, along, deep)
                Side.BOTTOM -> Rect((width - along) / 2, height - deep - style.margin, along, deep)
                Side.LEFT -> Rect(style.margin, (height - along) / 2, deep, along)
                Side.RIGHT -> Rect(width - deep - style.margin, (height - along) / 2, deep, along)
            }
        }
        is Center -> {
            val w = (width * style.width).roundToInt()
            val h = (height * style.height).roundToInt()
            Rect((width - w) / 2, (height - h) / 2, w, h)
        }
        is Overlay -> Rect(0, 0, width, height)
    }

    /** Where it comes from and goes to: beyond its edge, or a small point in the middle. */
    internal fun hiddenRect(shown: Rect, width: Int, height: Int): Rect = when (val s = style) {
        is Edge -> when (s.side) {
            Side.TOP -> shown.translate(0, -shown.bottom)
            Side.BOTTOM -> shown.translate(0, height - shown.y)
            Side.LEFT -> shown.translate(-shown.right, 0)
            Side.RIGHT -> shown.translate(width - shown.x, 0)
        }
        else -> Rect(shown.x + shown.width * 2 / 5, shown.y + shown.height * 2 / 5, shown.width / 5, shown.height / 5)
    }
}
