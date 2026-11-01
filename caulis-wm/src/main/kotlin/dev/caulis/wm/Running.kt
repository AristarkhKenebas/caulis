package dev.caulis.wm

import dev.caulis.log.Log
import dev.caulis.log.Logger
import dev.caulis.core.Compositor
import dev.caulis.core.rethrowIfFatal
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
import dev.caulis.core.NoticeLevel
import dev.caulis.core.WindowPlacement
import dev.caulis.core.Presentation
import dev.caulis.core.SessionLockManager
import dev.caulis.core.Shm
import dev.caulis.core.XdgOutputManager
import dev.caulis.core.XdgShell
import dev.caulis.core.input.CursorShape
import dev.caulis.core.input.KeyBindings
import dev.caulis.core.input.Keymap
import dev.caulis.core.input.PointerGestures
import dev.caulis.core.input.PenPointer
import dev.caulis.core.input.Seat
import dev.caulis.core.input.Tablets
import dev.caulis.event.EventLoop
import dev.caulis.protocol.Display
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer

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
    internal val switchConsole: ((Int) -> Unit)?,
) : AutoCloseable {

    // The configuration: replaced by a new one when it is reloaded (see reconfigure). Everything
    // reads it through here, so the user's code of the moment is what runs.
    var config: Caulis = initialConfig
        internal set

    /** False once it was closed (a configuration a reload replaced no longer reaches it then). */
    @Volatile
    var isOpen = true
        private set

    /** True while the configuration's onStart runs: what it starts is the session's (see Caulis.onStart). */
    var startingUp = false
        private set

    /** Runs [body] so that what it adds stays through reloads, as in onStart (plugins). */
    internal fun <T> asSession(body: () -> T): T {
        val before = startingUp
        startingUp = true
        try {
            return body()
        } finally {
            startingUp = before
        }
    }

    /** The plugins. */
    internal val rplugins = RunningPlugins(this)

    /** Widgets, drawings and the like the session keeps (from onStart): closed when the compositor ends. */
    val sessionResources = ArrayList<AutoCloseable>()

    // The buses as the session uses them (onStart, replaced configurations): never taken away.
    private val sessionScopes = HashMap<Boolean, dev.caulis.core.dbus.DBusScope>()

    /** The session's view of the session bus (true) or the system bus (false), or null if there is none. */
    fun sessionScope(session: Boolean): dev.caulis.core.dbus.DBusScope? = sessionScopes[session] ?: run {
        val connection = (if (session) sessionBus else systemBus) ?: return null
        dev.caulis.core.dbus.DBusScope(connection).also { sessionScopes[session] = it }
    }

    internal val log = Logger("config")
    internal val keyboardLog = Logger("keyboard")
    private val clientsLog = Logger("clients")

    // Screen pictures (made with the display; their permission questions answered through it).
    private var capture: ScreenCapture? = null

    // The permission rules at work, and the person's answers.
    internal val permissions = PermissionKeeper({ config }, { capture?.answered() }, { client -> display.privileged(client) })
    internal val cursorLog = Logger("cursor")
    internal val gesturesLog = Logger("gestures")
    internal val inputLog = Logger("input")

    // Lines of the log that become notices (from any thread; shown on ours). Watched from the
    // start, so mistakes found while starting (a key used twice) are shown too. Not lines
    // about notices themselves: showing one is logged too.
    private val logNotices: AutoCloseable = Log.watch { entry ->
        if (entry.area == "notices") return@watch // (that line would come here again)
        // Lines come from any thread; your rule and the notice are the compositor's thread's.
        loop.post {
            val wanted = try {
                config.noticesFromLog(entry)
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                false // (a broken rule is not logged: that line would come here again)
            }
            if (!wanted) return@post
            val level = when {
                entry.isError -> NoticeLevel.ERROR
                entry.isWarning -> NoticeLevel.WARNING
                else -> NoticeLevel.INFO
            }
            val text = entry.message + (entry.cause?.let { cause -> if (cause.toString() in entry.message) "" else " — $cause" } ?: "")
            val notice = desktop.notify(text, level)
            if (entry.area == "config") configNotices += notice
        }
    }

    // Notices about mistakes in the configuration: a reloaded one says anew what is still wrong.
    internal val configNotices = ArrayList<dev.caulis.core.Notice>()

    val display = Display(loop)
    // The keymap and the settings it was made of (made anew when they change: a reload).
    internal var keymapOf = listOf(config.keyboardLayouts, config.keyboardVariants, config.keyboardOptions, config.keyboardModel)
    // (The seat owns the keymap: it closes the one it replaces, and its last one.)
    val seat = Seat(display, Keymap.fromNames(layouts = config.keyboardLayouts, variants = config.keyboardVariants, options = config.keyboardOptions, model = config.keyboardModel))
    internal val compositor = Compositor(display, renderer)
    val desktop = Desktop(renderer, seat, compositor, width, height)
    internal val rapply = RunningSettings(this)
    internal val rlock = RunningLock(this)
    internal val rmon = RunningMonitors(this)
    internal val rreload = RunningReload(this)
    internal val rgest = RunningGestures(this)
    internal val rinput = RunningInput(this)
    internal val renv = RunningEnvironment(this)
    internal val rrules = RunningRules(this)
    internal val rlooks = RunningAppearance(this)
    internal val rtablets = RunningTablets(this)

    /** logind (on the screen): sleep and wake, lock requests, brightness. Null elsewhere. */
    internal val logind: dev.caulis.core.Logind? = if (switchConsole != null) dev.caulis.core.Logind.connect(loop) else null

    /** The backend wakes its monitors after sleep (set by the screen backend). */
    var wakeMonitors: () -> Unit = {}

    /** A laptop's keys: volume, microphone, brightness, touchpad, music players. */
    internal val system = SystemControls(this)

    /** Numbers for windows (the IPC socket, scripts). */
    val windowIds = WindowIds(desktop, loop)
    internal val pointerGestures = PointerGestures(display, seat)

    /** Drawing tablets for programs that understand pens (tablet-v2). */
    val tablets = Tablets(display, seat)

    /** Socket name programs connect to, e.g. "wayland-1". */
    val socket: String

    val keys = KeyBindings()

    // The only keys of ours while the screen is locked; everything else goes to the locker.
    internal val lockedKeys = KeyBindings()

    // The keys that still work while a window keeps the others (a nested compositor, a VM).
    internal val inhibitedKeys = KeyBindings()

    // The keys of each key mode (submap), and the mode that is on.
    internal val modeKeys = HashMap<String, KeyBindings>()

    /** The key mode that is on (null: the usual keys). */
    var keyMode: String? = null
        internal set

    /** Keyboards, mice and touchpads (on the screen only; nested, the outer session has them). */
    var inputDevices: dev.caulis.core.libinput.InputDevices? = null
        set(value) {
            field = value
            value?.settings = config.input
            value?.let(rtablets::useTablets)
            value?.onSwitch = { switch, on ->
                if (switch == dev.caulis.core.libinput.Switch.LID) {
                    log.info { "The lid is ${if (on) "closed" else "open"}" }
                    for (action in config.lidActions) safely("onLid") { action(config, on) }
                    displays?.lid(on)
                }
            }
        }

    /** The display modes of a laptop with another monitor (on the screen only; set by it). */
    internal var displays: Displays? = null

    /** The clipboard (programs' and ours); set in init. */
    var clipboard: DataDeviceManager
        private set

    /** Windows asking for the focus (xdg-activation); set in init. */
    var activation: dev.caulis.core.Activation
        private set

    /** Idle timers (ext-idle-notify, onIdle); set in init. */
    var idle: dev.caulis.core.Idle
        private set

    // When screens were last switched off (input right after it does not wake them).
    private var screensOffAt = 0L

    // Someone did something: idle timers start over, and dark screens light up again.
    internal fun userActive() {
        idle.activity()
        if (config.wakeScreensOnInput && System.nanoTime() - screensOffAt > config.wakeScreensAfterMillis * 1_000_000L) {
            for (monitor in desktop.monitors) if (!monitor.powered) desktop.setPower(monitor, true)
        }
    }

    /** Switches the screens off (or on) — all of them, or the monitor [name]; false if one could not. */
    fun setScreens(on: Boolean, name: String? = null): Boolean {
        if (!on) screensOffAt = System.nanoTime()
        return desktop.monitors.filter { name == null || it.name.equals(name, ignoreCase = true) }.map { desktop.setPower(it, on) }.all { it }
    }

    /** Programs that arrange monitors (kanshi, wlr-randr); set in init. */
    var outputManagement: dev.caulis.core.OutputManagement
        private set

    /** What programs are told about how to look (light or dark, the accent colour). */
    val appearance = dev.caulis.core.appearance.Appearance()
    /** The session bus, connected when first asked for and shared (null: there is none). */
    val sessionBus: dev.caulis.core.dbus.DBusConnection? by lazy {
        dev.caulis.core.dbus.DBusConnection.session(loop).also { sessionBusConnected = true }
    }
    private var sessionBusConnected = false

    /** The system bus (NetworkManager, BlueZ, UPower, ...) for the configuration's code, opened when first asked for. */
    val systemBus: dev.caulis.core.dbus.DBusConnection? by lazy {
        dev.caulis.core.dbus.DBusConnection.system(loop).also { systemBusConnected = true }
    }
    private var systemBusConnected = false

    // keyboard-shortcuts-inhibit (set in init).
    internal var shortcutsInhibit: dev.caulis.core.input.ShortcutsInhibit

    // Takes on changed code while running (null when off or not started from a project).
    private var reloader: Reloader? = null

    // The user's event listeners of the configuration of the moment (replaced on a reload).
    internal val configListeners = ArrayList<AutoCloseable>()

    // Where a cursor theme that came with a library is put for programs that draw their own cursor.
    internal var cursorFolder: java.nio.file.Path? = null

    // X11 programs (null if turned off). Declared before init, which sets it.
    internal var xwayland: Xwayland? = null

    /** The X11 display programs reach Xwayland at (DISPLAY), or null. */
    val x11Display: String? get() = xwayland?.displayName

    // The IPC socket (null if turned off). Declared before init, which sets it.
    internal var ipc: IpcServer? = null

    // Starts programs: Wayland first, X11 when that fails.
    internal val launcher = Launcher(
        environment = { env -> renv.fillEnvironment(env) },
        userEnvironment = renv::userEnvironment,
        x11Available = { xwayland != null },
        post = loop::post,
        settings = {
            Launcher.Settings(
                config.waylandFirst, config.x11Programs.toSet(), desktop.largestScale, config.xwayland && config.sharpJavaScaling,
                config.programOutput, config.programOutputFile ?: defaultProgramLog(),
                config.x11Fallback, config.x11FallbackMillis,
            )
        },
    )

    /** The session's start: the reveal, the startup sound, the programs of autostart (see SessionStart). */
    internal val sessionStart = SessionStart(this, launcher::startProgram)

    // Where programs' output goes with ProgramOutput.FILE when no file is named.
    private fun defaultProgramLog(): java.nio.file.Path {
        val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.cache")
        return java.nio.file.Path.of(cache, config.configName, "programs.log")
    }

    init {
        Shm(display)
        if (renderer.renderNode != null) LinuxDmabuf(display, renderer)
        clipboard = DataDeviceManager(display, seat)
        // A notice's copy button puts its text here.
        desktop.copyText = { text -> clipboard.setClipboard(dev.caulis.core.TextOnClipboard(text)) }
        desktop.osdHandler = config.osdHandler
        // (Middle-click paste: offered unless the configuration said no.)
        if (config.primarySelection) dev.caulis.core.PrimarySelection(display, seat, clipboard) else clientsLog.info { "Middle-click paste is off (primarySelection)" }
        dev.caulis.core.DataControl(display, clipboard)
        activation = dev.caulis.core.Activation(display, seat, desktop)
        logind?.let { l ->
            l.onSleep = { ready -> rlock.goingToSleep(ready) }
            l.onWake = rlock::wokeUp
            l.onLock = { lock -> if (lock && !rlock.lockedNow()) safely("lock") { config.lock() } }
        }
        idle = dev.caulis.core.Idle(display, loop, desktop)
        desktop.onUserActivity = ::userActive
        desktop.pointerConstraints = dev.caulis.core.input.PointerConstraints(display, seat)
        desktop.tablets = tablets
        // A pen that is the pointer goes the way a mouse goes: the hooks and bindings see it too.
        desktop.penPointer = object : PenPointer {
            override fun moved(timeMillis: Int, x: Double, y: Double) = rinput.pointerMovedTo(timeMillis, x, y)
            override fun button(timeMillis: Int, button: Int, pressed: Boolean) = rinput.pointerButton(timeMillis, button, pressed)
        }
        dev.caulis.core.OutputPower(display, desktop)
        dev.caulis.core.GammaControl(display, desktop)
        outputManagement = dev.caulis.core.OutputManagement(display, desktop)
        Presentation(display)
        XdgShell(display).listener = desktop
        LayerShell(display).listener = desktop
        // (The desktop handles lockers; we only note which one is in charge, and its unlock.)
        SessionLockManager(display).listener = object : SessionLockManager.Listener by desktop {
            override fun lockRequested(lock: dev.caulis.core.SessionLock): Boolean =
                desktop.lockRequested(lock).also { granted -> if (granted) rlock.lockerProgram = lock }

            override fun unlockRequested(lock: dev.caulis.core.SessionLock) {
                desktop.unlockRequested(lock)
                if (lock === rlock.lockerProgram) {
                    rlock.lockerProgram = null
                    rlock.lockerUnlocked(lock)
                }
            }
        }
        CursorShape(display, seat)
        shortcutsInhibit = dev.caulis.core.input.ShortcutsInhibit(display, seat)
        XdgOutputManager(display)
        ServerDecorations(display, desktop)
        dev.caulis.core.Dialogs(display, desktop)
        xwayland = if (config.xwayland) safely("Xwayland", null) { Xwayland(display, desktop, loop, clipboard, wholeScaleWorlds = config.sharpJavaScaling) } else null
        val toplevels = ForeignToplevels(display, desktop)
        // (Who may see the window list: the programs named in windowListPrograms, else the privileged rule.)
        val privilegedRule = toplevels.mayListWindows
        toplevels.mayListWindows = { client ->
            val named = config.windowListPrograms
            if (named == null) privilegedRule(client) else (programOfProcess(client.pid) in named).also { allowed ->
                if (!allowed) clientsLog.info { "${client.name} may not see the window list (windowListPrograms)" }
            }
        }
        capture = ScreenCapture(display, desktop, renderer, toplevels).also { c ->
            // (Permissions: "ask" makes a program's pictures wait for the person's answer.)
            c.allowed = { client -> permissions.captureAllowed(client) }
            c.asking = { client -> permissions.asking(client, "screencopy") }
        }
        rmon.applyCursor()
        desktop.workspaceMode = config.workspaceMode // before the monitors come
        desktop.primaryMonitorName = config.primaryMonitor
        desktop.workspaceCount = config.workspaceCount
        desktop.workspaceRules = config.workspaceRules
        desktop.layout = config.layout
        socket = config.socketDirectory?.let(display::addSocket) ?: display.addSocket()
        // The IPC socket beside the Wayland one: bars, scripts and tools talk to us there.
        ipc = if (config.ipc) {
            safely("the IPC socket", null) {
                IpcServer(this, IpcServer.socketPath(config.socketDirectory ?: dev.caulis.protocol.Display.defaultRuntimeDir(), config.configName, socket))
            }
        } else null

        desktop.scale = config.scale
        xwayland?.rescaled() // (made before the scale was set)

        // The user's rules (dialogs and fixed-size windows always float); they ask the
        // configuration of the moment, so they need no setting again after a reload.
        val defaultRule = desktop.floatRule
        desktop.windowEffects = { window ->
            val fx = config.effectRules.fold(desktop.effects) { fx, rule -> safely("effectsFor", fx) { rule(window, fx) } }
            config.rules.fold(fx) { now, rule -> if (rule.hasLooks && rrules.fits(rule, window)) safely("$rule effects", now) { rule.effectsOf(now) } else now }
        }
        // Window rules: where windows open (decided before their first frame), and once more
        // for a window that fits a rule only after it changed its title or app id.
        desktop.placementFor = { window ->
            val fitting = config.rules.filter { it.hasPlacement && rrules.fits(it, window) }
            rrules.rulesApplied.getOrPut(window) { HashSet() } += fitting.map { it.signature }
            fitting.fold(WindowPlacement.USUAL) { placement, rule -> placement.and(rule.placement()) }.takeIf { fitting.isNotEmpty() }
        }
        desktop.onWindowInfoChanged { window ->
            val applied = rrules.rulesApplied.getOrPut(window) { HashSet() }
            for (rule in config.rules) {
                if (!rule.hasPlacement || rule.signature in applied || !rrules.fits(rule, window)) continue
                applied += rule.signature
                rrules.rulesLog.info { "$window fits $rule now" }
                safely("$rule") { desktop.applyPlacement(window, rule.placement()) }
            }
            // Its margins and title bar too (a rule may fit it now, or no longer).
            rrules.rulesChanged(window)
        }
        desktop.behaviorFor = { window ->
            config.rules.fold(dev.caulis.core.WindowBehavior.USUAL) { now, rule -> if (rule.hasBehavior && rrules.fits(rule, window)) rule.behaviorOf(now) else now }
        }
        desktop.onWindowOpened { window ->
            rrules.shownTitleBars[window] = rrules.titleBarsOf(window)
            rrules.shownMargins[window] = rrules.marginsOf(window)
        }
        desktop.marginsFor = { window -> rrules.marginsOf(window) }
        desktop.layerEffects = { layer ->
            val fx = config.panelEffectRules.firstNotNullOfOrNull { rule -> safely("panelEffects", null) { rule(layer) } } ?: Effects.NONE
            config.panelRules.fold(fx) { now, rule -> if (safely("$rule", false) { rule.fits(layer) }) safely("$rule effects", now) { rule.effectsOf(now) } else now }
        }
        desktop.layerBehaviorFor = { layer ->
            config.panelRules.fold(dev.caulis.core.LayerBehavior.USUAL) { now, rule -> if (safely("$rule", false) { rule.fits(layer) }) rule.behaviorOf(now) else now }
        }
        desktop.floatRule = { window -> defaultRule(window) || config.floatRules.any { rule -> safely("floatWhen", false) { rule(window) } } }
        desktop.onWindowOpened { window -> launcher.windowOpened(window.pid) }
        rapply.applyConfig()
    }

    fun quit() = loop.stop()

    // When each scratchpad's program was started (so a second press does not start it twice).
    private val startedAt = HashMap<String, Long>()

    /** Shows or hides a scratchpad; starts its program if it has no window yet. */
    fun toggleScratchpad(name: String) {
        if (desktop.toggleScratchpad(name)) return
        val command = config.scratchpads.firstOrNull { it.name == name }?.command ?: return
        val now = System.nanoTime()
        val last = startedAt[name]
        if (last != null && now - last < config.scratchpadStartMillis * 1_000_000) return // still starting
        startedAt[name] = now
        launch(command)
    }

    /**
     * Starts [command] (run by /bin/sh) as a program inside the compositor, the Wayland way
     * first (or through X11: [x11]). If the program does not exist, that is said in plain
     * words instead of nothing happening.
     */
    fun launch(command: String, x11: Boolean = false, fallback: Boolean = true) {
        val pid = launcher.launch(command, x11, fallback)
        if (pid != null && rlock.startingLocker) rlock.lockerPids += pid
    }

    fun spawn(command: List<String>, directory: java.io.File?): Process? =
        launcher.spawn(command, directory)?.also { if (rlock.startingLocker) rlock.lockerPids += it.pid() }

    // Runs the user's code; a mistake in it is reported and the compositor keeps running.
    internal fun <T> safely(what: String, fallback: T, action: () -> T): T = try {
        action()
    } catch (e: Throwable) {
        e.rethrowIfFatal()
        log.error("Your code for \"$what\" failed (ignored): $e", e)
        fallback
    }

    internal fun safely(what: String, action: () -> Unit) = safely(what, Unit, action)

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

    // Java updated under the running compositor: said once (Caulis.javaUpdatedNotice).
    private var javaUpdates: AutoCloseable? = null

    /** Runs the user's start actions and the main loop until [quit]. */
    fun runLoop() {
        config.running = this
        try {
            // (Asked now: what is on disk at the start is what later changes are compared with.)
            dev.caulis.ffi.Processes.javaChanged()
            javaUpdates = dev.caulis.ffi.Processes.onJavaChanged {
                loop.post { if (isOpen) config.javaUpdatedNotice?.let { desktop.notify(it, NoticeLevel.WARNING) } }
            }
            config.exitAfterSeconds?.let { seconds -> loop.after(seconds * 1000) { quit() } }
            for (problem in config.startWarnings) desktop.notify("${problem.message} ${problem.fix}", NoticeLevel.WARNING)
            for (problem in config.configProblems) configNotices += desktop.notify(problem, NoticeLevel.WARNING)
            if (config.useConfigFile) rreload.followConfigFile()
            if (config.exportEnvironment ?: (switchConsole != null)) safely("handing the environment to the session") { renv.exportEnvironment() }
            // The start: dark until warm, the sound, the programs of autostart (see SessionStart).
            sessionStart.begin()
            // What onStart starts is the session's: it stays through reloads (see Caulis.onStart).
            startingUp = true
            try {
                config.startActions.forEach { action -> safely("onStart") { action(config) } }
            } finally {
                startingUp = false
            }
            if (rreload.keptItems.isNotEmpty()) log.info { "onStart added ${rreload.keptItems.size} keys and listeners: they stay through reloads" }
            config.startWaitingTimers()
            safely("plugins") { rplugins.start() }
            if (config.hotReload) reloader = safely("hot reload", null) { Reloader.start(this) }
            log.info { "Your files (shaders, pictures): ${config.configFolder}${if (java.nio.file.Files.isDirectory(config.configFolder)) "" else " (not there yet)"}" }
            watchTheStart()
            safely("telling of the last crash report") { CrashHandling.tellOfLast(config, desktop) }
            loop.run()
        } finally {
            config.running = null
        }
    }

    // How the start went (the longest the loop was busy), and the memory the start freed given back.
    private fun watchTheStart() {
        loop.stallMillis = config.stallReportMillis
        loop.stallWarnMillis = config.stallWarnMillis
        if (config.stallReportMillis > 0) {
            loop.after(START_REPORT_MILLIS) {
                val longest = loop.longestStall?.let { "${loop.longestStallMillis} ms: $it" } ?: "never ${config.stallReportMillis} ms or more"
                dev.caulis.log.Logger("native").info { "The first ${START_REPORT_MILLIS / 1000} s: the main loop was busy at most $longest" }
            }
        }
        if (!config.trimMemory || !dev.caulis.ffi.NativeMemory.canTrim) return
        fun trim() {
            if (!isOpen) return
            Thread.ofPlatform().daemon().name("caulis-trim").start { dev.caulis.ffi.NativeMemory.trim() }
        }
        loop.after(TRIM_FIRST_MILLIS) { trim() }
        loop.after(TRIM_SECOND_MILLIS) {
            trim()
            val minutes = config.trimMemoryEveryMinutes
            if (minutes > 0) {
                fun next() {
                    loop.after(minutes * 60_000L) {
                        trim()
                        if (isOpen) next()
                    }
                }
                next()
            }
        }
    }

    override fun close() {
        // Your onQuit first, while the desktop and the buses are still there.
        config.quitActions.toList().forEach { action -> safely("onQuit") { action(config) } }
        // Then the plugins: each hears, and what it registered goes while all that is still there.
        rplugins.close()
        // The configuration's window and focus listeners end here: windows that go away now (their
        // programs cut off as everything is taken down) are not "closed" by anyone — a handler
        // would find no desktop to act on.
        configListeners.forEach { runCatching { it.close() } }
        configListeners.clear()
        javaUpdates?.close()
        // (No program of autostart is started again while they are being stopped.)
        safely("the start") { sessionStart.close() }
        if (config.stopProgramsOnQuit) safely("stopping the programs") { launcher.stopAll() }
        isOpen = false
        if (config.running === this) config.running = null
        sessionResources.toList().forEach { runCatching { it.close() } }
        sessionResources.clear()
        sessionScopes.values.forEach { it.close() }
        rlooks.colorSchemeTimer?.cancel()
        rlooks.stopTellingPrograms()
        if (sessionBusConnected) sessionBus?.close()
        if (systemBusConnected) systemBus?.close()
        logind?.close()
        displays?.close()
        rreload.configWatches.forEach(AutoCloseable::close)
        system.close()
        rreload.configReloads.shutdownNow()
        ipc?.close()
        reloader?.close()
        logNotices.close()
        xwayland?.close()
        desktop.close()
        seat.close()
        display.close()
    }

    // Last of all (after every property above): from here on the configuration's code may use
    // the desktop — onMonitorAdded runs for the first monitors before the loop does.
    init {
        config.running = this
        config.session = this
    }
}

/** Milliseconds on the clock Wayland and libinput timestamps use. */
internal fun now(): Int = (System.nanoTime() / 1_000_000).toInt()
