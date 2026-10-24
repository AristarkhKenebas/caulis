package dev.caulis.tiling

import dev.caulis.geometry.Rect

/**
 * The arrangement of one workspace, for layouts that remember things: a tree of splits
 * (bspwm, i3), columns (niri), a canvas (newm). Each workspace gets its own model from
 * [Layout.createModel].
 *
 * The model gets the full list of tiled windows on every [arrange] and brings itself up
 * to date (new windows are placed next to the focused one, gone ones are dropped), so it
 * can never lose a window. It must always show the focused window — scrolling or panning
 * to it if needed — which makes "activate this window" (from a dock, Alt+Tab, ...) work the
 * same with every layout.
 */
interface TilingModel {
    /** One rectangle per window in [windows] (tiling order), [focused] among them or null. */
    fun arrange(windows: List<LayoutWindow>, area: Rect, focused: LayoutWindow?): List<Rect>

    /** Carries out [command] for the [focused] window; true if the arrangement changed. */
    fun command(command: TilingCommand, focused: LayoutWindow?): Boolean = false

    /**
     * [window] was closed for good. Until this is called, a window missing from [arrange]
     * is only away (minimized, floating, on another workspace) and a model may keep its
     * place for it — e.g. a canvas keeps its cells, so it comes back exactly where it was.
     */
    fun forget(window: LayoutWindow) {}
}

/** A direction on screen. */
enum class Direction(val dx: Int, val dy: Int) {
    LEFT(-1, 0), RIGHT(1, 0), UP(0, -1), DOWN(0, 1);

    val horizontal: Boolean get() = dx != 0
    val opposite: Direction get() = entries.first { it.dx == -dx && it.dy == -dy }
}

/** What users can ask a remembering layout to do. Layouts ignore what makes no sense to them. */
sealed interface TilingCommand {
    /** Move the focused window that way (swap with its neighbour, move its column, its cell). */
    data class Move(val direction: Direction) : TilingCommand

    /** Make the focused window bigger towards [direction] (negative [amount] = smaller). */
    data class Resize(val direction: Direction, val amount: Double = 0.05) : TilingCommand

    /** The next window opens on that side of the focused one (again = cancel). Trees. */
    data class Preselect(val direction: Direction) : TilingCommand

    /** Split the next window this way: true = side by side, false = on top (i3 style). Trees. */
    data class SplitNext(val sideBySide: Boolean) : TilingCommand

    /** Turn the whole arrangement by 90 degrees. Trees. */
    object Rotate : TilingCommand

    /** Make all splits even again. Trees. */
    object Balance : TilingCommand

    /** Put the focused window into the neighbouring column, or out into its own. Columns. */
    data class Consume(val direction: Direction) : TilingCommand

    /** Next width of the focused column (1/3, 1/2, 2/3, full). Columns. */
    object CycleWidth : TilingCommand

    /** Move the view over the canvas by one cell. Canvas. */
    data class Pan(val direction: Direction) : TilingCommand

    /**
     * Move the view by about ([dx], [dy]) pixels, e.g. after the fingers dragged it; the
     * layout rounds it to what fits (whole cells on a canvas). Canvas, columns.
     */
    data class PanBy(val dx: Int, val dy: Int) : TilingCommand
}

/** A plain [Layout] needs no memory: the model just asks it every time. */
internal class StatelessModel(private val layout: Layout) : TilingModel {
    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: LayoutWindow?): List<Rect> =
        layout.arrange(windows, area, windows.indexOf(focused))
}

/**
 * Base for layouts that remember things: they only create models ([createModel]); asking
 * them directly gives a fresh arrangement each time (fine for previews and tests).
 */
abstract class RememberingLayout(override val name: String) : Layout {
    abstract override fun createModel(): TilingModel

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> =
        createModel().arrange(windows, area, windows.getOrNull(focused))
}
