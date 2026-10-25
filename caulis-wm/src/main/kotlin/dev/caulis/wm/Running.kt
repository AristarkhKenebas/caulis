package dev.caulis.wm

import dev.caulis.log.Log
import dev.caulis.log.Logger
import dev.caulis.core.Compositor
import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.Effects
import dev.caulis.core.ForeignToplevels
import dev.caulis.core.ScreenCapture
import dev.caulis.core.ServerDecorations
import dev.caulis.xwayland.Xwayland
import dev.caulis.core.FrameStats
import dev.caulis.core.LayerShell
import dev.caulis.core.LinuxDmabuf
import dev.caulis.core.Monitor
import dev.caulis.core.NoticeLevel
import dev.caulis.core.WindowPlacement
import dev.caulis.core.Output
import dev.caulis.core.Presentation
import dev.caulis.core.SessionLockManager
import dev.caulis.core.Shm
import dev.caulis.core.XdgOutputManager
import dev.caulis.core.XdgShell
import dev.caulis.core.cursor.CursorTheme
import dev.caulis.core.input.CursorShape
import dev.caulis.core.input.Gesture
import dev.caulis.core.input.KeyBindings
import dev.caulis.core.input.Keymap
import dev.caulis.core.input.PointerGestures
import dev.caulis.core.input.Seat
import dev.caulis.event.EventLoop
import dev.caulis.protocol.Display
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer
import dev.caulis.tiling.Direction
import dev.caulis.tiling.RememberingLayout

/**
 * A running compositor, the same in every mode: the Wayland socket, the features offered
 * to programs, the window manager, keys and starting programs.
 */
