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

    // Monitors switched off because the configuration says enabled = false.
    private val disabledByConfig = config.monitorSettings.values.filter { !it.enabled }.mapTo(HashSet()) { it.name }

    private val log = Logger("config")
    private val clientsLog = Logger("clients")
    private val cursorLog = Logger("cursor")
    private val gesturesLog = Logger("gestures")

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
    private val configNotices = ArrayList<dev.caulis.core.Notice>()

    val display = Display(loop)
    private val keymap = Keymap.fromNames(layouts = config.keyboardLayouts, variants = config.keyboardVariants, options = config.keyboardOptions, model = config.keyboardModel)
    val seat = Seat(display, keymap)
    private val compositor = Compositor(display, renderer)
    val desktop = Desktop(renderer, seat, compositor, width, height)

    /** logind (on the screen): sleep and wake, lock requests, brightness. Null elsewhere. */
    internal val logind: dev.caulis.core.Logind? = if (switchConsole != null) dev.caulis.core.Logind.connect(loop) else null

    /** The backend wakes its monitors after sleep (set by the screen backend). */
    var wakeMonitors: () -> Unit = {}

    /** A laptop's keys: volume, microphone, brightness, touchpad, music players. */
    internal val system = SystemControls(this)

    /** Numbers for windows (the IPC socket, scripts). */
    val windowIds = WindowIds(desktop, loop)
    private val pointerGestures = PointerGestures(display, seat)

    /** Socket name programs connect to, e.g. "wayland-1". */
    val socket: String

    val keys = KeyBindings()

    // The only keys of ours while the screen is locked; everything else goes to the locker.
    private val lockedKeys = KeyBindings()

    // The keys that still work while a window keeps the others (a nested compositor, a VM).
    private val inhibitedKeys = KeyBindings()

    // The keys of each key mode (submap), and the mode that is on.
    private val modeKeys = HashMap<String, KeyBindings>()

    /** The key mode that is on (null: the usual keys). */
    var keyMode: String? = null
        private set

    /** Keyboards, mice and touchpads (on the screen only; nested, the outer session has them). */
    var inputDevices: dev.caulis.core.libinput.InputDevices? = null
        set(value) {
            field = value
            value?.settings = config.input
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
    lateinit var clipboard: DataDeviceManager
        private set

    /** Windows asking for the focus (xdg-activation); set in init. */
    lateinit var activation: dev.caulis.core.Activation
        private set

    /** Idle timers (ext-idle-notify, onIdle); set in init. */
    lateinit var idle: dev.caulis.core.Idle
        private set

    // When screens were last switched off (input right after it does not wake them).
    private var screensOffAt = 0L

    // Someone did something: idle timers start over, and dark screens light up again.
    private fun userActive() {
        idle.activity()
        if (config.wakeScreensOnInput && System.nanoTime() - screensOffAt > 1_000_000_000L) {
            for (monitor in desktop.monitors) if (!monitor.powered) desktop.setPower(monitor, true)
        }
    }

    /** Switches the screens off (or on) — all of them, or the monitor [name]; false if one could not. */
    fun setScreens(on: Boolean, name: String? = null): Boolean {
        if (!on) screensOffAt = System.nanoTime()
        return desktop.monitors.filter { name == null || it.name.equals(name, ignoreCase = true) }.map { desktop.setPower(it, on) }.all { it }
    }

    /** Programs that arrange monitors (kanshi, wlr-randr); set in init. */
    lateinit var outputManagement: dev.caulis.core.OutputManagement
        private set

    // The configuration's own idle timers (replaced on a reload).
    private val idleWatches = ArrayList<AutoCloseable>()

    // --- light and dark ---

    /** What programs are told about how to look (light or dark, the accent colour). */
    val appearance = dev.caulis.core.appearance.Appearance()
    private val colorSchemes = ColorSchemes()
    private var colorSchemeTimer: EventLoop.Timer? = null
    // Telling programs: the portal backend (on the session bus) and gsettings. Only while wanted.
    private var sessionBus: dev.caulis.core.dbus.DBusConnection? = null
    private var portalSettings: dev.caulis.core.appearance.PortalSettings? = null
    private var gsettings: dev.caulis.core.appearance.GSettingsAppearance? = null
    private val appearanceLog = Logger("appearance")

    /** The configuration's scheme, schedule, accent and GTK themes, applied (again). */
    fun applyAppearance() {
        val config = config
        val toPrograms = config.appearanceToPrograms ?: (switchConsole != null)
        if (toPrograms && gsettings == null) {
            appearanceLog.info { "Programs are told the colour scheme (gsettings and the portal)" }
            gsettings = dev.caulis.core.appearance.GSettingsAppearance(appearance).also { if (appearance.colorScheme != dev.caulis.core.appearance.ColorScheme.DEFAULT) it.write() }
            sessionBus = dev.caulis.core.dbus.DBusConnection.session(loop)
            portalSettings = sessionBus?.let { dev.caulis.core.appearance.PortalSettings(it, appearance) }
        } else if (!toPrograms && gsettings != null) {
            stopTellingPrograms()
        }
        gsettings?.gtkThemes = config.gtkThemes
        appearance.accentColor = config.accentColor
        if (colorSchemes.base != config.colorScheme) {
            colorSchemes.base = config.colorScheme
            colorSchemes.switch(null, java.time.ZonedDateTime.now()) // (set anew: it wins over one by hand)
        }
        colorSchemes.schedule = config.colorSchedule
        updateColorScheme()
    }

    /** Light or dark by hand until the schedule's next change (null: back to the schedule). */
    fun switchColorScheme(scheme: dev.caulis.core.appearance.ColorScheme?) {
        appearanceLog.info { "Colour scheme by hand: ${scheme?.name?.lowercase() ?: "back to the schedule"}" }
        colorSchemes.switch(scheme, java.time.ZonedDateTime.now())
        updateColorScheme()
    }

    // The scheme of now, told; looked at again when the schedule changes it (at least hourly:
    // the clock may be set, or the laptop asleep).
    private fun updateColorScheme() {
        colorSchemeTimer?.cancel()
        colorSchemeTimer = null
        val now = java.time.ZonedDateTime.now()
        val (scheme, next) = colorSchemes.at(now)
        if (appearance.colorScheme != scheme) {
            appearance.colorScheme = scheme
            ipc?.colorSchemeChanged(scheme)
            for (action in config.colorSchemeListeners.toList()) safely("onColorSchemeChanged") { action(config, scheme) }
        }
        if (next != null) {
            val millis = java.time.Duration.between(now, next).toMillis().coerceIn(1_000, 3_600_000)
            appearanceLog.trace { "The colour scheme is looked at again in ${millis / 1000} s" }
            colorSchemeTimer = loop.after(millis) { safely("the colour schedule") { updateColorScheme() } }
        }
    }

    private fun stopTellingPrograms() {
        portalSettings?.close()
        portalSettings = null
        sessionBus?.close()
        sessionBus = null
        gsettings?.close()
        gsettings = null
    }

    // keyboard-shortcuts-inhibit (set in init).
    private lateinit var shortcutsInhibit: dev.caulis.core.input.ShortcutsInhibit

    // Takes on changed code while running (null when off or not started from a project).
    private var reloader: Reloader? = null

    // Which rules (by what they say) each window got its placement from already: a rule is
    // applied once, when the window opens or first fits it.
    private val rulesApplied = java.util.WeakHashMap<dev.caulis.core.Window, MutableSet<String>>()

    // The margins each window was arranged with last (a rule may change them with its title).
    private val shownMargins = java.util.WeakHashMap<dev.caulis.core.Window, dev.caulis.core.Margins?>()

    // A window's margins: the last rule that fits and says.
    private fun marginsOf(window: dev.caulis.core.Window): dev.caulis.core.Margins? =
        config.rules.lastOrNull { it.margins != null && fits(it, window) }?.margins

    // The title bars each window was last told (a rule may change them with its title).
    private val shownTitleBars = java.util.WeakHashMap<dev.caulis.core.Window, dev.caulis.core.TitleBars?>()

    private val rulesLog = Logger("windows")

    private fun fits(rule: WindowRule, window: dev.caulis.core.Window): Boolean = safely("$rule", false) { rule.fits(window, factsOf(window)) }

    // Tags given by hand (the action `tag`), and each window's app id and title when it opened.
    private val manualTags = java.util.WeakHashMap<dev.caulis.core.Window, MutableSet<String>>()
    private val initials = java.util.WeakHashMap<dev.caulis.core.Window, Pair<String, String>>()

    // What rules may ask about [window]: its tags are those given by hand, then those of each
    // rule in turn that fits it (a rule sees the tags of the ones before).
    private fun factsOf(window: dev.caulis.core.Window, rules: List<WindowRule> = config.rules): WindowRule.Facts {
        val (appId, title) = initials.getOrPut(window) { window.appId to window.title }
        val tags = LinkedHashSet(manualTags[window].orEmpty())
        for (rule in rules) {
            if (rule.tags.isEmpty() || rule.tags.all { it in tags }) continue
            if (safely("$rule", false) { rule.fits(window, WindowRule.Facts(desktop, tags, appId, title)) }) tags += rule.tags
        }
        return WindowRule.Facts(desktop, tags, appId, title)
    }

    /** [window]'s tags now: given by hand and by the rules that fit it. */
    fun tagsOf(window: dev.caulis.core.Window): Set<String> = factsOf(window).tags

    /** Gives [window] the tag [name] (true), takes it away (false), or toggles it (null). */
    fun tag(window: dev.caulis.core.Window, name: String, on: Boolean? = null) {
        val own = manualTags.getOrPut(window) { LinkedHashSet() }
        val give = on ?: (name !in own)
        if (give) own += name else own -= name
        rulesLog.info { "$window: tag $name ${if (give) "given" else "taken away"}" }
        rulesChanged(window)
    }

    // A window's tags or state changed: its rules' margins and title bar may be others now.
    private fun rulesChanged(window: dev.caulis.core.Window) {
        val margins = marginsOf(window)
        if (shownMargins.put(window, margins) != margins) desktop.marginsChanged(window)
        val bars = titleBarsOf(window)
        if (shownTitleBars.put(window, bars) != bars) desktop.titleBarsChanged()
        desktop.requestRedraw()
    }

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

    /** The X11 display programs reach Xwayland at (DISPLAY), or null. */
    val x11Display: String? get() = xwayland?.displayName

    // The IPC socket (null if turned off). Declared before init, which sets it.
    private var ipc: IpcServer? = null

    // Starts programs: Wayland first, X11 when that fails.
    private val launcher = Launcher(
        environment = { env ->
            for ((name, value) in config.environment) if (value == null) env.remove(name) else env[name] = value
            env["WAYLAND_DISPLAY"] = socket
            // A good activation token: its first window may take the focus.
            env["XDG_ACTIVATION_TOKEN"] = activation.issue()
            ipc?.let { env[dev.caulis.ipc.CaulisIpc.SOCKET_VARIABLE] = it.path.toString() } ?: env.remove(dev.caulis.ipc.CaulisIpc.SOCKET_VARIABLE)
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
        clipboard = DataDeviceManager(display, seat)
        dev.caulis.core.PrimarySelection(display, seat, clipboard)
        dev.caulis.core.DataControl(display, clipboard)
        activation = dev.caulis.core.Activation(display, seat, desktop)
        logind?.let { l ->
            l.onSleep = { ready -> goingToSleep(ready) }
            l.onWake = ::wokeUp
            l.onLock = { lock -> if (lock && !desktop.locked) safely("lock") { config.lock() } }
        }
        idle = dev.caulis.core.Idle(display, loop, desktop)
        desktop.onUserActivity = ::userActive
        desktop.pointerConstraints = dev.caulis.core.input.PointerConstraints(display, seat)
        dev.caulis.core.OutputPower(display, desktop)
        dev.caulis.core.GammaControl(display, desktop)
        outputManagement = dev.caulis.core.OutputManagement(display, desktop)
        Presentation(display)
        XdgShell(display).listener = desktop
        LayerShell(display).listener = desktop
        SessionLockManager(display).listener = desktop
        CursorShape(display, seat)
        shortcutsInhibit = dev.caulis.core.input.ShortcutsInhibit(display, seat)
        XdgOutputManager(display)
        ServerDecorations(display, desktop)
        xwayland = if (config.xwayland) safely("Xwayland", null) { Xwayland(display, desktop, loop, clipboard, wholeScaleWorlds = config.sharpJavaScaling) } else null
        ScreenCapture(display, desktop, renderer, ForeignToplevels(display, desktop))
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
            config.rules.fold(fx) { now, rule -> if (rule.hasLooks && fits(rule, window)) safely("$rule effects", now) { rule.effectsOf(now) } else now }
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
            // Its margins and title bar too (a rule may fit it now, or no longer).
            rulesChanged(window)
        }
        desktop.behaviorFor = { window ->
            config.rules.fold(dev.caulis.core.WindowBehavior.USUAL) { now, rule -> if (rule.hasBehavior && fits(rule, window)) rule.behaviorOf(now) else now }
        }
        desktop.onWindowOpened { window ->
            shownTitleBars[window] = titleBarsOf(window)
            shownMargins[window] = marginsOf(window)
        }
        desktop.marginsFor = { window -> marginsOf(window) }
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

    // The computer is about to sleep: your onSleep, the screen locked (when asked for), then [ready].
    private fun goingToSleep(ready: () -> Unit) {
        config.sleepActions.toList().forEach { action -> safely("onSleep") { action(config) } }
        if (!config.lockBeforeSleep || desktop.locked) return ready()
        safely("the lock before sleep") { config.lock() }
        // Sleep once the locker shows (or after a while: logind does not wait for ever anyway).
        val since = System.nanoTime()
        fun check() {
            if (desktop.locked || System.nanoTime() - since > 3_000_000_000) ready() else loop.after(50) { check() }
        }
        check()
    }

    // The computer woke up: the monitors are set up anew (sleep may have reset them), the idle
    // timers start over, and your onWake runs.
    private fun wokeUp() {
        safely("waking the monitors") { wakeMonitors() }
        userActive()
        safely("the colour schedule") { updateColorScheme() } // (the clock went on while asleep)
        config.wakeActions.toList().forEach { action -> safely("onWake") { action(config) } }
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
        if (desktop.director !== config.director) desktop.director = config.director
        display.privileged = { client -> safely("privilegedClients", false) { this.config.privilegedClients(client) } }
        desktop.screenShader = config.screenShader
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
        desktop.monitorEdges = config.monitorEdges
        desktop.resizeBorder = config.resizeBorder
        desktop.doubleClickMillis = config.doubleClickMillis
        desktop.lockColor = config.lockColor
        desktop.focusFollowsMouse = config.focusFollowsMouse
        activation.focusOnActivate = config.focusOnActivate
        idle.fullscreenInhibits = config.fullscreenKeepsAwake
        idleWatches.forEach(AutoCloseable::close)
        idleWatches.clear()
        for (watch in config.idleWatches) {
            idleWatches += idle.watch(watch.seconds * 1000L, onIdle = { safely("onIdle") { watch.idle(config) } }, onResume = { safely("onIdle resume") { watch.resume(config) } })
        }
        seat.repeatRate = config.repeatRate
        seat.repeatDelay = config.repeatDelay
        applyAppearance()
        inputDevices?.settings = config.input
        desktop.workspaceCount = config.workspaceCount
        desktop.workspaceRules = config.workspaceRules
        desktop.primaryMonitorName = config.primaryMonitor

        for (pad in config.scratchpads) {
            // A scratchpad asks the configuration of the moment which windows are its own.
            if (desktop.scratchpad(pad.name) == null) {
                desktop.addScratchpad(pad.name, pad.style) { window ->
                    val now = this.config.scratchpads.firstOrNull { it.name == pad.name } ?: return@addScratchpad false
                    safely("scratchpad ${pad.name}", false) { now.match(window) }
                }
            }
        }
        rebindKeys()

        rewireEvents()

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
        // Quitting and text consoles also work while the screen is locked (when they are on).
        val usual = config.keys
        for (bindings in listOf(keys, lockedKeys, inhibitedKeys)) {
            usual.quit?.let { bindings.bind(it) { quit() } }
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
        }
        modeKeys.clear()
        for (binding in config.bindings) {
            if (!binding.enabled) continue
            val combination = binding.combination
            val run = { safely(combination) { binding.action(config) } }
            val mode = binding.mode
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
        if (keyMode != null && keyMode !in modeKeys) enterMode(null)
        // Combinations that repeat use the loop and the key repeat of the moment.
        for (bindings in allKeys()) {
            bindings.schedule = { millis, task -> loop.after(millis) { task() }.let { timer -> AutoCloseable { timer.cancel() } } }
            bindings.repeatDelay = config.repeatDelay
            bindings.repeatRate = config.repeatRate
        }
    }

    fun quit() = loop.stop()

    // config.json saved again: main() runs once more (in this program, as it is) and hands over
    // its configuration with the file on top; that one is taken on.
    private fun followConfigFile() {
        for (name in listOf("config.json") + dev.caulis.wm.config.ConfigFile.languageFiles.keys) followConfigFile(config.file(name))
    }

    // Takes the configuration on again when [path] changes.
    private fun followConfigFile(path: java.nio.file.Path) {
        configWatches += dev.caulis.core.FileWatch.watch(path) {
            // main() (and with it config.lua, config.kts: compiling takes seconds) runs on a
            // thread of its own: the desktop goes on meanwhile. One reload at a time.
            configReloads.execute {
                val next = try {
                    Reloader.rerunMain()
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError) throw e
                    log.warn("The configuration could not be read again: ${e.message ?: e}")
                    null
                }
                loop.post { takeOnConfig(next, path) }
            }
        }
    }

    // On the compositor's thread: [next] (or the files once more onto the configuration as it
    // is, when main() cannot be run again) replaces the configuration — or, if that fails, the
    // one before keeps running.
    private fun takeOnConfig(next: Caulis?, path: java.nio.file.Path) {
        val before = config
        try {
            if (next == null) {
                config.configProblems = dev.caulis.wm.config.ConfigFile.applyTo(config)
                reconfigure(config)
            } else {
                reconfigure(next)
            }
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.warn("Taking on ${path.fileName} failed, back to the one before: $e")
            runCatching { reconfigure(before) }
            desktop.notify("$e", NoticeLevel.WARNING, "Your configuration failed — the one before keeps running")
            return
        }
        log.info { "${path.fileName} taken on" }
        desktop.notify(path.fileName.toString(), NoticeLevel.INFO, "Configuration reloaded")
    }

    // Config files followed, and the thread that reads them again.
    private val configWatches = ArrayList<AutoCloseable>()
    private val configReloads = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "caulis-config-reload").apply { isDaemon = true }
    }

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
            val facts = factsOf(window, next.rules)
            rulesApplied.getOrPut(window) { HashSet() } += next.rules.filter { it.hasPlacement && safely("$it", false) { it.fits(window, facts) } }.map { it.signature }
        }
        // (The same configuration when config.json was applied onto it: nothing to compare.)
        val same = before === next
        before.running = null
        next.running = this
        config = next
        next.applyLogSettings()
        applyConfig()
        if (desktop.layout != next.layout) desktop.layout = next.layout
        if (same || before.scale != next.scale || before.monitorSettings.mapValues { it.value.scale } != next.monitorSettings.mapValues { it.value.scale }) applyScale()
        // A turn taken out of the configuration: the monitor stands as it is again.
        for (monitor in desktop.monitors) {
            if (before.monitorSettings[monitor.name]?.transform != null && next.monitorSettings[monitor.name]?.transform == null) {
                desktop.setTransform(monitor, dev.caulis.core.Transform.NORMAL)
            }
        }
        applyMonitorSettings()
        applyCursor()
        // Margins of the new rules: windows take them at once.
        for (window in desktop.allWindows) {
            val margins = marginsOf(window)
            if (shownMargins.put(window, margins) != margins) desktop.marginsChanged(window)
        }
        // Only now that the new one runs, the one that goes hears it (to stop what it started)
        // and its timers end: a new one that fails on the way leaves the old one whole.
        if (!same) {
            before.unloadActions.forEach { action -> safely("onUnload") { action(before) } }
            before.timers.forEach { it.cancel() }
            before.timers.clear()
            before.added.toList().forEach { it.close() }
            before.added.clear()
            before.waitingTimers.forEach { it.close() }
        }
        next.startWaitingTimers()
        next.reloadActions.forEach { action -> safely("onReload") { action(next) } }
        ipc?.configReloaded(next.configProblems)
        return restartOnly to notes
    }

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
    internal fun <T> safely(what: String, fallback: T, action: () -> T): T = try {
        action()
    } catch (e: Throwable) {
        e.rethrowIfFatal()
        log.error("Your code for \"$what\" failed (ignored): $e", e)
        fallback
    }

    internal fun safely(what: String, action: () -> Unit) = safely(what, Unit, action)

    /** A key from the keyboard: the compositor's combination, or passed to the focused program. */
    /** The pointer moved by [dx], [dy] (units; raw: the mouse's own movement) — onPointerMotion first. */
    fun pointerMovedBy(timeMillis: Int, dx: Double, dy: Double, rawDx: Double = dx, rawDy: Double = dy) {
        if (motionTaken(dx, dy)) return desktop.onUserActivity()
        desktop.pointerMovedBy(timeMillis, dx, dy, rawDx, rawDy)
    }

    /** The pointer is at [x], [y] (units: nested, tablets) — onPointerMotion first, with the movement. */
    fun pointerMovedTo(timeMillis: Int, x: Double, y: Double) {
        if (motionTaken(x - desktop.pointerX, y - desktop.pointerY)) return desktop.onUserActivity()
        desktop.pointerMoved(timeMillis, x, y)
    }

    private fun motionTaken(dx: Double, dy: Double): Boolean =
        !desktop.locked && config.motionHandlers.isNotEmpty() && (dx != 0.0 || dy != 0.0) &&
            config.motionHandlers.asReversed().any { h -> safely("onPointerMotion", false) { h(config, dx, dy) } }

    // Modifiers held now (KeyInput.Modifiers bits), for onKey.
    private var modifiersHeld = 0
    private val modifierKeysDown = HashSet<Int>()

    // Asks the configuration's onKey handlers (last added first); true if one took the key.
    private fun keyTaken(timeMillis: Int, evdevCode: Int, pressed: Boolean): Boolean {
        val before = modifiersHeld
        val bit = KeyInput.Modifiers.of(evdevCode)
        if (bit != 0) {
            if (pressed) modifierKeysDown += evdevCode else modifierKeysDown -= evdevCode
            modifiersHeld = modifierKeysDown.fold(0) { m, code -> m or KeyInput.Modifiers.of(code) }
        }
        if (config.keyHandlers.isEmpty()) return false
        val keysym = seat.keysymName(evdevCode)
        val locked = desktop.locked
        if (locked && !keysym.startsWith("XF86")) return false // (never what is typed into the locker)
        val input = KeyInput(evdevCode, pressed, keysym, modifiersHeld, before, locked, timeMillis)
        return config.keyHandlers.asReversed().any { handler -> safely("onKey", false) { handler(config, input) } }
    }

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        userActive()
        if (keyTaken(timeMillis, evdevCode, pressed)) {
            // (The seat's own keyboard state still follows modifiers, or they would stick.)
            if (KeyInput.Modifiers.of(evdevCode) != 0) seat.key(timeMillis, evdevCode, pressed)
            return
        }
        val active = activeKeys()
        // A key pressed before locking may be released after it (Super+L): all must see the release.
        if (!pressed) for (other in allKeys()) if (other !== active) other.key(evdevCode, false)
        if (!active.key(evdevCode, pressed)) {
            // A special key (a laptop's Fn keys, media keys) nobody binds: said once, with its
            // name, so it can be bound. Never a typing key, never while the screen is locked.
            if (pressed && evdevCode >= FIRST_SPECIAL_KEY && evdevCode !in 0x100..0x15f && !desktop.locked && unboundSeen.add(evdevCode)) {
                val name = KeyBindings.nameOf(evdevCode)
                log.info { "The key ${name ?: "with code $evdevCode"} is not bound${name?.let { ": bind(\"$it\") { ... } binds it" } ?: ""}" }
            }
            desktop.key(timeMillis, evdevCode, pressed)
        }
    }

    // Special keys said once already (see key).
    private val unboundSeen = HashSet<Int>()

    // The keys that are the compositor's now.
    private fun activeKeys(): KeyBindings = when {
        desktop.locked -> lockedKeys
        // A window keeps the keys (a nested compositor): only quit, consoles and restoreShortcuts are ours.
        shortcutsInhibit.active -> inhibitedKeys
        // An overlay of the compositor's own (a screenshot chooser) has the keys: only quit and consoles stay.
        desktop.overlay != null -> inhibitedKeys
        else -> keyMode?.let(modeKeys::get) ?: keys
    }

    private fun allKeys(): List<KeyBindings> = listOf(keys, lockedKeys, inhibitedKeys) + modeKeys.values

    /** Forgets every key held (the keyboard went to another session or terminal). */
    fun releaseKeys() {
        for (bindings in allKeys()) bindings.releaseAll()
    }

    /** Turns the key mode [name] on; null: the usual keys again. */
    fun enterMode(name: String?) {
        val wanted = name?.takeIf { it != "reset" && it != "default" }
        require(wanted == null || wanted in modeKeys) { "there is no key mode \"$name\" (there are: ${modeKeys.keys.joinToString().ifEmpty { "none" }})" }
        if (wanted == keyMode) return
        keyMode = wanted
        log.info { if (wanted == null) "The usual keys again" else "Key mode \"$wanted\"" }
        ipc?.keyModeChanged(wanted)
    }

    // The wheel's steps, for combinations with it (hi-res wheels send parts of a step).
    private var wheelSteps = doubleArrayOf(0.0, 0.0)

    /** The mouse wheel or a touchpad scrolled: a combination with the wheel, or the program under the pointer. */
    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) {
        userActive()
        if (!desktop.locked && config.scrollHandlers.asReversed().any { h -> safely("onScroll", false) { h(config, vertical, value, value120 / 120.0) } }) return
        val active = activeKeys()
        if (value120 != 0 && !desktop.locked) {
            val (back, forth) = if (vertical) KeyBindings.WHEEL_UP to KeyBindings.WHEEL_DOWN else KeyBindings.WHEEL_LEFT to KeyBindings.WHEEL_RIGHT
            val code = if (value120 < 0) back else forth
            if (active.has(code)) {
                val axis = if (vertical) 0 else 1
                wheelSteps[axis] += value120 / 120.0
                while (Math.abs(wheelSteps[axis]) >= 1.0) {
                    active.wheel(code)
                    wheelSteps[axis] -= Math.signum(wheelSteps[axis])
                }
                return
            }
        }
        active.interrupted()
        desktop.pointerAxis(timeMillis, vertical, value, value120)
    }

    /**
     * A mouse button: Super + left button drags a window, Super + right button resizes it
     * (from the nearest corner). Everything else goes to the program under the pointer.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        userActive()
        if (!desktop.locked && config.buttonHandlers.asReversed().any { h -> safely("onPointerButton", false) { h(config, button, pressed) } }) return
        // A combination with a mouse button ("Super+MouseSide", "MouseBack").
        val active = activeKeys()
        if (!pressed) for (other in allKeys()) if (other !== active) other.key(button, false)
        if (active.key(button, pressed)) return
        if (pressed && !desktop.locked && desktop.overlay == null && dragging() && (button == BTN_LEFT || button == BTN_RIGHT)) {
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
    private enum class GestureUse { NONE, DRAG, SWIPE, PINCH, PROGRAM, CLAIMED }

    // The configuration's handler that took the gesture going on (onGesture).
    private var gestureClaimedBy: (Caulis.(Gesture) -> Boolean)? = null

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
        userActive()
        gesturesLog.trace { "$gesture (fingers ${gestureFingers}, use $gestureUse)" }
        val begins = gesture is Gesture.SwipeBegin || gesture is Gesture.PinchBegin || gesture is Gesture.HoldBegin
        val ends = gesture is Gesture.SwipeEnd || gesture is Gesture.PinchEnd || gesture is Gesture.HoldEnd
        // A gesture of the configuration's own: asked at its start, then it has all of it.
        if (begins && !desktop.locked) {
            gestureClaimedBy = config.gestureHandlers.asReversed().firstOrNull { handler -> safely("onGesture", false) { handler(config, gesture) } }
            if (gestureClaimedBy != null) {
                gesturesLog.info { "$gesture: taken by onGesture" }
                gestureUse = GestureUse.CLAIMED
                return@safely
            }
        }
        if (gestureUse == GestureUse.CLAIMED) {
            gestureClaimedBy?.let { handler -> safely("onGesture", false) { handler(config, gesture) } }
            if (ends) {
                gestureUse = GestureUse.NONE
                gestureClaimedBy = null
            }
            return@safely
        }
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
            for (problem in config.configProblems) configNotices += desktop.notify(problem, NoticeLevel.WARNING)
            if (config.useConfigFile) followConfigFile()
            config.startActions.forEach { action -> safely("onStart") { action(config) } }
            config.startWaitingTimers()
            if (config.hotReload) reloader = safely("hot reload", null) { Reloader.start(this) }
            log.info { "Your files (shaders, pictures): ${config.configFolder}${if (java.nio.file.Files.isDirectory(config.configFolder)) "" else " (not there yet)"}" }
            loop.run()
        } finally {
            config.running = null
        }
    }

    override fun close() {
        colorSchemeTimer?.cancel()
        stopTellingPrograms()
        logind?.close()
        displays?.close()
        configWatches.forEach(AutoCloseable::close)
        system.close()
        configReloads.shutdownNow()
        ipc?.close()
        reloader?.close()
        logNotices.close()
        xwayland?.close()
        desktop.close()
        seat.close()
        keymap.close()
        display.close()
    }

    private companion object {

        /** How long a scratchpad's program may take to show a window before a key press starts it again. */
        const val STARTING_NANOS = 5_000_000_000L
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111

        // From here on, keys are no typing keys (media, laptop, F13..F24); 0x100..0x15f are buttons.
        const val FIRST_SPECIAL_KEY = 113

        // Keys that always exist when a configuration has none at all.
        const val EMERGENCY_QUIT = "Super+Shift+Escape"
        const val EMERGENCY_TERMINAL = "Super+Return"
    }
}

/** Milliseconds on the clock Wayland and libinput timestamps use. */
internal fun now(): Int = (System.nanoTime() / 1_000_000).toInt()
