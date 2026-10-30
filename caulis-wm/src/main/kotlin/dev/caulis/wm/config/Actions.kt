package dev.caulis.wm.config

import dev.caulis.core.NoticeLevel
import dev.caulis.core.Window
import dev.caulis.core.appearance.ColorScheme
import dev.caulis.tiling.Direction
import dev.caulis.tiling.TilingCommand
import dev.caulis.wm.Caulis
import dev.caulis.wm.DisplayMode

/**
 * Things to do, written as text — for keys in a configuration file, and for other programs
 * (`caulisctl`, scripts) — the same everywhere:
 *
 * ```
 * close · quit · lock · terminal · overview · next-window · dump-state
 * exec firefox --private-window          (a program; the rest of the line is its command)
 * workspace 3 · move-to-workspace 3 · move-to-workspace special:music silent
 * special-workspace toggle [name] · move-to-special [name] [silent] · move-out-of-special
 * focus left|right · move left|right|up|down · resize ... · preselect ...
 * maximize · fullscreen · minimize · float · pin · tag +video
 * rotate · balance · column-width · split-beside · split-below
 * layout dwindle · layout-message ratio +0.05 · scale 1.5 · notify Hello
 * color-scheme dark|light|toggle|auto
 * ```
 */
object Actions {

    /** Every action, its argument and what it does (for the documentation and messages). */
    val all: List<ActionInfo> get() = builtIn + providers.flatMap { provider -> provider.actions.map(::info) } + own.values

    // (A provider compiled against Caulis 0.1 lists its actions as Triples.)
    private fun info(item: Any?): ActionInfo = when (item) {
        is ActionInfo -> item
        is Triple<*, *, *> -> ActionInfo(item.first.toString(), item.second.toString(), item.third.toString())
        else -> ActionInfo(item.toString(), "", "")
    }

    /** An action of a compositor's own ([Caulis.action]): what it is, and what it does. */
    internal class Own(val name: String, val argument: String, val description: String, val run: Caulis.(String, Window?) -> Unit)

    // The names of actions compositors added (Caulis.action), so text naming them is read right.
    private val own = java.util.concurrent.ConcurrentHashMap<String, ActionInfo>()

    // Makes [name] an action text may name (its work is the configuration's, see Caulis.action).
    internal fun addOwn(name: String, argument: String, description: String) {
        require(name.isNotBlank() && name.none { it.isWhitespace() || it.isUpperCase() }) { "an action's name is one word in small letters (\"toggle-bar\"), not \"$name\"" }
        require(builtIn.none { it.name == name }) { "\"$name\" is an action of Caulis's own already" }
        own[name] = ActionInfo(name, argument, description)
        dev.caulis.log.Logger("config").trace { "Action of your own: $name" }
    }

    // Actions of modules on the classpath (caulis-screenshot's "screenshot", ...).
    private val providers: List<ActionProvider> by lazy {
        java.util.ServiceLoader.load(ActionProvider::class.java, Actions::class.java.classLoader).toList()
    }

