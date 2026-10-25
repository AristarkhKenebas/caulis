package dev.caulis.wm.config

import dev.caulis.core.NoticeLevel
import dev.caulis.core.Window
import dev.caulis.tiling.Direction
import dev.caulis.tiling.TilingCommand
import dev.caulis.wm.Caulis

/**
 * Things to do, written as text — for keys in a configuration file, and for other programs
 * (`caulisctl`, scripts) — the same everywhere:
 *
 * ```
 * close · quit · lock · terminal · overview · next-window · dump-state
 * exec firefox --private-window          (a program; the rest of the line is its command)
 * workspace 3 · move-to-workspace 3
 * focus left|right · move left|right|up|down · resize ... · preselect ...
 * maximize · fullscreen · minimize · float
 * rotate · balance · column-width · split-beside · split-below
 * layout dwindle · scale 1.5 · notify Hello
 * ```
 */
object Actions {

    /** Every action, its argument and what it does (for the documentation and messages). */
    val all: List<Triple<String, String, String>> = listOf(
        Triple("close", "", "closes the focused window"),
        Triple("quit", "", "quits the compositor"),
        Triple("lock", "", "locks the screen"),
        Triple("terminal", "", "opens the terminal"),
        Triple("exec", "command", "starts a program"),
        Triple("overview", "", "opens or closes the overview"),
        Triple("next-window", "", "focuses the next window"),
        Triple("dump-state", "", "writes the state to the log"),
        Triple("workspace", "number", "shows a workspace"),
        Triple("move-to-workspace", "number", "moves the focused window to a workspace"),
        Triple("focus", "left|right", "focuses the window beside"),
        Triple("move", "left|right|up|down", "moves the window in the layout (or to the monitor beside)"),
        Triple("resize", "left|right|up|down", "resizes the window in the layout"),
        Triple("preselect", "left|right|up|down", "where the next window goes / moves the canvas"),
        Triple("maximize", "", "maximizes the focused window, or back"),
        Triple("fullscreen", "", "fullscreens the focused window, or back"),
        Triple("minimize", "", "minimizes the focused window"),
        Triple("float", "", "takes the focused window out of the layout, or back"),
        Triple("rotate", "", "rotates the split (trees)"),
        Triple("balance", "", "evens out the sizes"),
        Triple("column-width", "", "the next column width (scrolling columns)"),
        Triple("split-beside", "", "the next window beside the focused one (manual tiling)"),
        Triple("split-below", "", "the next window below the focused one (manual tiling)"),
        Triple("layout", "name", "switches the layout"),
        Triple("scale", "number", "sets the scale"),
        Triple("notify", "text", "shows a notice"),
    )

    /**
     * What [text] does, ready to run on a [Caulis] (on the focused window); throws with a
     * plain message (and a guess) when it is not an action.
     */
    fun parse(text: String): Caulis.() -> Unit = parseFor(text).let { action -> { action(null) } }

    /**
     * What [text] does to a window: the one given, or the focused one (null). Moving in the
     * layout, resizing and focusing beside work from the focused window, so a window given
     * gets the focus first.
     */
    fun parseFor(text: String): Caulis.(Window?) -> Unit {
        val trimmed = text.trim()
        val name = trimmed.substringBefore(' ').lowercase().replace('_', '-')
        val argument = trimmed.substringAfter(' ', "").trim()
        fun needs(what: String): String = argument.ifEmpty { throw IllegalArgumentException("\"$name\" needs $what: \"$name <$what>\"") }
        fun direction(): Direction = when (needs("a direction").lowercase()) {
            "left" -> Direction.LEFT
            "right" -> Direction.RIGHT
            "up" -> Direction.UP
            "down" -> Direction.DOWN
            else -> throw IllegalArgumentException("\"$argument\" is not left, right, up or down")
        }
        fun number(): Int = needs("a number").toIntOrNull()?.takeIf { it in 1..99 } ?: throw IllegalArgumentException("\"$argument\" is not a workspace number (1 to 99)")
        // On the window given (or the focused one).
        fun window(action: Caulis.(Window) -> Unit): Caulis.(Window?) -> Unit = { given -> (given ?: focusedWindow)?.let { action(it) } }
        // From the focused window: the one given gets the focus first.
        fun focused(action: Caulis.() -> Unit): Caulis.(Window?) -> Unit = { given ->
            if (given != null && given !== focusedWindow) desktop.focus(given)
            action()
        }
        return when (name) {
            "close" -> window { it.close() }
            "quit" -> { _ -> quit() }
            "lock" -> { _ -> lock() }
            "terminal" -> { _ -> openTerminal() }
            "exec" -> needs("a command").let { command -> { _ -> launch(command) } }
            "overview" -> { _ -> desktop.toggleOverview() }
            "next-window" -> { _ -> desktop.cycleFocus() }
            "dump-state" -> { _ -> dumpState() }
            "workspace" -> number().let { n -> { _ -> desktop.switchTo(n) } }
            "move-to-workspace" -> number().let { n -> window { desktop.moveToWorkspace(it, n) } }
            "focus" -> direction().let { d ->
                focused { desktop.focusDirection(if (d == Direction.LEFT) -1 else if (d == Direction.RIGHT) 1 else 0, if (d == Direction.UP) -1 else if (d == Direction.DOWN) 1 else 0) }
            }
            "move" -> direction().let { d -> focused { desktop.tilingCommand(TilingCommand.Move(d)) || moveToMonitorBeside(d) } }
            "resize" -> direction().let { d -> focused { desktop.tilingCommand(TilingCommand.Resize(d)) } }
            "preselect" -> direction().let { d ->
                focused {
                    desktop.tilingCommand(TilingCommand.Preselect(d)) || desktop.tilingCommand(TilingCommand.Pan(d)) || desktop.tilingCommand(TilingCommand.Consume(d))
                }
            }
            "maximize" -> window { desktop.setMaximized(it, !it.maximized) }
            "fullscreen" -> window { desktop.setFullscreen(it, !it.fullscreen) }
            "minimize" -> window { desktop.minimize(it) }
            "float" -> window { desktop.setFloating(it, !desktop.isFloating(it)) }
            "rotate" -> focused { desktop.tilingCommand(TilingCommand.Rotate) }
            "balance" -> focused { desktop.tilingCommand(TilingCommand.Balance) }
            "column-width" -> focused { desktop.tilingCommand(TilingCommand.CycleWidth) }
            "split-beside" -> focused { desktop.tilingCommand(TilingCommand.SplitNext(sideBySide = true)) }
            "split-below" -> focused { desktop.tilingCommand(TilingCommand.SplitNext(sideBySide = false)) }
            "layout" -> Layouts.byName(needs("a layout name")).let { make -> { _ -> layout = make() } }
            "scale" -> (needs("a number").toDoubleOrNull()?.takeIf { it in 0.5..4.0 } ?: throw IllegalArgumentException("\"$argument\" is not a scale (0.5 to 4)"))
                .let { s -> { _ -> scale = s } }
            "notify" -> needs("a text").let { message -> { _ -> notify(message, NoticeLevel.INFO) } }
            else -> {
                val near = all.map { it.first }.minByOrNull { distance(it, name) }?.takeIf { distance(it, name) <= 2 }
                throw IllegalArgumentException("\"$name\" is not an action" + (near?.let { " (did you mean \"$it\"?)" } ?: ""))
            }
        }
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }
}
