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

    /** Widgets, drawings and the like the session keeps (from onStart): closed when the compositor ends. */
    val sessionResources = ArrayList<AutoCloseable>()

    // The buses as the session uses them (onStart, replaced configurations): never taken away.
    private val sessionScopes = HashMap<Boolean, dev.caulis.core.dbus.DBusScope>()

    /** The session's view of the session bus (true) or the system bus (false), or null if there is none. */
    fun sessionScope(session: Boolean): dev.caulis.core.dbus.DBusScope? = sessionScopes[session] ?: run {
        val connection = (if (session) sessionBus else systemBus) ?: return null
        dev.caulis.core.dbus.DBusScope(connection).also { sessionScopes[session] = it }
    }

    // Monitors switched off because the configuration says enabled = false.
    private val disabledByConfig = config.monitorSettings.values.filter { !it.enabled }.mapTo(HashSet()) { it.name }

    internal val log = Logger("config")
    internal val keyboardLog = Logger("keyboard")
    private val clientsLog = Logger("clients")

    // Screen pictures (made with the display; their permission questions answered through it).
    private var capture: ScreenCapture? = null

    // The permission rules at work, and the person's answers.
    private val permissions = PermissionKeeper({ config }, { capture?.answered() }, { client -> display.privileged(client) })
    private val cursorLog = Logger("cursor")
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
    private val compositor = Compositor(display, renderer)
    val desktop = Desktop(renderer, seat, compositor, width, height)
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

    // The configuration's own idle timers (replaced on a reload).
    private val idleWatches = ArrayList<AutoCloseable>()
    private var appliedNumLock: Boolean? = null

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
    private val configListeners = ArrayList<AutoCloseable>()

    // Where a cursor theme that came with a library is put for programs that draw their own cursor.
    internal var cursorFolder: java.nio.file.Path? = null

    // X11 programs (null if turned off). Declared before init, which sets it.
    internal var xwayland: Xwayland? = null

    /** The X11 display programs reach Xwayland at (DISPLAY), or null. */
    val x11Display: String? get() = xwayland?.displayName

    // The IPC socket (null if turned off). Declared before init, which sets it.
    internal var ipc: IpcServer? = null

    // Starts programs: Wayland first, X11 when that fails.
    private val launcher = Launcher(
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
            l.onSleep = { ready -> goingToSleep(ready) }
            l.onWake = ::wokeUp
            l.onLock = { lock -> if (lock && !lockedNow()) safely("lock") { config.lock() } }
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
                desktop.lockRequested(lock).also { granted -> if (granted) lockerProgram = lock }

            override fun unlockRequested(lock: dev.caulis.core.SessionLock) {
                desktop.unlockRequested(lock)
                if (lock === lockerProgram) {
                    lockerProgram = null
                    lockerUnlocked(lock)
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
        applyCursor()
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
        applyConfig()
    }

    // True while the screen is locked: by a locker program, or by the compositor's own (lockerShows).
    private fun lockedNow(): Boolean =
        desktop.locked || config.lockerShows?.let { shows -> safely("lockerShows", false) { shows(config) } } == true

    // The computer is about to sleep: your onSleep, the screen locked (when asked for), then [ready].
    private fun goingToSleep(ready: () -> Unit) {
        config.sleepActions.toList().forEach { action -> safely("onSleep") { action(config) } }
        if (!config.lockBeforeSleep || lockedNow()) return ready()
        safely("the lock before sleep") { config.lock() }
        // A lock screen of the compositor's own that cannot say when it shows: taken as up at once.
        if (config.locker != null && config.lockerShows == null) return ready()
        // Sleep once the lock screen shows (or after a while: logind does not wait for ever anyway).
        val since = System.nanoTime()
        fun check() {
            when {
                lockedNow() -> ready()
                System.nanoTime() - since > config.sleepLockWaitMillis * 1_000_000 -> {
                    lockFailed("it did not lock in ${config.sleepLockWaitMillis} ms before sleep")
                    // (Our own lock screen: a moment to be on screen before the computer sleeps.)
                    if (fallbackLock != null) loop.after(config.sleepOwnLockShowMillis) { ready() } else ready()
                }
                else -> loop.after(50) { check() }
            }
        }
        check()
    }

    // The computer woke up: the monitors are set up anew (sleep may have reset them), the idle
    // timers start over, and your onWake runs.
    private fun wokeUp() {
        safely("waking the monitors") { wakeMonitors() }
        userActive()
        safely("the colour schedule") { rlooks.updateColorScheme() } // (the clock went on while asleep)
        config.wakeActions.toList().forEach { action -> safely("onWake") { action(config) } }
    }

    /** Hands the background rule over again (it was changed while running). */
    fun applyLooks() {
        desktop.backgroundFor = config.backgroundFor?.let { rule -> { monitor, workspace -> safely("backgroundFor", null) { rule(monitor.name, workspace) } } }
    }

    // What of the configuration is handed over to the desktop, keys and events — at the
    // start and again after each reload.
    /** Settings changed by code while running (Caulis.applySettings). */
    internal fun applySettingsNow() = applyConfig()

    // The desktop's own focus-stealing rule (focusStealing SAME_PROGRAM), kept the first time.
    // (No initializer: this is first used from init, which runs before this line would.)
    private lateinit var sameProgramRule: (dev.caulis.core.Window, dev.caulis.core.WindowRequest) -> Boolean

    /** The settings of Caulis's core parts as the configuration says (see Caulis.focusStealing, maxRegionParts, ...). */
    fun applyCoreSettings() {
        val config = config
        if (!::sameProgramRule.isInitialized) sameProgramRule = desktop.programMayTakeFocus
        desktop.backgroundColor = config.backgroundColor
        desktop.programMayTakeFocus = config.focusStealingRule?.let { rule -> { window, why -> safely("focusStealingRule", false) { rule(window, why) } } }
            ?: when (config.focusStealing) {
                FocusStealing.SAME_PROGRAM -> sameProgramRule
                FocusStealing.ALWAYS -> { _, _ -> true }
                FocusStealing.NEVER -> { _, _ -> false }
            }
        idle.inhibitorsWorkWhileLocked = config.idleInhibitorsWhileLocked
        idle.protocolInhibitors = config.idleInhibitors
        desktop.unlockFocus = config.unlockFocus
        display.maxForwardedStringBytes = config.maxForwardedStringBytes
        compositor.maxRegionParts = config.maxRegionParts
        xwayland?.let { x ->
            x.restartDelayMillis = config.xwaylandRestartMillis
            x.restartDelayMaxMillis = config.xwaylandRestartMaxMillis
            x.maxFailedStarts = config.xwaylandMaxFailedStarts
        }
        applyMonitorLinks?.invoke()
        log.trace { "Core settings: focus stealing ${config.focusStealing}, strings ${config.maxForwardedStringBytes} bytes, regions ${config.maxRegionParts} parts" }
    }

    /** Set by the screen backend: each monitor's link settings (maxBpc, adaptiveSync) as the configuration says. */
    var applyMonitorLinks: (() -> Unit)? = null
    /** Tries Xwayland again after it failed too often (the action "xwayland-retry"). */
    fun retryXwayland() {
        val x = xwayland ?: return log.info { "Xwayland is off" }
        log.info { "Trying Xwayland again" }
        x.retry()
    }

    internal fun applyConfig() {
        val config = config
        applyCoreSettings()
        dev.caulis.ffi.Processes.method = config.processStart
        desktop.effects = config.effects
        desktop.specialWorkspaces = config.specialWorkspaces
        if (desktop.director !== config.director) desktop.director = config.director
        display.privileged = { client ->
            // (Every program asks which features there are right as it connects: the launcher
            // learns here that one of its programs is a Wayland one.)
            launcher.clientConnected(client.pid)
            safely("privilegedClients", false) { this.config.privilegedClients(client) }
        }
        // Each privileged feature: the permission rules first, else the privileged rule.
        display.mayUse = { client, feature -> permissions.mayUse(client, feature) }
        desktop.screenShader = config.screenShader
        desktop.titleBars = config.titleBars
        desktop.titleBarStyle = config.titleBarStyle
        desktop.titleBarsFor = if (config.titleBarRules.isEmpty() && config.rules.none { it.titleBars != null }) null else rrules::titleBarsOf
        desktop.noticeStyle = config.noticeStyle
        desktop.animationMillis = config.animationMillis
        desktop.animations = config.animations
        desktop.background = config.background
        applyLooks()
        desktop.backgroundChangeMillis = config.backgroundChangeMillis
        desktop.wallpaperShadersPaused = config.wallpaperShadersPaused
        desktop.wallpaperFpsLimit = config.wallpaperFpsLimit
        desktop.movingPaintsFps = config.movingPaintsFps
        desktop.overviewFill = config.overviewFill
        desktop.monitorEdges = config.monitorEdges
        desktop.resizeBorder = config.resizeBorder
        desktop.doubleClickMillis = config.doubleClickMillis
        desktop.lockColor = config.lockColor
        desktop.focusFollowsMouse = config.focusFollowsMouse
        desktop.quickCursorMoves = config.quickCursorMoves
        desktop.widgetThreads = config.widgetThreads
        desktop.osdHandler = config.osdHandler
        activation.focusOnActivate = config.focusOnActivate
        activation.tokensNeedFocus = config.activationTokensNeedFocus
        idle.fullscreenInhibits = config.fullscreenKeepsAwake
        applyIdleWatches()
        applyKeyRepeat()
        rinput.applyKeymap()
        applyNumLock()
        rlooks.applyAppearance()
        inputDevices?.settings = config.input
        inputDevices?.ownThread = config.inputThread
        applyWorkspaces()
        desktop.primaryMonitorName = config.primaryMonitor
        applyScratchpads()
        rewireEvents()
        sessionStart.reconfigure()
    }

    /** The configuration's idle timers, made anew (they count from now). */
    fun applyIdleWatches() {
        idleWatches.forEach(AutoCloseable::close)
        idleWatches.clear()
        val config = config
        for (watch in config.idleWatches) {
            idleWatches += idle.watch(watch.seconds * 1000L, onIdle = { safely("onIdle") { watch.idle(config) } }, onResume = { safely("onIdle resume") { watch.resume(config) } })
        }
        log.trace { "Idle timers: ${config.idleWatches.joinToString { "${it.seconds} s" }.ifEmpty { "none" }}" }
    }

    /** Key repeat as the configuration says, for programs and for the compositor's own keys. */
    fun applyKeyRepeat() {
        seat.repeatRate = config.repeatRate
        seat.repeatDelay = config.repeatDelayMillis.toInt()
        for (bindings in rinput.allKeys()) {
            bindings.repeatDelay = config.repeatDelayMillis.toInt()
            bindings.repeatRate = config.repeatRate
        }
        keyboardLog.trace { "Key repeat: ${config.repeatRate} a second after ${config.repeatDelayMillis} ms" }
    }

    /** Num Lock as configured, when that changes (not again on each reload: the key may have switched it since). */
    fun applyNumLock() {
        val on = config.numLock ?: return
        if (on == appliedNumLock) return
        seat.setNumLock(on)
        appliedNumLock = on
        keyboardLog.info { "Num Lock ${if (on) "on" else "off"}" }
    }

    /** The configuration's workspaces: how many, and how each is. */
    fun applyWorkspaces() {
        desktop.workspaceCount = config.workspaceCount
        desktop.workspaceRules = config.workspaceRules
    }

    /** The configuration's scratchpads: those not there yet made, and their keys bound. */
    fun applyScratchpads() {
        for (pad in config.scratchpads) {
            // A scratchpad asks the configuration of the moment which windows are its own.
            if (desktop.scratchpad(pad.name) == null) {
                desktop.addScratchpad(pad.name, pad.style) { window ->
                    val now = this.config.scratchpads.firstOrNull { it.name == pad.name } ?: return@addScratchpad false
                    safely("scratchpad ${pad.name}", false) { now.match(window) }
                }
                log.info { "Scratchpad ${pad.name}" }
            }
        }
        rebindKeys()
    }

    /** Hands the configuration's event listeners to the desktop anew (after a reload, or one added or taken away). */
    fun rewireEvents() {
        configListeners.forEach(AutoCloseable::close)
        configListeners.clear()
        val config = config
        config.noticeListeners.forEach { action -> configListeners += desktop.onNotice { notice -> safely("onNotice") { action(config, notice) } } }
        config.windowOpened.forEach { action -> configListeners += desktop.onWindowOpened { window -> action(config, window) } }
        config.windowClosed.forEach { action -> configListeners += desktop.onWindowClosed { window -> action(config, window) } }
        config.windowChanged.forEach { action -> configListeners += desktop.onWindowInfoChanged { window -> safely("onWindowChanged") { action(config, window) } } }
        config.focusChanged.forEach { action -> configListeners += desktop.onFocusChanged { window -> action(config, window) } }
        config.workspaceChanged.forEach { action -> configListeners += desktop.onWorkspaceChanged { workspace -> action(config, workspace.number) } }
    }

    /**
     * Binds every key anew: the usual ones, yours (those switched on), scratchpads', quitting
     * and consoles — at the start, after a reload, and when one of yours is switched on or off.
     */
    fun rebindKeys() {
        keys.clear()
        lockedKeys.clear()
        inhibitedKeys.clear()
        bindKeys()
        for (pad in config.scratchpads) pad.key?.let { key -> keys.bind(key) { toggleScratchpad(pad.name) } }
        // Text consoles also work while the screen is locked (when they are on); quitting only
        // when keys.quitWhileLocked says so (else it would get round the lock).
        val usual = config.keys
        for (bindings in listOf(keys, lockedKeys, inhibitedKeys)) {
            if (bindings !== lockedKeys || usual.quitWhileLocked) usual.quit?.let { bindings.bind(it) { quit() } }
            if (switchConsole != null) usual.consoles?.let { modifiers -> for (n in 1..12) bindings.bind("$modifiers+F$n") { switchConsole.invoke(n) } }
        }
        usual.restoreShortcuts?.let { inhibitedKeys.bind(it) { shortcutsInhibit.restore() } }
        if (usual.quit == null && (usual.consoles == null || switchConsole == null)) {
            log.warn("No key quits and none switches to a text console (keys.quit and keys.consoles are off): if a program takes the whole screen, only another computer (ssh) or the power button gets you out")
        }
        // Not one key of ours at all (every usual key off, none of your own): a way out and a
        // terminal anyway, so a broken configuration never locks you in.
        if (usual.bindings(config.workspaceCount).isEmpty() && config.bindings.none { it.enabled } && config.scratchpads.none { it.key != null }) {
            keys.bind(EMERGENCY_QUIT) { quit() }
            keys.bind(EMERGENCY_TERMINAL) { safely("the emergency terminal") { config.openTerminal() } }
            log.warn("Your configuration has no keys at all: $EMERGENCY_QUIT quits and $EMERGENCY_TERMINAL opens a terminal until it has some")
        }
    }

    // The usual keys first, the user's own after them (later ones win on the same combination).
    private fun bindKeys() {
        // Those you changed after the others: on the same keys, yours wins over a default one.
        val usual = config.keys.bindings(config.workspaceCount).sortedBy { config.keys.changed(it.first) }
        val taken = HashMap<Pair<Set<KeyBindings.Modifier>, Int>, String>()
        for ((name, combination, action) in usual) {
            val parsed = KeyBindings.parse(combination)
            taken.put(parsed, name)?.takeIf { it != name }?.let { other ->
                log.warn("keys.$name and keys.$other are both $combination: keys.$name wins")
            }
            val options = KeyBindings.Options(repeat = config.keys.repeats(name))
            val run = { safely(combination) { action(config) } }
            keys.bind(combination, options, run)
            if (config.keys.whileLocked(name)) lockedKeys.bind(combination, options, run)
            // (A program that keeps the keys for itself never keeps the screen from locking.)
            if (name == "lock" && config.keys.lockWhileInhibited) inhibitedKeys.bind(combination, options, run)
        }
        modeKeys.clear()
        for (name in config.modeNames) modeKeys[name] = KeyBindings()
        for (binding in config.bindings) {
            if (!binding.enabled) continue
            val combination = binding.combination
            val run = { safely(combination) { binding.action(config) } }
            val mode = binding.keyMode
            if (mode != null) {
                modeKeys.getOrPut(mode) { KeyBindings() }.bind(combination, binding.options, run)
                continue
            }
            KeyBindings.parseAllowingTap(combination).takeIf { it.second != null }?.let { parsed ->
                taken[parsed.first to parsed.second!!]?.let { name -> log.info { "bind(\"$combination\") replaces keys.$name" } }
            }
            keys.bind(combination, binding.options, run)
            if (binding.options.whileLocked) lockedKeys.bind(combination, binding.options, run)
        }
        // Quitting and consoles in every key mode too.
        val always = config.keys
        for (bindings in modeKeys.values) {
            always.quit?.let { bindings.bind(it) { quit() } }
            if (switchConsole != null) always.consoles?.let { modifiers -> for (n in 1..12) bindings.bind("$modifiers+F$n") { switchConsole.invoke(n) } }
        }
        // A mode that is gone after a reload is left.
        if (keyMode != null && keyMode !in modeKeys) rinput.enterMode(null)
        // Combinations that repeat use the loop and the key repeat of the moment.
        // Which keys are modifiers: as the keymap says (caps:super, altwin:swap_lalt_lwin), or the physical ones.
        val modifierOf: (Int) -> KeyBindings.Modifier? =
            if (config.bindingModifiers == BindingModifiers.XKB) seat::modifierOfKey else { code -> KeyBindings.Modifier.of(code) }
        // Which key a combination's letter is: as the layout says (the first Latin one for Russian), or the physical key.
        val keyOf: (Int) -> Int = if (config.bindingsUseFirstLatinLayout) seat::bindingKeyOf else { code -> code }
        keyboardLog.trace { "Bindings by ${if (config.bindingsUseFirstLatinLayout) "the layout (the first Latin one for other scripts)" else "the physical keys"}" }
        for (bindings in rinput.allKeys()) {
            bindings.modifierOf = modifierOf
            bindings.keyOf = keyOf
            bindings.schedule = { millis, task -> loop.after(millis) { task() }.let { timer -> AutoCloseable { timer.cancel() } } }
            bindings.repeatDelay = config.repeatDelayMillis.toInt()
            bindings.repeatRate = config.repeatRate
        }
    }

    fun quit() = loop.stop()

    /** Applies the scale to the desktop, the monitors (their own where set) and the programs. */
    fun applyScale() {
        // Each monitor straight to its scale (one with its own never passes through the
        // usual one: programs would draw twice, and the screen change twice).
        desktop.defaultScale = config.scale
        for (monitor in desktop.monitors) desktop.setScale(monitor, config.monitorSettings[monitor.name]?.scale ?: config.scale)
        xwayland?.rescaled()
        ipc?.changed()
        outputManagement.changed()
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
        settings?.transform?.let { desktop.setTransform(monitor, it) }
        config.monitorAdded.forEach { action -> safely("onMonitorAdded") { action(config, name) } }
        xwayland?.rescaled() // the largest scale may be another now
        ipc?.changed() // subscribers hear of the monitors
        outputManagement.changed()
        return monitor
    }

    /** A monitor went away (the backend's). */
    fun removeMonitor(monitor: Monitor) {
        desktop.removeMonitor(monitor)
        config.monitorRemoved.forEach { action -> safely("onMonitorRemoved") { action(config, monitor.name) } }
        xwayland?.rescaled()
        ipc?.changed()
        outputManagement.changed()
    }

    /**
     * Applies changed monitor settings (mode, scale, turn, place, on or off) to the monitors
     * there are; only what differs changes.
     */
    fun applyMonitorSettings() {
        for (monitor in desktop.monitors) {
            val settings = config.monitorSettings[monitor.name] ?: continue
            applyMode(monitor, settings)
            settings.scale?.let { desktop.setScale(monitor, it) }
            settings.transform?.let { desktop.setTransform(monitor, it) }
            desktop.moveMonitor(monitor, settings.x, settings.y)
        }
        // Off and on again, as the configuration says now (the last monitor stays: else nothing shows).
        val heads = outputManagement.heads
        for (monitor in desktop.monitors.toList()) {
            if (config.monitorSettings[monitor.name]?.enabled != false) continue
            if (desktop.monitors.size <= 1) {
                log.warn("Monitor ${monitor.name} stays on: it is the only one (enabled = false is for one of several)")
            } else if (heads.setEnabled(monitor.name, false)) {
                disabledByConfig += monitor.name
                log.info { "Monitor ${monitor.name} switched off (enabled = false)" }
            }
        }
        for (name in disabledByConfig.toList()) {
            if (config.monitorSettings[name]?.enabled == false) continue
            disabledByConfig -= name
            if (heads.setEnabled(name, true)) log.info { "Monitor $name used again" }
        }
        applyMonitorLinks?.invoke()
        xwayland?.rescaled()
        ipc?.changed()
        outputManagement.changed()
    }

    // The mode the configuration asks of [monitor] (mode(width, height, refreshHz)), if it is another.
    private fun applyMode(monitor: Monitor, settings: Caulis.MonitorSettings) {
        val width = settings.width ?: return
        val height = settings.height ?: return
        val control = monitor.control ?: return
        val hz = settings.refreshHz
        val wanted = control.modes.filter { it.width == width && it.height == height }
            .minByOrNull { mode -> hz?.let { Math.abs(mode.refreshMillihertz / 1000.0 - it) } ?: -mode.refreshMillihertz.toDouble() }
            ?: if (control.customModes) dev.caulis.core.MonitorMode(width, height, ((hz ?: 60.0) * 1000).toInt()) else null
        if (wanted == null) {
            log.warn("Monitor ${monitor.name} has no mode ${width}x$height (it has ${control.modes.joinToString()})")
            return
        }
        val current = control.currentMode
        if (current != null && current.width == wanted.width && current.height == wanted.height && current.refreshMillihertz == wanted.refreshMillihertz) return
        if (control.setMode(wanted)) log.info { "Monitor ${monitor.name}: mode $wanted" } else log.warn("Monitor ${monitor.name} could not take the mode $wanted")
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
        if (pid != null && startingLocker) lockerPids += pid
    }

    // Programs started as the screen locker (by Caulis.lock: lockCommand, or what your locker
    // started): only their unlock ends the compositor's own lock screen. (Ended ones stay in
    // the set: a pid is reused only after many others.)
    internal val lockerPids = HashSet<Long>()
    private var startingLocker = false

    /** Runs [body], which starts the screen locker: the programs it starts may end our own lock screen. */
    fun <T> startingLocker(body: () -> T): T {
        val outer = startingLocker
        startingLocker = true
        try {
            return body()
        } finally {
            startingLocker = outer
        }
    }

    // Says so when a lock asked for has not shown in time (see Caulis.lock).
    private var lockWatch: dev.caulis.event.EventLoop.Timer? = null

    /** A lock was asked of [locker]: if the screen is not locked [Caulis.lockShowMillis] later, a notice says so. */
    fun watchLock(locker: String) {
        lockWatch?.cancel()
        val millis = config.lockShowMillis
        lockWatch = loop.after(millis) {
            lockWatch = null
            if (!lockedNow()) lockFailed("$locker showed no lock screen in $millis ms")
        }
    }

    // A lock asked for did not show ([why]): said loudly, and — as lockFailure says — the
    // compositor's own plain lock screen goes up instead.
    private fun lockFailed(why: String) {
        // (Said once: the lock before sleep and the watch on the lock may both find it.)
        lockWatch?.cancel()
        lockWatch = null
        log.error("The screen did not lock: $why")
        desktop.notify("The screen is NOT locked: $why. Is the locker installed? (lockCommand)", NoticeLevel.ERROR)
        if (config.lockFailure == LockFailure.OWN_LOCK) lockOwnScreen()
    }

    // The compositor's own plain lock screen (the locker did not show), or null.
    private var fallbackLock: FallbackLock? = null

    // The locker program in charge now (to know its unlock ends our own lock screen too).
    private var lockerProgram: dev.caulis.core.SessionLock? = null

    /** Locks with the compositor's own plain lock screen (see [Caulis.lockFailure]). */
    fun lockOwnScreen() {
        if (fallbackLock != null) return
        val screen = FallbackLock(color = { config.lockColor }, retry = {
            log.info { "Starting the locker again (Enter on the compositor's own lock screen)" }
            safely("the locker") { config.lock() }
        })
        fallbackLock = screen
        desktop.lockInternally(screen, hideDesktop = true)
        log.warn(
            "Locked with the compositor's own plain lock screen: it ends when the locker the compositor starts unlocks — " +
                "press Enter to start ${Launcher.programName(config.lockCommand)} again (from a text console: fix lockCommand in the configuration, save it, then press Enter)",
        )
    }

    // A locker program unlocked the session: our own lock screen (if it is up) ends with it —
    // if that locker is one the compositor started (lock(): Enter on our lock screen). Any
    // other program could lock and unlock at once, without asking for a password.
    private fun lockerUnlocked(lock: dev.caulis.core.SessionLock) {
        val own = fallbackLock ?: return
        val pid = lock.resource.client.pid
        if (!launcher.descendsFrom(pid, lockerPids)) {
            log.warn(
                "${lock.resource.client.name} locked and unlocked, but it is not the locker the compositor started: " +
                    "the compositor's own lock screen stays (press Enter on it to start ${Launcher.programName(config.lockCommand)})",
            )
            return
        }
        fallbackLock = null
        if (desktop.lockScreen === own) desktop.unlockInternally()
        log.info { "The locker unlocked: the compositor's own lock screen is gone too" }
    }

    fun spawn(command: List<String>, directory: java.io.File?): Process? =
        launcher.spawn(command, directory)?.also { if (startingLocker) lockerPids += it.pid() }

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
            if (config.hotReload) reloader = safely("hot reload", null) { Reloader.start(this) }
            log.info { "Your files (shaders, pictures): ${config.configFolder}${if (java.nio.file.Files.isDirectory(config.configFolder)) "" else " (not there yet)"}" }
            watchTheStart()
            loop.run()
        } finally {
            config.running = null
        }
    }

    // How the start went (the longest the loop was busy), and the memory the start freed given back.
    private fun watchTheStart() {
        loop.stallMillis = config.stallReportMillis
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