    private val builtIn: List<ActionInfo> = listOf(
        ActionInfo("close", "", "closes the focused window"),
        ActionInfo("quit", "", "quits the compositor"),
        ActionInfo("lock", "", "locks the screen"),
        ActionInfo("xwayland-retry", "", "tries Xwayland again after it failed to start too often"),
        ActionInfo("terminal", "", "opens the terminal"),
        ActionInfo("exec", "command", "starts a program"),
        ActionInfo("overview", "", "opens or closes the overview"),
        ActionInfo("next-window", "", "focuses the next window"),
        ActionInfo("dump-state", "", "writes the state to the log"),
        ActionInfo("autostart", "", "lists the programs started with the session and how each is (a notice, and the log)"),
        ActionInfo("play-startup-sound", "[preset]", "plays the startup sound now (or one of the presets: airy, classic, glass, soft) — to try it"),
        ActionInfo("workspace", "number|next|prev|back|empty", "shows a workspace (back: the one before; empty: the first empty one)"),
        ActionInfo("move-to-workspace", "number|next|prev|back|empty|special[:name] [silent]", "moves the focused window to a workspace (special:name: into a special workspace, as move-to-special)"),
        ActionInfo("special-workspace", "toggle|show|hide [name]", "shows the special workspace (\"special\" without a name) over the current one, or hides it"),
        ActionInfo("move-to-special", "[name] [silent]", "moves the focused window into a special workspace, shown with it (silent: it waits there unseen)"),
        ActionInfo("move-out-of-special", "", "moves the focused window out of its special workspace onto the workspace on screen"),
        ActionInfo("focus-monitor", "left|right|up|down|next|primary|connector", "gives the focus to another monitor"),
        ActionInfo("move-to-monitor", "left|right|up|down|next|primary|connector", "moves the focused window to another monitor"),
        ActionInfo("focus", "left|right", "focuses the window beside"),
        ActionInfo("move", "left|right|up|down", "moves the window in the layout (or to the monitor beside)"),
        ActionInfo("resize", "left|right|up|down", "resizes the window in the layout"),
        ActionInfo("preselect", "left|right|up|down", "where the next window goes / moves the canvas"),
        ActionInfo("maximize", "", "maximizes the focused window, or back"),
        ActionInfo("fullscreen", "", "fullscreens the focused window, or back"),
        ActionInfo("minimize", "", "minimizes the focused window"),
        ActionInfo("float", "", "takes the focused window out of the layout, or back"),
        ActionInfo("pin", "[on|off|toggle]", "pins the focused window: it floats on every workspace (toggles without an argument)"),
        ActionInfo("tag", "+name|-name|name", "gives the focused window a tag (+), takes it away (-), or toggles it, for rules with hasTag"),
        ActionInfo("rotate", "", "rotates the split (trees)"),
        ActionInfo("balance", "", "evens out the sizes"),
        ActionInfo("column-width", "", "the next column width (scrolling columns)"),
        ActionInfo("split-beside", "", "the next window beside the focused one (manual tiling)"),
        ActionInfo("split-below", "", "the next window below the focused one (manual tiling)"),
        ActionInfo("layout", "name", "switches the layout"),
        ActionInfo("layout-message", "text", "tells the layout something (\"ratio +0.05\", \"masters +1\", \"toggle-split\", \"width 0.5\", ...)"),
        ActionInfo("scale", "number", "sets the scale"),
        ActionInfo("notify", "text", "shows a notice"),
        ActionInfo("focus-urgent", "", "focuses the window that wants attention"),
        ActionInfo("dpms", "on|off|toggle [monitor]", "switches the screens (or one) off or on"),
        ActionInfo("keep-awake", "on|off|toggle", "no idle timers while on"),
        ActionInfo("keyboard-layout", "next|prev|number", "the next keyboard layout (of keyboardLayouts), the one before, or one by its number (0: the first)"),
        ActionInfo("mode", "name|reset", "turns a key mode on (\"mode resize\"), or back to the usual keys"),
        ActionInfo("volume", "+5|-5|mute", "louder, quieter, or sound off and on (wpctl), shown on screen"),
        ActionInfo("mic-mute", "", "the microphone off or on"),
        ActionInfo("brightness", "+5|-5", "the screen brighter or darker"),
        ActionInfo("touchpad", "on|off|toggle | typing on|off|toggle", "the touchpad on or off; typing: off while typing (a palm on it moves nothing), or not"),
        ActionInfo("media", "play-pause|next|previous|stop", "the music player (playerctl)"),
        ActionInfo("displays", "next|extend|mirror|laptop|external", "how the laptop's screen and a monitor or projector are used together"),
        ActionInfo("color-scheme", "dark|light|default|toggle|auto", "light or dark for programs (until the schedule's next change); auto: back to the schedule"),
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
        fun onOffToggle(): (Boolean) -> Boolean = when (needs("on, off or toggle").lowercase()) {
            "on" -> ({ true })
            "off" -> ({ false })
            "toggle" -> ({ !it })
            else -> throw IllegalArgumentException("\"$argument\" is not on, off or toggle")
        }
        fun number(): Int = needs("a number").toIntOrNull()?.takeIf { it in 1..99 } ?: throw IllegalArgumentException("\"$argument\" is not a workspace number (1 to 99)")
        // A workspace: a number, or next, prev, back, empty (worked out when it runs).
        fun workspaceTarget(): Caulis.() -> Int? {
            val word = needs("a workspace").lowercase()
            word.toIntOrNull()?.let { n -> if (n !in 1..99) throw IllegalArgumentException("\"$argument\" is not a workspace number (1 to 99)"); return { n } }
            return when (word) {
                "next" -> ({ desktop.workspaceAfter(1) })
                "prev", "previous" -> ({ desktop.workspaceAfter(-1) })
                "back" -> ({ desktop.previousWorkspaceNumber })
                "empty" -> ({ desktop.firstEmptyWorkspace() })
                else -> throw IllegalArgumentException("\"$argument\" is not a workspace (a number, next, prev, back or empty)")
            }
        }
        // A monitor: a direction from the focused one, next, primary, or a connector.
        fun monitorTarget(): Caulis.() -> dev.caulis.core.Monitor? {
            val word = needs("a monitor")
            return when (word.lowercase()) {
                "left" -> ({ desktop.monitorBeside(-1, 0) })
                "right" -> ({ desktop.monitorBeside(1, 0) })
                "up" -> ({ desktop.monitorBeside(0, -1) })
                "down" -> ({ desktop.monitorBeside(0, 1) })
                "next" -> ({ desktop.monitors.let { all -> all[(all.indexOf(desktop.focusedMonitor) + 1) % all.size] } })
                "primary" -> ({ desktop.primaryMonitor })
                else -> ({ desktop.monitors.firstOrNull { it.name.equals(word, ignoreCase = true) } })
            }
        }
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
            "xwayland-retry" -> { _ -> liveOrNull()?.retryXwayland() }
            "terminal" -> { _ -> openTerminal() }
            "exec" -> needs("a command").let { command -> { _ -> launch(command) } }
            "overview" -> { _ -> desktop.toggleOverview() }
            "next-window" -> { _ -> desktop.cycleFocus() }
            "dump-state" -> { _ -> dumpState() }
            "autostart" -> { _ ->
                // (In the log, and as a notice.)
                val lines = autostartStatus()
                val text = if (lines.isEmpty()) "No program started with the session" else lines.joinToString("\n")
                dev.caulis.log.Logger("autostart").info { "The programs of the start:\n$text" }
                notify(text, NoticeLevel.INFO, "Autostart")
            }
            "play-startup-sound" -> {
                val preset = argument.lowercase().ifEmpty { null }
                if (preset != null && preset !in dev.caulis.wm.StartupChimes.PRESETS) {
                    throw IllegalArgumentException("\"$preset\" is not a startup sound (there are ${dev.caulis.wm.StartupChimes.PRESETS.joinToString()})")
                }
                { _ -> playStartupSound(preset) }
            }
            "workspace" -> workspaceTarget().let { target -> { _ -> target()?.let(desktop::switchTo) } }
            "move-to-workspace" -> if (argument.lowercase().startsWith("special")) {
                // Hyprland's "movetoworkspace special:name" (and "... silent").
                val words = argument.split(Regex("\\s+"))
                val silent = words.drop(1).let { rest -> rest == listOf("silent") || (rest.isNotEmpty() && throw IllegalArgumentException("\"${rest.joinToString(" ")}\" is not silent")) }
                val special = words[0].substringAfter(':', "").ifEmpty { dev.caulis.core.SpecialWorkspaces.DEFAULT_NAME }
                window { w -> desktop.moveToSpecialWorkspace(w, special, silent) }
            } else workspaceTarget().let { target -> window { w -> target()?.let { desktop.moveToWorkspace(w, it) } } }
            "special-workspace" -> {
                val words = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val how = words.getOrNull(0)?.lowercase() ?: "toggle"
                if (words.size > 2) throw IllegalArgumentException("\"$argument\" is more than toggle|show|hide [name]")
                val special = words.getOrNull(1) ?: dev.caulis.core.SpecialWorkspaces.DEFAULT_NAME
                when (how) {
                    "toggle" -> { _ -> desktop.toggleSpecialWorkspace(special) }
                    "show" -> { _ -> desktop.showSpecialWorkspace(special) }
                    "hide" -> { _ -> desktop.hideSpecialWorkspace(special) }
                    else -> throw IllegalArgumentException("\"$how\" is not toggle, show or hide")
                }
            }
            "move-to-special" -> {
                val words = argument.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val silent = words.lastOrNull()?.lowercase() == "silent"
                val names = if (silent) words.dropLast(1) else words
                if (names.size > 1) throw IllegalArgumentException("\"$argument\" is more than [name] [silent]")
                val special = names.firstOrNull() ?: dev.caulis.core.SpecialWorkspaces.DEFAULT_NAME
                window { w -> desktop.moveToSpecialWorkspace(w, special, silent) }
            }
            "move-out-of-special" -> window { w -> desktop.moveOutOfSpecialWorkspace(w) }
            "focus-monitor" -> monitorTarget().let { target -> { _ -> target()?.let(desktop::focusMonitor) } }
            "move-to-monitor" -> monitorTarget().let { target -> window { w -> target()?.let { desktop.moveToMonitor(w, it) } } }
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
            "pin" -> {
                val change: (Boolean) -> Boolean = if (argument.isEmpty()) ({ !it }) else onOffToggle()
                window { desktop.setPinned(it, change(desktop.isPinned(it))) }
            }
            "tag" -> {
                val word = needs("a tag")
                val tag = word.removePrefix("+").removePrefix("-")
                if (tag.isEmpty() || tag.any { it.isWhitespace() }) throw IllegalArgumentException("\"$word\" is not a tag (one word, with + or - before it to give or take it)")
                val on = when (word.first()) { '+' -> true; '-' -> false; else -> null }
                window { w -> running?.tag(w, tag, on) }
            }
            "rotate" -> focused { desktop.tilingCommand(TilingCommand.Rotate) }
            "balance" -> focused { desktop.tilingCommand(TilingCommand.Balance) }
            "column-width" -> focused { desktop.tilingCommand(TilingCommand.CycleWidth) }
            "split-beside" -> focused { desktop.tilingCommand(TilingCommand.SplitNext(sideBySide = true)) }
            "split-below" -> focused { desktop.tilingCommand(TilingCommand.SplitNext(sideBySide = false)) }
            "layout" -> Layouts.byName(needs("a layout name")).let { make -> { _ -> layout = make() } }
            "layout-message" -> dev.caulis.tiling.TilingCommand.Message.of(needs("a message")).let { message -> focused { desktop.tilingCommand(message) } }
            "scale" -> (needs("a number").toDoubleOrNull()?.takeIf { it in 0.5..4.0 } ?: throw IllegalArgumentException("\"$argument\" is not a scale (0.5 to 4)"))
                .let { s -> { _ -> scale = s } }
            "notify" -> needs("a text").let { message -> { _ -> notify(message, NoticeLevel.INFO) } }
            "keyboard-layout" -> {
                val which = needs("next, prev or a number").lowercase()
                when {
                    which == "next" -> { _ -> switchKeyboardLayout(keyboardLayoutNow + 1) }
                    which == "prev" -> { _ -> switchKeyboardLayout(keyboardLayoutNow - 1) }
                    which.toIntOrNull() != null -> { _ -> switchKeyboardLayout(which.toInt()) }
                    else -> throw IllegalArgumentException("\"$which\" is not next, prev or a number")
                }
            }
            "dpms" -> {
                val words = needs("on, off or toggle").split(Regex("\\s+"))
                val name = words.getOrNull(1)
                when (words[0].lowercase()) {
                    "on" -> { _ -> running?.setScreens(true, name) }
                    "off" -> { _ -> running?.setScreens(false, name) }
                    "toggle" -> { _ -> running?.let { r -> r.setScreens(desktop.monitors.none { !it.powered }.not(), name) } }
                    else -> throw IllegalArgumentException("\"${words[0]}\" is not on, off or toggle")
                }
            }
            "focus-urgent" -> { _ -> desktop.urgentWindows.firstOrNull()?.let(desktop::focus) }
            "keep-awake" -> onOffToggle().let { change -> { _ -> running?.idle?.let { it.inhibitedByUser = change(it.inhibitedByUser) } } }
            "volume" -> needs("+5, -5 or mute").let { word ->
                if (word == "mute") { _ -> running?.system?.volume(mute = true) }
                else (word.removePrefix("+").toIntOrNull() ?: throw IllegalArgumentException("\"$word\" is not +5, -5 or mute")).let { step -> { _ -> running?.system?.volume(step) } }
            }
            "mic-mute" -> { _ -> running?.system?.microphone() }
            "brightness" -> (needs("+5 or -5").removePrefix("+").toIntOrNull() ?: throw IllegalArgumentException("\"$argument\" is not +5 or -5"))
                .let { step -> { _ -> running?.system?.brightness(step) } }
            "touchpad" -> if (argument.startsWith("typing")) when (argument.removePrefix("typing").trim().ifEmpty { "toggle" }) {
                "on" -> { _ -> running?.system?.touchpadWhileTyping(true) }
                "off" -> { _ -> running?.system?.touchpadWhileTyping(false) }
                "toggle" -> { _ -> running?.system?.touchpadWhileTyping(null) }
                else -> throw IllegalArgumentException("\"$argument\" is not typing on, off or toggle")
            } else when (argument.ifEmpty { "toggle" }) {
                "on" -> { _ -> running?.system?.touchpad(true) }
                "off" -> { _ -> running?.system?.touchpad(false) }
                "toggle" -> { _ -> running?.system?.touchpad(null) }
                else -> throw IllegalArgumentException("\"$argument\" is not on, off or toggle")
            }
            "media" -> {
                val command = needs("play-pause, next, previous or stop")
                require(command in setOf("play-pause", "next", "previous", "stop")) { "\"$command\" is not play-pause, next, previous or stop" }
                val action: Caulis.(Window?) -> Unit = { _ -> running?.system?.media(command) }
                action
            }
            "displays" -> when (val word = argument.ifEmpty { "next" }) {
                "next" -> { _ -> running?.displays?.next() }
                else -> (DisplayMode.of(word) ?: throw IllegalArgumentException("\"$word\" is not next, extend, mirror, laptop or external"))
                    .let { mode -> { _ -> useDisplays(mode) } }
            }
            "color-scheme" -> when (val word = argument.lowercase().ifEmpty { "toggle" }) {
                "dark" -> ({ _ -> switchColorScheme(ColorScheme.DARK) })
                "light" -> ({ _ -> switchColorScheme(ColorScheme.LIGHT) })
                "default" -> ({ _ -> switchColorScheme(ColorScheme.DEFAULT) })
                "toggle" -> ({ _ -> switchColorScheme(colorSchemeNow.toggled()) })
                "auto" -> ({ _ -> switchColorScheme(null) })
                else -> throw IllegalArgumentException("\"$word\" is not dark, light, default, toggle or auto")
            }
            "mode" -> needs("a mode name, or reset").let { mode -> { _ -> enterKeyMode(mode.takeIf { it != "reset" && it != "default" }) } }
            else -> {
                for (provider in providers) provider.parse(name, argument)?.let { return it }
                // A compositor's own: run by the configuration of the moment.
                if (own.containsKey(name)) return { window ->
                    val action = ownActions[name] ?: throw IllegalStateException("the action \"$name\" is not in this configuration")
                    action.run(this, argument, window)
                }
                val near = all.map { it.name }.minByOrNull { distance(it, name) }?.takeIf { distance(it, name) <= 2 }
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

/**
 * More actions for text configurations (config.json, Lua, keys, caulisctl), from a module:
 * listed in `META-INF/services/dev.caulis.wm.config.ActionProvider`, found when it is on the
 * classpath (as caulis-screenshot adds "screenshot").
 */
interface ActionProvider {
    /** Its actions: name, argument and what it does. */
    val actions: List<ActionInfo>

    /**
     * What the action [name] with [argument] does, or null if it is not one of these. Throws
     * IllegalArgumentException with a plain message for a wrong argument.
     */
    fun parse(name: String, argument: String): (Caulis.(Window?) -> Unit)?
}

/**
 * One action as text knows it: its [name] ("workspace"), what [argument] it takes
 * ("number|next|prev", empty: none) and what it does ([description]).
 */
data class ActionInfo(val name: String, val argument: String, val description: String)
