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
import dev.caulis.core.input.PenPointer
import dev.caulis.core.input.Seat
import dev.caulis.core.input.Tablet
import dev.caulis.core.input.TabletAxes
import dev.caulis.core.input.TabletPad
import dev.caulis.core.input.TabletTool
import dev.caulis.core.input.Tablets
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

    /** False once it was closed (a configuration a reload replaced no longer reaches it then). */
    @Volatile
    var isOpen = true
        private set

    /** True while the configuration's onStart runs: what it starts is the session's (see Caulis.onStart). */
    var startingUp = false
        private set

    /** Widgets, drawings and the like the session keeps (from onStart): closed when the compositor ends. */
    val sessionResources = ArrayList<AutoCloseable>()

    // Keys and listeners added in onStart (or by a replaced configuration), by the index of
    // their list in Caulis.addableLists: each new configuration gets them too.
    private val keptItems = ArrayList<Pair<Int, Any?>>()

    /** Keeps [item] (in list [index] of Caulis.addableLists) in every configuration from now on. */
    fun keepThroughReloads(index: Int, item: Any?) {
        keptItems += index to item
        log.trace { "Kept through reloads: ${item?.javaClass?.simpleName}" }
    }

    /** No longer keeps [item] (it was taken away). */
    fun forget(item: Any?) {
        keptItems.removeIf { it.second === item }
    }

    // The buses as the session uses them (onStart, replaced configurations): never taken away.
    private val sessionScopes = HashMap<Boolean, dev.caulis.core.dbus.DBusScope>()

    /** The session's view of the session bus (true) or the system bus (false), or null if there is none. */
    fun sessionScope(session: Boolean): dev.caulis.core.dbus.DBusScope? = sessionScopes[session] ?: run {
        val connection = (if (session) sessionBus else systemBus) ?: return null
        dev.caulis.core.dbus.DBusScope(connection).also { sessionScopes[session] = it }
    }

    // Monitors switched off because the configuration says enabled = false.
    private val disabledByConfig = config.monitorSettings.values.filter { !it.enabled }.mapTo(HashSet()) { it.name }

    private val log = Logger("config")
    private val keyboardLog = Logger("keyboard")
    private val clientsLog = Logger("clients")
    private val cursorLog = Logger("cursor")
    private val gesturesLog = Logger("gestures")
    private val inputLog = Logger("input")

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
    // The keymap and the settings it was made of (made anew when they change: a reload).
    private var keymapOf = listOf(config.keyboardLayouts, config.keyboardVariants, config.keyboardOptions, config.keyboardModel)
    // (The seat owns the keymap: it closes the one it replaces, and its last one.)
    val seat = Seat(display, Keymap.fromNames(layouts = config.keyboardLayouts, variants = config.keyboardVariants, options = config.keyboardOptions, model = config.keyboardModel))
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

    /** Drawing tablets for programs that understand pens (tablet-v2). */
    val tablets = Tablets(display, seat)

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
            value?.let(::useTablets)
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
        if (config.wakeScreensOnInput && System.nanoTime() - screensOffAt > config.wakeScreensAfterMillis * 1_000_000L) {
            for (monitor in desktop.monitors) if (!monitor.powered) desktop.setPower(monitor, true)
        }
    }

    // --- drawing tablets ----------------------------------------------------------------------

    // The tablets, pens and pads of [devices]: places on the desktop as each tablet's settings
    // say; the pen goes to programs as a pen or as the pointer (the desktop decides), pads to
    // the program with the keyboard or to a binding ("Pad1").
    private fun useTablets(devices: dev.caulis.core.libinput.InputDevices) {
        devices.onTabletAdded = tablets::add
        devices.onTabletRemoved = tablets::remove
        devices.onPadAdded = tablets::addPad
        devices.onPadRemoved = tablets::removePad
        devices.onTabletProximity = { time, tablet, tool, x, y, axes, near ->
            val (dx, dy) = tablet.place(x, y, tabletArea(tablet))
            desktop.tabletProximity(time, tablet, tool, dx, dy, asLaid(tablet, axes), near)
        }
        devices.onTabletMotion = { time, tablet, tool, x, y, axes ->
            val (dx, dy) = tablet.place(x, y, tabletArea(tablet))
            desktop.tabletMotion(time, tool, dx, dy, asLaid(tablet, axes))
        }
        devices.onTabletTip = { time, _, tool, down -> desktop.tabletTip(time, tool, down) }
        devices.onTabletButton = { time, _, tool, button, pressed -> desktop.tabletButton(time, tool, button, pressed) }
        devices.onPadButton = ::padButton
        devices.onPadRing = { time, pad, ring, degrees, finger ->
            userActive()
            tablets.padRing(time, pad, ring, degrees, finger)
        }
        devices.onPadStrip = { time, pad, strip, position, finger ->
            userActive()
            tablets.padStrip(time, pad, strip, position, finger)
        }
        devices.onPadMode = tablets::padMode
        for (device in devices.list) {
            device.tablet?.let(tablets::add)
            device.pad?.let(tablets::addPad)
        }
    }

    // A left-handed tablet lies turned around: its pen tilts the other way.
    private fun asLaid(tablet: Tablet, axes: TabletAxes) = if (tablet.settings.leftHanded == true) axes.turnedAround() else axes

    // Monitors named in tablet settings that are not there (said once each).
    private val missingTabletMonitors = HashSet<String>()

    /**
     * The part of the desktop [tablet] covers: the monitor its settings name; else, for a
     * tablet that is a screen, the laptop's own (or the first); else all monitors as one.
     */
    fun tabletArea(tablet: Tablet): dev.caulis.geometry.Rect {
        val monitors = desktop.monitors
        tablet.settings.monitor?.let { name ->
            monitors.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.area }
            if (missingTabletMonitors.add(name)) inputLog.warn("The tablet ${tablet.name} is set to the monitor $name, which is not there (it covers ${if (tablet.screen) "the laptop's screen" else "all monitors"} meanwhile)")
        }
        if (tablet.screen) return (monitors.firstOrNull { config.isBuiltIn(it.name) } ?: monitors.first()).area
        return monitors.map { it.area }.reduce(dev.caulis.geometry.Rect::union)
    }

    /** A tablet pad's [button] (0 the first): a combination bound to it ("Pad1"), else the program with the keyboard. */
    fun padButton(timeMillis: Int, pad: TabletPad, button: Int, pressed: Boolean) {
        userActive()
        val code = KeyBindings.padButton(button)
        val active = activeKeys()
        if (!pressed) for (other in allKeys()) if (other !== active) other.key(code, false)
        if (active.key(code, pressed)) return
        tablets.padButton(timeMillis, pad, button, pressed)
    }

    // The pretend tablet, pen and pad of the simulate functions (tests).
    internal val testTablet = Tablet("Caulis test tablet", widthMm = 216.0, heightMm = 135.0)
    internal val testPen = TabletTool(TabletTool.Type.PEN, setOf(TabletTool.Capability.PRESSURE, TabletTool.Capability.DISTANCE, TabletTool.Capability.TILT))
    internal val testPad by lazy { TabletPad("Caulis test pad", buttons = 4, tablet = testTablet).also(tablets::addPad) }
    internal var testPenNear = false

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
    private var appliedNumLock: Boolean? = null

    // --- light and dark ---

    /** What programs are told about how to look (light or dark, the accent colour). */
    val appearance = dev.caulis.core.appearance.Appearance()
    private val colorSchemes = ColorSchemes()
    private var colorSchemeTimer: EventLoop.Timer? = null
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

    // Telling programs: the portal backend (on the session bus) and gsettings. Only while wanted.
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

    // What every program started inside gets in its environment (on top of the compositor's
    // own) — Caulis's choices only: the user's [Caulis.environment] goes on top last (see
    // userEnvironment). [token]: a fresh activation token too (for a program started now).
    private fun fillEnvironment(env: MutableMap<String, String>, token: Boolean = true) {
        env["WAYLAND_DISPLAY"] = socket
        // A good activation token: its first window may take the focus.
        if (token) env["XDG_ACTIVATION_TOKEN"] = activation.issue() else env.remove("XDG_ACTIVATION_TOKEN")
        ipc?.let { env[dev.caulis.ipc.CaulisIpc.SOCKET_VARIABLE] = it.path.toString() } ?: env.remove(dev.caulis.ipc.CaulisIpc.SOCKET_VARIABLE)
        env["XDG_CURRENT_DESKTOP"] = config.desktopName
        // (Started from a text console the compositor itself was "tty": its programs are Wayland's.)
        env["XDG_SESSION_TYPE"] = "wayland"
        env["XDG_SESSION_DESKTOP"] = config.desktopName
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
    }

    // The user's own environment ([Caulis.environment]) on top of everything else — except what
    // must be the compositor's for programs to reach it at all.
    private fun userEnvironment(env: MutableMap<String, String>) {
        for ((name, value) in config.environment) {
            if (name in OWN_VARIABLES) continue
            if (value == null) env.remove(name) else env[name] = value
        }
    }
    // systemd's user manager and D-Bus activation get the compositor's environment (the services
    // they start then reach it); both quietly do nothing where they are not.
    private fun exportEnvironment() {
        // (No activation token: it would be handed to services that never use it.)
        val env = HashMap(System.getenv()).also { fillEnvironment(it, token = false) }.also(::userEnvironment)
        val ours = listOf("WAYLAND_DISPLAY", "DISPLAY", "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE", "XDG_SESSION_DESKTOP")
        val names = (ours + config.environment.filterValues { it != null }.keys.filter { it !in OWN_VARIABLES }).distinct().filter { env[it] != null }
        val pairs = names.map { "$it=${env[it]}" }
        val portals = config.portalsToRestart.toList()
        // (On a thread of its own: the commands are waited for, one after the other.)
        Thread({
            fun run(command: List<String>): Boolean = try {
                val process = dev.caulis.ffi.Processes.start(
                    ProcessBuilder(command).also { it.environment().putAll(env) }
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD),
                )
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS).also { if (!it) process.destroyForcibly() } && process.exitValue() == 0
            } catch (e: java.io.IOException) {
                if (dev.caulis.ffi.Processes.isNotFound(e)) log.trace { "${command.first()} is not there" }
                else dev.caulis.ffi.Processes.tellStartFailed(log, command.first(), e)
                false
            }
            run(listOf("systemctl", "--user", "import-environment") + names)
            run(listOf("dbus-update-activation-environment", "--systemd") + pairs)
            log.info { "The session's services know this compositor: ${pairs.joinToString()}" }
            // Portals started before (by the login, by a session before) still have the old
            // environment: a link they open went to no screen (Telegram's links did nothing).
            // Started again, only those running.
            if (portals.isNotEmpty() && staleServices(env["WAYLAND_DISPLAY"])) {
                // (One by one: a portal not installed failed the whole command, and none restarted.)
                val restarted = portals.filter { run(listOf("systemctl", "--user", "try-restart", it)) }
                log.info { "The portals were started again with this compositor's environment (${restarted.joinToString()})" }
            }
        }, "caulis-environment").apply { isDaemon = true }.start()
    }

    // True if xdg-desktop-portal runs with another WAYLAND_DISPLAY than [ours] (or none).
    private fun staleServices(ours: String?): Boolean = try {
        java.nio.file.Files.list(java.nio.file.Path.of("/proc")).use { procs ->
            procs.filter { it.fileName.toString().all(Char::isDigit) }.anyMatch { proc ->
                val name = runCatching { java.nio.file.Files.readString(proc.resolve("comm")).trim() }.getOrNull()
                if (name?.startsWith("xdg-desktop-po") != true) return@anyMatch false // (comm is cut to 15 characters)
                val environ = runCatching { String(java.nio.file.Files.readAllBytes(proc.resolve("environ"))) }.getOrNull() ?: return@anyMatch false
                environ.split('\u0000').none { it == "WAYLAND_DISPLAY=$ours" }
            }
        }
    } catch (_: Exception) {
        false
    }

    /** The environment programs started inside get (WAYLAND_DISPLAY, DISPLAY, the cursor, the user's own, ...). */
    fun programEnvironment(): Map<String, String> = HashMap(System.getenv()).also { fillEnvironment(it) }.also(::userEnvironment)

    // Starts programs: Wayland first, X11 when that fails.
    private val launcher = Launcher(
        environment = { env -> fillEnvironment(env) },
        userEnvironment = ::userEnvironment,
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
        dev.caulis.core.PrimarySelection(display, seat, clipboard)
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
            override fun moved(timeMillis: Int, x: Double, y: Double) = pointerMovedTo(timeMillis, x, y)
            override fun button(timeMillis: Int, button: Int, pressed: Boolean) = pointerButton(timeMillis, button, pressed)
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
        ScreenCapture(display, desktop, renderer, toplevels)
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
        safely("the colour schedule") { updateColorScheme() } // (the clock went on while asleep)
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

    private fun applyConfig() {
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
        desktop.osdHandler = config.osdHandler
        activation.focusOnActivate = config.focusOnActivate
        idle.fullscreenInhibits = config.fullscreenKeepsAwake
        applyIdleWatches()
        applyKeyRepeat()
        applyKeymap()
        applyNumLock()
        applyAppearance()
        inputDevices?.settings = config.input
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
        for (bindings in allKeys()) {
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

    /** Rules changed (one added while running): who draws title bars, and each window's margins and bars. */
    fun rulesChanged() {
        desktop.titleBarsFor = if (config.titleBarRules.isEmpty() && config.rules.none { it.titleBars != null }) null else ::titleBarsOf
        for (window in desktop.allWindows) rulesChanged(window)
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
        if (keyMode != null && keyMode !in modeKeys) enterMode(null)
        // Combinations that repeat use the loop and the key repeat of the moment.
        // Which keys are modifiers: as the keymap says (caps:super, altwin:swap_lalt_lwin), or the physical ones.
        val modifierOf: (Int) -> KeyBindings.Modifier? =
            if (config.bindingModifiers == BindingModifiers.XKB) seat::modifierOfKey else { code -> KeyBindings.Modifier.of(code) }
        for (bindings in allKeys()) {
            bindings.modifierOf = modifierOf
            bindings.schedule = { millis, task -> loop.after(millis) { task() }.let { timer -> AutoCloseable { timer.cancel() } } }
            bindings.repeatDelay = config.repeatDelayMillis.toInt()
            bindings.repeatRate = config.repeatRate
        }
    }

    fun quit() = loop.stop()

    // config.json saved again: main() runs once more (in this program, as it is) and hands over
    // its configuration with the file on top; that one is taken on. Each file is followed where
    // the user's own would be and in the system's folders ($XDG_CONFIG_DIRS, /etc/xdg: where
    // the NixOS module puts it) — made, saved or taken away.
    private fun followConfigFile() {
        for (name in listOf("config.json") + dev.caulis.wm.config.ConfigFile.languageFileNames()) {
            for (path in dev.caulis.wm.config.ConfigFile.places(config, name)) followConfigFile(path)
        }
        log.trace { "Following the configuration files in ${dev.caulis.wm.config.ConfigFile.places(config, "config.json").joinToString { it.parent.toString() }}" }
    }

    // Takes the configuration on again when [path] changes (made, saved, taken away).
    private fun followConfigFile(path: java.nio.file.Path) {
        configWatches += dev.caulis.wm.config.ConfigWatch(path) {
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
                // The files once more onto a copy of what the code said alone: a setting taken
                // out of them goes back to the code's (without the copy: onto this one as it is).
                val fresh = config.codeOnly?.let { code -> code.snapshot().also { it.codeOnly = code } } ?: config
                log.info { "main() cannot be run again: ${path.fileName} applied onto ${if (fresh === config) "the configuration as it is" else "what the code says"}" }
                fresh.configProblems = dev.caulis.wm.config.ConfigFile.applyTo(fresh)
                reconfigure(fresh)
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
            check("backend", before.backend, next.backend)
            check("xwayland", before.xwayland, next.xwayland)
            check("hardwareCursor", before.hardwareCursor, next.hardwareCursor)
            check("directScanout", before.directScanout, next.directScanout)
            check("ipc", before.ipc, next.ipc)
            check("hotReload", before.hotReload, next.hotReload)
            check("useConfigFile", before.useConfigFile, next.useConfigFile)
            check("configName", before.configName, next.configName)
            check("configDir", before.configDir, next.configDir)
            check("headlessMonitors", before.headlessMonitors, next.headlessMonitors)
            check("exportEnvironment", before.exportEnvironment, next.exportEnvironment)
            check("exitAfterSeconds", before.exitAfterSeconds, next.exitAfterSeconds)
            check("nestedIn", before.nestedIn, next.nestedIn)
            check("sharpJavaScaling", before.sharpJavaScaling, next.sharpJavaScaling)
            check("workspaceMode", before.workspaceMode, next.workspaceMode)
            check("nestedMonitors", before.nestedMonitors, next.nestedMonitors)
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
        // Keys and listeners from onStart (and from replaced configurations) are the new one's too.
        if (!same) {
            val lists = next.addableLists()
            for ((index, item) in keptItems) {
                @Suppress("UNCHECKED_CAST")
                val list = lists[index] as MutableList<Any?>
                if (list.none { it === item }) list += item
                if (item is Caulis.Binding) item.owner = next
            }
        }
        before.running = null
        next.running = this
        next.session = this
        config = next
        next.applyLogSettings(before)
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
        !desktop.locked && desktop.overlay == null && config.motionHandlers.isNotEmpty() && (dx != 0.0 || dy != 0.0) &&
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
        if (config.keyHandlers.isEmpty()) {
            handlersSawDown.clear()
            return false
        }
        // (Latin whatever the layout: Super+D on the Russian layout is Super+D, not Super+в.)
        val keysym = seat.shortcutName(evdevCode)
        val locked = desktop.locked
        // The release of a key the handlers saw go down: theirs too, whatever opened since (a
        // lock, a screenshot chooser) — else they thought it held for good (Super pressed,
        // the chooser opened, Super let go: every binding of theirs with Super broke).
        val sawDown = !pressed && handlersSawDown.remove(evdevCode)
        if (!sawDown) {
            if (locked && !keysym.startsWith("XF86")) return false // (never what is typed into the locker)
            // An overlay (a screenshot chooser) has the keys to itself, as it has the mouse; its
            // releases too (else a Ctrl taken here stayed held for it). The laptop's keys go on.
            if (desktop.overlay != null && !keysym.startsWith("XF86")) return false
        } else {
            keyboardLog.trace { "A release for onKey: it saw the key go down" }
        }
        if (pressed) handlersSawDown += evdevCode
        val input = KeyInput(evdevCode, pressed, keysym, modifiersHeld, before, locked, timeMillis)
        return config.keyHandlers.asReversed().any { handler -> safely("onKey", false) { handler(config, input) } }
    }

    // Keys onKey handlers were told went down (and not yet that they were let go).
    private val handlersSawDown = HashSet<Int>()

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        userActive()
        // A key a program saw go down is let go for it too, whoever takes the release (1 pressed
        // in a terminal, then Super: Super+1 took the release, and the terminal typed 1 for ever).
        val programHolds = !pressed && seat.holds(evdevCode)
        if (keyTaken(timeMillis, evdevCode, pressed)) {
            // The keyboard's state still follows the key: modifiers would stick, and a layout
            // switch or Caps Lock on a key the configuration took would do nothing.
            if (KeyInput.Modifiers.of(evdevCode) != 0 || programHolds) seat.key(timeMillis, evdevCode, pressed) else seat.trackKey(evdevCode, pressed)
            // The combinations stop counting it as held too (its press may have reached them:
            // a Super held there for good broke every combination without Super).
            if (!pressed) for (bindings in allKeys()) bindings.forget(evdevCode)
            return
        }
        val active = activeKeys()
        // A key pressed before locking may be released after it (Super+L): all must see the release.
        if (!pressed) for (other in allKeys()) if (other !== active) other.key(evdevCode, false)
        val bound = active.key(evdevCode, pressed)
        if (bound && programHolds) seat.key(timeMillis, evdevCode, false)
        if (!bound) {
            // A special key (a laptop's Fn keys, media keys) nobody binds: said once, with its
            // name, so it can be bound. Never a typing key, never while the screen is locked.
            if (pressed && evdevCode >= FIRST_SPECIAL_KEY && evdevCode !in 0x100..0x15f && !desktop.locked && unboundSeen.add(evdevCode)) {
                val name = KeyBindings.nameOf(evdevCode)
                log.info { "The key ${name ?: "with code $evdevCode"} is not bound${name?.let { ": bind(\"$it\") { ... } binds it" } ?: ""}" }
            }
            desktop.key(timeMillis, evdevCode, pressed)
        }
    }

    // Other layouts, variants, options or model in the settings: the keymap made anew, given
    // to every program (a mistake in them keeps the keymap there was).
    fun applyKeymap() {
        val wanted = listOf(config.keyboardLayouts, config.keyboardVariants, config.keyboardOptions, config.keyboardModel)
        if (wanted == keymapOf) return
        val next = try {
            Keymap.fromNames(layouts = wanted[0], variants = wanted[1], options = wanted[2], model = wanted[3])
        } catch (e: Exception) {
            keyboardLog.warn("The keyboard layouts \"${wanted[0]}\" (options \"${wanted[2]}\") cannot be used: ${e.message}")
            keymapOf = wanted
            return
        }
        seat.setKeymap(next)
        keymapOf = wanted
        keyboardLog.info { "Keyboard layouts: ${wanted[0]}${if (wanted[2].isNotEmpty()) " (${wanted[2]})" else ""}" }
    }

    // Special keys said once already (see key).
    private val unboundSeen = HashSet<Int>()

    // The keys that are the compositor's now.
    internal fun activeKeys(): KeyBindings = when {
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
        // onKey handlers hear the releases of what they saw go down (they keep their own count of
        // what is held); then nothing counts as held for them.
        val now = (System.nanoTime() / 1_000_000).toInt()
        for (code in handlersSawDown.toList()) keyTaken(now, code, false)
        handlersSawDown.clear()
        modifierKeysDown.clear()
        modifiersHeld = 0
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
    fun pointerAxis(timeMillis: Int, scroll: dev.caulis.core.input.Scroll) {
        userActive()
        // Each direction on its own for the handlers and wheel combinations; what they leave
        // goes to the program under the pointer (both directions still in one scroll).
        val vertical = scroll.verticalOnly()?.takeIf { !pointerAxisTaken(true, it.vertical, it.vertical120) }
        val horizontal = scroll.horizontalOnly()?.takeIf { !pointerAxisTaken(false, it.horizontal, it.horizontal120) }
        if (vertical == null && horizontal == null) return
        activeKeys().interrupted()
        desktop.pointerAxis(timeMillis, if (vertical != null && horizontal != null) scroll else vertical ?: horizontal!!)
    }

    // A scroll handler or a wheel combination takes this direction of a scroll.
    private fun pointerAxisTaken(vertical: Boolean, value: Double, value120: Int): Boolean {
        // (An overlay — a screenshot chooser — has the mouse to itself: no handler takes it first.)
        if (!desktop.locked && desktop.overlay == null && config.scrollHandlers.asReversed().any { h -> safely("onScroll", false) { h(config, vertical, value, value120 / 120.0) } }) return true
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
                return true
            }
        }
        return false
    }

    /**
     * A mouse button: Super + left button drags a window, Super + right button resizes it
     * (from the nearest corner). Everything else goes to the program under the pointer.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
        userActive()
        // (An overlay — a screenshot chooser — has the mouse to itself: no handler takes it first.)
        if (!desktop.locked && desktop.overlay == null && config.buttonHandlers.asReversed().any { h -> safely("onPointerButton", false) { h(config, button, pressed) } }) return
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
            if (config.useConfigFile) followConfigFile()
            if (config.exportEnvironment ?: (switchConsole != null)) safely("handing the environment to the session") { exportEnvironment() }
            // The start: dark until warm, the sound, the programs of autostart (see SessionStart).
            sessionStart.begin()
            // What onStart starts is the session's: it stays through reloads (see Caulis.onStart).
            startingUp = true
            try {
                config.startActions.forEach { action -> safely("onStart") { action(config) } }
            } finally {
                startingUp = false
            }
            if (keptItems.isNotEmpty()) log.info { "onStart added ${keptItems.size} keys and listeners: they stay through reloads" }
            config.startWaitingTimers()
            if (config.hotReload) reloader = safely("hot reload", null) { Reloader.start(this) }
            log.info { "Your files (shaders, pictures): ${config.configFolder}${if (java.nio.file.Files.isDirectory(config.configFolder)) "" else " (not there yet)"}" }
            loop.run()
        } finally {
            config.running = null
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
        colorSchemeTimer?.cancel()
        stopTellingPrograms()
        if (sessionBusConnected) sessionBus?.close()
        if (systemBusConnected) systemBus?.close()
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
        display.close()
    }

    private companion object {

        // What programs must get from the compositor itself (Caulis.environment does not change them).
        val OWN_VARIABLES = setOf("WAYLAND_DISPLAY", "DISPLAY", "XDG_ACTIVATION_TOKEN", dev.caulis.ipc.CaulisIpc.SOCKET_VARIABLE)
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111

        // From here on, keys are no typing keys (media, laptop, F13..F24); 0x100..0x15f are buttons.
        const val FIRST_SPECIAL_KEY = 113

        // Keys that always exist when a configuration has none at all.
        const val EMERGENCY_QUIT = "Super+Shift+Escape"
        const val EMERGENCY_TERMINAL = "Super+Return"
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