internal class Running(
    initialConfig: Caulis,
    val loop: EventLoop,
    val renderer: Renderer,
    width: Int,
    height: Int,
    /** Null where switching text consoles is impossible (nested mode). */
    private val switchConsole: ((Int) -> Unit)?,
) : AutoCloseable {

    // The configuration: replaced by a new one when it is reloaded (see reconfigure). Everything
    // reads it through here, so the user's code of the moment is what runs.
    var config: Caulis = initialConfig
        private set

    private val log = Logger("config")
    private val clientsLog = Logger("clients")
    private val cursorLog = Logger("cursor")
    private val gesturesLog = Logger("gestures")

    // Lines of the log that become notices (from any thread; shown on ours). Watched from the
    // start, so mistakes found while starting (a key used twice) are shown too. Not lines
    // about notices themselves: showing one is logged too.
    private val logNotices: AutoCloseable = Log.watch { entry ->
        // (A broken rule is not logged: that line would come here again.)
        val wanted = entry.area != "notices" && try { config.noticesFromLog(entry) } catch (e: Exception) { false }
        if (wanted) {
            val level = when {
                entry.isError -> NoticeLevel.ERROR
                entry.isWarning -> NoticeLevel.WARNING
                else -> NoticeLevel.INFO
            }
            val text = entry.message + (entry.cause?.let { cause -> if (cause.toString() in entry.message) "" else " — $cause" } ?: "")
            loop.post {
                val notice = desktop.notify(text, level)
                if (entry.area == "config") configNotices += notice
            }
        }
    }

    // Notices about mistakes in the configuration: a reloaded one says anew what is still wrong.
    private val configNotices = ArrayList<dev.caulis.core.Notice>()

    val display = Display(loop)
    private val keymap = Keymap.fromNames(layouts = config.keyboardLayouts, options = config.keyboardOptions)
    val seat = Seat(display, keymap)
    private val compositor = Compositor(display, renderer)
    val desktop = Desktop(renderer, seat, compositor, width, height)
    private val pointerGestures = PointerGestures(display, seat)

    /** Socket name programs connect to, e.g. "wayland-1". */
    val socket: String

    val keys = KeyBindings()

    // The only keys of ours while the screen is locked; everything else goes to the locker.
    private val lockedKeys = KeyBindings()

    // Takes on changed code while running (null when off or not started from a project).
    private var reloader: Reloader? = null

    // Which rules (by what they say) each window got its placement from already: a rule is
    // applied once, when the window opens or first fits it.
    private val rulesApplied = java.util.WeakHashMap<dev.caulis.core.Window, MutableSet<String>>()

    // The title bars each window was last told (a rule may change them with its title).
    private val shownTitleBars = java.util.WeakHashMap<dev.caulis.core.Window, dev.caulis.core.TitleBars?>()

    private val rulesLog = Logger("windows")

    private fun fits(rule: WindowRule, window: dev.caulis.core.Window): Boolean = safely("$rule", false) { rule.fits(window) }

    // Who draws a window's title bar: the last rule that fits and says, then titleBarsFor.
    private fun titleBarsOf(window: dev.caulis.core.Window): dev.caulis.core.TitleBars? =
        config.rules.lastOrNull { it.titleBars != null && fits(it, window) }?.titleBars
            ?: config.titleBarRules.firstNotNullOfOrNull { rule -> safely("titleBarsFor", null) { rule(window) } }

    // The user's event listeners of the configuration of the moment (replaced on a reload).
    private val configListeners = ArrayList<AutoCloseable>()

    // Where a cursor theme that came with a library is put for programs that draw their own cursor.
    private var cursorFolder: java.nio.file.Path? = null

    // X11 programs (null if turned off). Declared before init, which sets it.
    private var xwayland: Xwayland? = null

    // Starts programs: Wayland first, X11 when that fails.
    private val launcher = Launcher(
        environment = { env ->
            env["WAYLAND_DISPLAY"] = socket
            env["XDG_CURRENT_DESKTOP"] = config.desktopName
            // X11 programs (started now or later by these) reach Xwayland here.
            xwayland?.let { env["DISPLAY"] = it.displayName } ?: env.remove("DISPLAY")
            // Programs that draw their own cursor use the same theme and size.
            val theme = desktop.cursorTheme
            env["XCURSOR_THEME"] = theme.name
            // On a scaled screen X11 programs need the cursor size in pixels and Wayland ones in
            // units: no XCURSOR_SIZE then (it would win over both); X11 programs get it from the
            // X resources, Wayland ones from the compositor (cursor-shape) or their own default.
            if (desktop.scale == 1.0) env["XCURSOR_SIZE"] = theme.size.toString() else env.remove("XCURSOR_SIZE")
            cursorFolder?.let { folder -> env["XCURSOR_PATH"] = (listOf(folder) + CursorTheme.searchPath()).joinToString(":") }
        },
        x11Available = { xwayland != null },
        post = loop::post,
        settings = { Launcher.Settings(config.waylandFirst, config.x11Programs.toSet(), desktop.largestScale, config.xwayland && config.sharpJavaScaling) },
    )

    init {
        Shm(display)
        if (renderer.renderNode != null) LinuxDmabuf(display, renderer)
        val clipboard = DataDeviceManager(display, seat)
        Presentation(display)
        XdgShell(display).listener = desktop
        LayerShell(display).listener = desktop
        SessionLockManager(display).listener = desktop
        CursorShape(display, seat)
        XdgOutputManager(display)
        ServerDecorations(display, desktop)
        xwayland = if (config.xwayland) safely("Xwayland", null) { Xwayland(display, desktop, loop, clipboard, wholeScaleWorlds = config.sharpJavaScaling) } else null
        ScreenCapture(display, desktop, renderer, ForeignToplevels(display, desktop))
        applyCursor()
        desktop.workspaceMode = config.workspaceMode // before the monitors come
        desktop.layout = config.layout
        socket = config.socketDirectory?.let(display::addSocket) ?: display.addSocket()

        desktop.scale = config.scale
        xwayland?.rescaled() // (made before the scale was set)

        // The user's rules (dialogs and fixed-size windows always float); they ask the
        // configuration of the moment, so they need no setting again after a reload.
        val defaultRule = desktop.floatRule
        desktop.windowEffects = { window ->
            val fx = config.effectRules.fold(desktop.effects) { fx, rule -> safely("effectsFor", fx) { rule(window, fx) } }
            config.rules.fold(fx) { now, rule -> if (fits(rule, window)) safely("$rule effects", now) { rule.effectsOf(now) } else now }
        }
        // Window rules: where windows open (decided before their first frame), and once more
        // for a window that fits a rule only after it changed its title or app id.
        desktop.placementFor = { window ->
            val fitting = config.rules.filter { it.hasPlacement && fits(it, window) }
            rulesApplied.getOrPut(window) { HashSet() } += fitting.map { it.signature }
            fitting.fold(WindowPlacement.USUAL) { placement, rule -> placement.and(rule.placement()) }.takeIf { fitting.isNotEmpty() }
        }
        desktop.onWindowInfoChanged { window ->
            val applied = rulesApplied.getOrPut(window) { HashSet() }
            for (rule in config.rules) {
                if (!rule.hasPlacement || rule.signature in applied || !fits(rule, window)) continue
                applied += rule.signature
                rulesLog.info { "$window fits $rule now" }
                safely("$rule") { desktop.applyPlacement(window, rule.placement()) }
            }
            // Its title bar may follow a rule that fits it now (or no longer).
            val bars = titleBarsOf(window)
            if (shownTitleBars.put(window, bars) != bars) desktop.titleBarsChanged()
        }
        desktop.onWindowOpened { window -> shownTitleBars[window] = titleBarsOf(window) }
        desktop.layerEffects = { layer ->
            config.panelEffectRules.firstNotNullOfOrNull { rule -> safely("panelEffects", null) { rule(layer) } } ?: Effects.NONE
        }
        desktop.floatRule = { window -> defaultRule(window) || config.floatRules.any { rule -> safely("floatWhen", false) { rule(window) } } }
        desktop.onWindowOpened { window -> launcher.windowOpened(window.pid) }
        applyConfig()
    }

    /** Hands the background rule over again (it was changed while running). */
    fun applyLooks() {
        desktop.backgroundFor = config.backgroundFor?.let { rule -> { monitor, workspace -> safely("backgroundFor", null) { rule(monitor.name, workspace) } } }
    }

    // What of the configuration is handed over to the desktop, keys and events — at the
    // start and again after each reload.
    private fun applyConfig() {
        val config = config
        desktop.effects = config.effects
        desktop.titleBars = config.titleBars
        desktop.titleBarStyle = config.titleBarStyle
        desktop.titleBarsFor = if (config.titleBarRules.isEmpty() && config.rules.none { it.titleBars != null }) null else ::titleBarsOf
        desktop.noticeStyle = config.noticeStyle
        desktop.animationMillis = config.animationMillis
        desktop.animations = config.animations
        desktop.background = config.background
        applyLooks()
        desktop.backgroundChangeMillis = config.backgroundChangeMillis
        desktop.overviewFill = config.overviewFill
        desktop.resizeBorder = config.resizeBorder
        desktop.doubleClickMillis = config.doubleClickMillis
        desktop.lockColor = config.lockColor

        keys.clear()
        lockedKeys.clear()
        bindKeys()
        for (pad in config.scratchpads) {
            // A scratchpad asks the configuration of the moment which windows are its own.
            if (desktop.scratchpad(pad.name) == null) {
                desktop.addScratchpad(pad.name, pad.style) { window ->
                    val now = this.config.scratchpads.firstOrNull { it.name == pad.name } ?: return@addScratchpad false
                    safely("scratchpad ${pad.name}", false) { now.match(window) }
                }
            }
            pad.key?.let { key -> keys.bind(key) { toggleScratchpad(pad.name) } }
        }

        configListeners.forEach(AutoCloseable::close)
        configListeners.clear()
        config.noticeListeners.forEach { action -> configListeners += desktop.onNotice { notice -> safely("onNotice") { action(config, notice) } } }
        config.windowOpened.forEach { action -> configListeners += desktop.onWindowOpened { window -> action(config, window) } }
        config.windowClosed.forEach { action -> configListeners += desktop.onWindowClosed { window -> action(config, window) } }
        config.focusChanged.forEach { action -> configListeners += desktop.onFocusChanged { window -> action(config, window) } }
        config.workspaceChanged.forEach { action -> configListeners += desktop.onWorkspaceChanged { workspace -> action(config, workspace.number) } }

        // Quitting and text consoles also work while the screen is locked (when they are on).
        val usual = config.keys
        for (bindings in listOf(keys, lockedKeys)) {
            usual.quit?.let { bindings.bind(it) { quit() } }
            if (switchConsole != null) usual.consoles?.let { modifiers -> for (n in 1..12) bindings.bind("$modifiers+F$n") { switchConsole.invoke(n) } }
        }
        if (usual.quit == null && (usual.consoles == null || switchConsole == null)) {
            log.warn("No key quits and none switches to a text console (keys.quit and keys.consoles are off): if a program takes the whole screen, only another computer (ssh) or the power button gets you out")
        }
    }

    // The usual keys first, the user's own after them (later ones win on the same combination).
    private fun bindKeys() {
        // Those you changed after the others: on the same keys, yours wins over a default one.
        val usual = config.keys.bindings().sortedBy { config.keys.changed(it.first) }
        val taken = HashMap<Pair<Set<KeyBindings.Modifier>, Int>, String>()
        for ((name, combination, action) in usual) {
            val parsed = KeyBindings.parse(combination)
            taken.put(parsed, name)?.takeIf { it != name }?.let { other ->
                log.warn("keys.$name and keys.$other are both $combination: keys.$name wins")
            }
            keys.bind(combination) { safely(combination) { action(config) } }
        }
        for ((combination, action) in config.bindings) {
            taken[KeyBindings.parse(combination)]?.let { name -> log.info { "bind(\"$combination\") replaces keys.$name" } }
            keys.bind(combination) { safely(combination) { action(config) } }
        }
    }

    fun quit() = loop.stop()

    /**
     * Runs with [next] from now on (the configuration was reloaded): its keys, rules, events,
     * looks and scale; windows and programs stay. Returns the settings that only change
     * after a restart, and notes on what was kept.
     */
    fun reconfigure(next: Caulis): Pair<List<String>, List<String>> {
        val before = config
        val notes = ArrayList<String>()
        // A layout that remembers the arrangement (trees, columns, a canvas) of the same kind
        // stays as it is: a new one would forget where your windows are.
        val shown = desktop.layout
        if (shown !== next.layout && shown is RememberingLayout && shown.javaClass == next.layout.javaClass && shown.name == next.layout.name) {
            next.layout = shown // (not running yet: only the setting)
            notes += "the ${shown.name} layout keeps your arrangement; its new settings apply when you switch layouts or restart"
        }
        val restartOnly = buildList {
            fun check(name: String, old: Any?, new: Any?) { if (old != new) add(name) }
            check("mode", before.mode, next.mode)
            check("xwayland", before.xwayland, next.xwayland)
            check("sharpJavaScaling", before.sharpJavaScaling, next.sharpJavaScaling)
            check("workspaceMode", before.workspaceMode, next.workspaceMode)
            check("nestedMonitors", before.nestedMonitors, next.nestedMonitors)
            check("keyboardLayouts", before.keyboardLayouts, next.keyboardLayouts)
            check("keyboardOptions", before.keyboardOptions, next.keyboardOptions)
            check("title", before.title, next.title)
            check("appId", before.appId, next.appId)
            check("socketDirectory", before.socketDirectory, next.socketDirectory)
            check("stats", before.stats, next.stats)
            for (pad in next.scratchpads) {
                val old = before.scratchpads.firstOrNull { it.name == pad.name } ?: continue
                check("the style of scratchpad ${pad.name}", old.style, pad.style)
            }
        }
        configNotices.forEach(desktop::dismiss)
        configNotices.clear()
        // New rules are for windows that open from now on (or fit them later), not for those
        // already open where they fit: those stay where you have them.
        for (window in desktop.allWindows) {
            rulesApplied.getOrPut(window) { HashSet() } += next.rules.filter { it.hasPlacement && safely("$it", false) { it.fits(window) } }.map { it.signature }
        }
        next.running = this
        before.running = null
        config = next
        next.applyLogSettings()
        applyConfig()
        if (desktop.layout != next.layout) desktop.layout = next.layout
        if (before.scale != next.scale || before.monitorSettings.values.map { it.scale } != next.monitorSettings.values.map { it.scale }) applyScale()
        applyMonitorSettings()
        applyCursor()
        next.reloadActions.forEach { action -> safely("onReload") { action(next) } }
        return restartOnly to notes
    }

    /** Applies the scale to the desktop, the monitors (their own where set) and the programs. */
    fun applyScale() {
        desktop.scale = config.scale
        for (monitor in desktop.monitors) config.monitorSettings[monitor.name]?.scale?.let { desktop.setScale(monitor, it) }
        xwayland?.rescaled()
        log.info { "Scale ${config.scale}${desktop.monitors.filter { it.scale != config.scale }.joinToString("") { ", ${it.name} ${it.scale}" }}" }
        applyCursor() // programs that draw their own cursor get the new size
    }

    /**
     * A monitor came (the backend's): it gets its settings (scale, place) and shows a
     * workspace from now on.
     */
    fun addMonitor(name: String, output: Output?, pixelWidth: Int, pixelHeight: Int): Monitor {
        val settings = config.monitorSettings[name]
        val x = settings?.x
        val y = settings?.y
        val monitor = desktop.addMonitor(name, output, pixelWidth, pixelHeight, settings?.scale ?: config.scale, x, y)
        xwayland?.rescaled() // the largest scale may be another now
        return monitor
    }

    /** A monitor went away (the backend's). */
    fun removeMonitor(monitor: Monitor) {
        desktop.removeMonitor(monitor)
        xwayland?.rescaled()
    }

    /** Applies changed monitor settings (scale and place) to the monitors there are. */
    fun applyMonitorSettings() {
        for (monitor in desktop.monitors) {
            val settings = config.monitorSettings[monitor.name] ?: continue
            settings.scale?.let { desktop.setScale(monitor, it) }
            desktop.moveMonitor(monitor, settings.x, settings.y)
        }
        xwayland?.rescaled()
    }

    /** Applies the cursor settings (theme, size, hiding). */
    fun applyCursor() {
        val theme = CursorTheme.load(config.cursorTheme, config.cursorSize)
        if (!theme.found) {
            cursorLog.warn("The cursor theme \"${theme.name}\" is not installed; using the system's. " +
                "Installed: ${CursorTheme.available().joinToString()}")
        }
        cursorLog.info { "Cursor theme ${theme.name}${if (theme.found) "" else " (not found)"}, ${theme.size} px" }
        desktop.cursorTheme = theme
        xwayland?.cursorChanged()
        desktop.hideCursorWhileTyping = config.hideCursorWhileTyping
        desktop.hideCursorAfterMillis = config.hideCursorAfterSeconds * 1000L
        cursorFolder = safely("cursor theme files", null) {
            // On disk, once (a theme is tens of megabytes; the runtime folder is in memory).
            val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.cache")
            val folder = java.nio.file.Path.of(cache, "caulis", "icons")
            if (theme.exportTo(folder)) folder else null
        }
    }

    // When each scratchpad's program was started (so a second press does not start it twice).
    private val startedAt = HashMap<String, Long>()

    /** Shows or hides a scratchpad; starts its program if it has no window yet. */
    fun toggleScratchpad(name: String) {
        if (desktop.toggleScratchpad(name)) return
        val command = config.scratchpads.firstOrNull { it.name == name }?.command ?: return
        val now = System.nanoTime()
        val last = startedAt[name]
        if (last != null && now - last < STARTING_NANOS) return // still starting
        startedAt[name] = now
        launch(command)
    }

    /**
     * Starts [command] (run by /bin/sh) as a program inside the compositor, the Wayland way
     * first (or through X11: [x11]). If the program does not exist, that is said in plain
     * words instead of nothing happening.
     */
    fun launch(command: String, x11: Boolean = false) = launcher.launch(command, x11)

    // Runs the user's code; a mistake in it is reported and the compositor keeps running.
    private fun <T> safely(what: String, fallback: T, action: () -> T): T = try {
        action()
    } catch (e: Exception) {
        log.error("Your code for \"$what\" failed (ignored): $e", e)
        fallback
    }

    private fun safely(what: String, action: () -> Unit) = safely(what, Unit, action)

    /** A key from the keyboard: the compositor's combination, or passed to the focused program. */
    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        val (active, other) = if (desktop.locked) lockedKeys to keys else keys to lockedKeys
        // A key pressed before locking may be released after it (Super+L): both must see the release.
        if (!pressed) other.key(evdevCode, false)
        if (!active.key(evdevCode, pressed)) desktop.key(timeMillis, evdevCode, pressed)
    }

    /**
     * A mouse button: Super + left button drags a window, Super + right button resizes it
     * (from the nearest corner). Everything else goes to the program under the pointer.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        if (pressed && !desktop.locked && dragging() && (button == BTN_LEFT || button == BTN_RIGHT)) {
            val window = desktop.windowAt(desktop.pointerX, desktop.pointerY)
            if (window != null) {
                desktop.pointerButton(timeMillis, button, true, passToApps = false)
                if (button == BTN_LEFT) {
                    desktop.beginMove(window)
                } else {
                    desktop.beginResize(window, desktop.nearestEdges(window, desktop.pointerX, desktop.pointerY))
                }
                return
            }
        }
        desktop.pointerButton(timeMillis, button, pressed)
    }

    // The modifiers for dragging windows are held (and no others).
    private fun dragging(): Boolean {
        val modifiers = config.keys.windowDrag ?: return false
        return keys.heldModifiers() == KeyBindings.modifiers(modifiers)
    }

    // --- touchpad gestures --------------------------------------------------------------------

    // What the gesture going on does: drag the view, open the overview, a swipe action, or
    // nothing of ours (then it goes to the program under the pointer).
    private enum class GestureUse { NONE, DRAG, SWIPE, PINCH, PROGRAM }

    private var gestureUse = GestureUse.NONE
    private var gestureFingers = 0
    private var swipeX = 0.0
    private var swipeY = 0.0
    private var pinchStartedOpen = false
    private var pinchProgress = 0.0

    private val swipes: List<Triple<Int, Direction, Caulis.() -> Unit>> get() = buildList {
        val fingers = config.workspaceSwipeFingers
        if (fingers > 0) {
            add(Triple(fingers, Direction.LEFT) { desktop.switchTo(desktop.activeWorkspace.number + 1) })
            add(Triple(fingers, Direction.RIGHT) { desktop.switchTo((desktop.activeWorkspace.number - 1).coerceAtLeast(1)) })
        }
        addAll(config.swipes) // the user's own win (the last one fits)
    }

    /** A touchpad gesture: the compositor's own (see [Caulis.dragViewFingers] ...) or the program's. */
    fun gesture(gesture: Gesture) = safely("gesture") {
        gesturesLog.trace { "$gesture (fingers ${gestureFingers}, use $gestureUse)" }
        when (gesture) {
            is Gesture.SwipeBegin -> {
                gestureFingers = gesture.fingers
                swipeX = 0.0
                swipeY = 0.0
                gestureUse = when {
                    desktop.locked -> GestureUse.PROGRAM // the locker's
                    gesture.fingers == config.dragViewFingers -> GestureUse.DRAG
                    swipes.any { it.first == gesture.fingers } -> GestureUse.SWIPE
                    else -> GestureUse.PROGRAM
                }
            }
            is Gesture.SwipeUpdate -> when (gestureUse) {
                GestureUse.DRAG -> desktop.dragView(gesture.dx, gesture.dy)
                GestureUse.SWIPE -> {
                    swipeX += gesture.dx
                    swipeY += gesture.dy
                }
                else -> {}
            }
            is Gesture.SwipeEnd -> when (gestureUse) {
                GestureUse.DRAG -> desktop.endViewDrag()
                GestureUse.SWIPE -> if (!gesture.cancelled) swipeDirection()?.let { direction ->
                    swipes.lastOrNull { it.first == gestureFingers && it.second == direction }?.let { (_, _, action) -> action(config) }
                }
                else -> {}
            }
            is Gesture.PinchBegin -> {
                gestureFingers = gesture.fingers
                gestureUse = if (gesture.fingers == config.overviewPinchFingers && !desktop.locked) GestureUse.PINCH else GestureUse.PROGRAM
                pinchStartedOpen = desktop.inOverview
                pinchProgress = if (pinchStartedOpen) 1.0 else 0.0
            }
            is Gesture.PinchUpdate -> if (gestureUse == GestureUse.PINCH) {
                // Fingers half as far apart = fully open; twice as far = fully closed again.
                pinchProgress = if (pinchStartedOpen) 1 - (gesture.scale - 1) / 1.0 else (1 - gesture.scale) / 0.5
                desktop.setOverviewProgress(pinchProgress)
            }
            is Gesture.PinchEnd -> if (gestureUse == GestureUse.PINCH) {
                desktop.endOverviewGesture(open = if (gesture.cancelled) pinchStartedOpen else pinchProgress > 0.5)
            }
            else -> {}
        }
        if (gestureUse == GestureUse.PROGRAM || gesture is Gesture.HoldBegin || gesture is Gesture.HoldEnd) pointerGestures.send(gesture)
        if (gesture is Gesture.SwipeEnd || gesture is Gesture.PinchEnd) gestureUse = GestureUse.NONE
    }

    // The main direction of a finished swipe, if it went far enough to mean something.
    private fun swipeDirection(): Direction? {
        val horizontal = Math.abs(swipeX) >= Math.abs(swipeY)
        val distance = if (horizontal) Math.abs(swipeX) else Math.abs(swipeY)
        if (distance < config.swipeDistance) return null
        return when {
            horizontal && swipeX < 0 -> Direction.LEFT
            horizontal -> Direction.RIGHT
            swipeY < 0 -> Direction.UP
            else -> Direction.DOWN
        }
    }

    /** Wraps a backend's `present` so drawing is timed by [stats] (if measuring is on). */
    fun timed(stats: FrameStats?, present: ((GpuTarget, Int) -> Unit) -> Unit): ((GpuTarget, Int) -> Unit) -> Unit {
        if (stats == null) return present
        return { draw ->
            present { target, age ->
                stats.drawStarted()
                draw(target, age)
                stats.drawFinished()
            }
        }
    }

    /** Runs the user's start actions and the main loop until [quit]. */
    fun runLoop() {
        config.running = this
        try {
            config.exitAfterSeconds?.let { seconds -> loop.after(seconds * 1000) { quit() } }
            for (problem in config.startWarnings) desktop.notify("${problem.message} ${problem.fix}", NoticeLevel.WARNING)
            config.startActions.forEach { action -> safely("onStart") { action(config) } }
            if (config.hotReload) reloader = safely("hot reload", null) { Reloader.start(this) }
            log.info { "Your files (shaders, pictures): ${config.configFolder}${if (java.nio.file.Files.isDirectory(config.configFolder)) "" else " (not there yet)"}" }
            loop.run()
        } finally {
            config.running = null
        }
    }

    override fun close() {
        reloader?.close()
        logNotices.close()
        xwayland?.close()
        seat.close()
        keymap.close()
        display.close()
    }

    private companion object {

        /** How long a scratchpad's program may take to show a window before a key press starts it again. */
        const val STARTING_NANOS = 5_000_000_000L
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
    }
}

/** Milliseconds on the clock Wayland and libinput timestamps use. */
internal fun now(): Int = (System.nanoTime() / 1_000_000).toInt()
