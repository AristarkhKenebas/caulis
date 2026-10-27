package dev.caulis.wm

import dev.caulis.core.Animations
import dev.caulis.core.Background
import dev.caulis.core.Desktop
import dev.caulis.core.Effects
import dev.caulis.core.LayerSurface
import dev.caulis.core.Notice
import dev.caulis.core.NoticeLevel
import dev.caulis.core.NoticeStyle
import dev.caulis.core.Scratchpad
import dev.caulis.core.TitleBarStyle
import dev.caulis.core.TitleBars
import dev.caulis.core.Window
import dev.caulis.core.WorkspaceMode
import dev.caulis.core.WorkspaceRule
import dev.caulis.core.appearance.ColorSchedule
import dev.caulis.core.appearance.ColorScheme
import dev.caulis.core.cursor.CursorTheme
import dev.caulis.core.input.KeyBindings
import dev.caulis.core.input.InputSettings
import dev.caulis.core.input.Seat
import dev.caulis.core.input.TabletAxes
import dev.caulis.log.Log
import dev.caulis.log.Logger
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Direction
import dev.caulis.tiling.Layout
import dev.caulis.tiling.TilingCommand
import java.util.function.Consumer
import kotlin.system.exitProcess

/** How one of your keys behaves (repeat while held, on release, while locked, a description). */
typealias BindOptions = dev.caulis.core.input.KeyBindings.Options

/**
 * A complete compositor, configured in a few lines and started with [run]:
 *
 * ```
 * fun main() = caulis {
 *     layout = Dwindle(Gaps(inner = 8, outer = 12))
 *     bind("Super+Return") { launch("foot") }
 *     bind("Super+Q") { closeFocused() }
 * }
 * ```
 *
 * The same from Java:
 *
 * ```
 * Caulis.create()
 *     .layout(Layouts.Dwindle())
 *     .bind("Super+Return", c -> c.launch("foot"))
 *     .run();
 * ```
 *
 * It runs as a window inside the current Wayland session when there is one, and directly
 * on the screen when started from a text console ([backend]).
 *
 * Settings are read when it starts; most can also be changed while it runs (each says so).
 * A reload ([hotReload], config.json saved again) makes a new configuration: see [onStart]
 * for what lives through reloads and what belongs to one configuration.
 */
class Caulis {

    // --- settings (before run) ------------------------------------------------------------

    /** Where the compositor shows its picture and gets its input (see [backend]). */
    enum class Backend {
        /** Nested when WAYLAND_DISPLAY is set, otherwise on the screen. */
        AUTO,

        /** A window inside the current Wayland session. */
        NESTED,

        /** Directly on the screen (from a text console). */
        SCREEN,

        /**
         * On monitors that are nowhere on screen ([headlessMonitors] of them): for trying a
         * compositor from inside another one, tests, and virtual monitors to share.
         * `CAULIS_MODE=headless` chooses it without changing the code.
         */
        HEADLESS,
    }

    /** The old name of [Backend]. */
    @Deprecated("Renamed to Backend (\"mode\" is for key modes)", ReplaceWith("Caulis.Backend"))
    enum class Mode { AUTO, NESTED, SCREEN, HEADLESS }

    /**
     * Where it runs: [Backend.AUTO] (the default: in a window inside a Wayland session, else on
     * the screen), [Backend.NESTED], [Backend.SCREEN] or [Backend.HEADLESS]. `CAULIS_MODE`
     * (nested, screen, headless) wins over it. Only at the start.
     */
    var backend = Backend.AUTO

    /** The old name of [backend]. */
    @Deprecated("Renamed to backend (\"mode\" is for key modes)", ReplaceWith("backend"))
    @Suppress("DEPRECATION")
    var mode: Mode
        get() = Mode.valueOf(backend.name)
        set(value) {
            backend = Backend.valueOf(value.name)
        }

    /** How windows are arranged on every workspace; can also be changed while running. */
    var layout: Layout = Floating
        set(value) {
            field = value
            running?.desktop?.layout = value
        }

    /**
     * Your compositor places windows itself, every frame (wlroots-style: drawn rectangle,
     * scale, opacity, order — see [dev.caulis.core.WindowDirector]); null: [layout] does.
     */
    var director: dev.caulis.core.WindowDirector? = null
        set(value) {
            field = value
            running?.desktop?.director = value
        }

    /**
     * The global time of animations, in milliseconds: windows gliding to new places,
     * minimizing into the dock, the overview zooming (× 1.5), ... — those that have no time
     * of their own (0 = no animations at all, they jump). Animations are off unless turned
     * on: [animations].
     */
    var animationMillis = 220L
        set(value) {
            require(value >= 0) { "animationMillis must not be negative" }
            field = value
            running?.desktop?.animationMillis = value
        }

    /**
     * All animations, Hyprland-like: off by default (everything changes at once), turned on
     * all together ([Animations.enabled]) or one by one by name (windowsIn, windowsOut,
     * windowsMove, scratchpads, minimize, restore, workspaces, overview, border, fadeScale,
     * fadeBackground, fadeNotices, fadeOsd — see [dev.caulis.core.AnimationTree.CAULIS]),
     * each with its time, curve and look; the looks themselves can be code:
     *
     * ```
     * animations { enabled = true }                                              // the usual ones, on
     * animations { enabled = true; animation("windowsIn", style = "popin 80%"); off("fade") }
     * animations { on("workspaces") }                                            // just this one
     * animations = Animations(enabled = true, minimize = WindowAnimation.pop())  // no genie
     * animations = Animations(enabled = true, windowOpen = WindowAnimation { m -> WindowFrame(m.to, alpha = m.progress.toFloat()) })
     * ```
     *
     * Own shaders too: [WindowShader][dev.caulis.core.WindowShader]. See [Animations] and the guide.
     */
    var animations: Animations = Animations()
        set(value) {
            field = value
            running?.desktop?.animations = value
        }

    /**
     * What the desktop shows behind everything — a colour, a gradient, a picture, your own
     * drawing, or a shader, still or moving (see [Background], [WallpaperShader][dev.caulis.core.WallpaperShader]):
     *
     * ```
     * background = Background.image(Path.of("/usr/share/backgrounds/forest.jpg"))
     * background = Background.gradient(Color.hex("#1e3c72"), Color.hex("#2a5298"))
     * background = Background.shader(WallpaperShader.AURORA)
     * ```
     *
     * A wallpaper program (swaybg, hyprpaper, swww, ...) draws on top of it and wins.
     */
    var background: Background = Background.USUAL
        set(value) {
            field = value
            running?.desktop?.background = value
        }

    internal var backgroundFor: ((String, Int) -> Background?)? = null

    /**
     * A background per monitor (its connector name) and workspace (its number); null: [background].
     * `backgroundFor { _, workspace -> Background.image(Path.of("/home/me/walls/$workspace.jpg")) }`
     */
    @JvmSynthetic
    fun backgroundFor(rule: (monitor: String, workspace: Int) -> Background?) {
        backgroundFor = rule
        running?.applyLooks()
    }

    /** How long one background melts into the next (milliseconds). */
    var backgroundChangeMillis = 400L
        set(value) {
            require(value in 0..10_000) { "backgroundChangeMillis is 0 to 10000" }
            field = value
            running?.desktop?.backgroundChangeMillis = value
        }

    /**
     * How the mouse goes between monitors of different sizes: [MonitorEdges.TOUCHING][dev.caulis.core.MonitorEdges.TOUCHING] (only
     * where they touch, as Windows — the default) or [MonitorEdges.PROPORTIONAL][dev.caulis.core.MonitorEdges.PROPORTIONAL] (each edge
     * leads to the whole edge beside it: top corner to top corner, bottom to bottom).
     */
    var monitorEdges = dev.caulis.core.MonitorEdges.TOUCHING
        set(value) {
            field = value
            running?.desktop?.monitorEdges = value
        }

    /** How much of the monitor the overview's windows take (0.3 to 1). */
    var overviewFill = 0.9
        set(value) {
            require(value in 0.3..1.0) { "overviewFill is 0.3 to 1" }
            field = value
            running?.desktop?.overviewFill = value
        }

    /** The edge around floating windows with the compositor's title bar to grab for resizing (units). */
    var resizeBorder = 6
        set(value) {
            require(value in 0..50) { "resizeBorder is 0 to 50" }
            field = value
            running?.desktop?.resizeBorder = value
        }

    /** Two clicks on a title bar within this many milliseconds maximize the window. */
    var doubleClickMillis = 400L
        set(value) {
            require(value in 50..2000) { "doubleClickMillis is 50 to 2000" }
            field = value
            running?.desktop?.doubleClickMillis = value
        }

    /** How far (in touchpad pixels) fingers must move for a swipe to count. */
    var swipeDistance = 80.0
        set(value) {
            require(value in 10.0..1000.0) { "swipeDistance is 10 to 1000" }
            field = value
        }

    /** The colour behind the screen locker (what shows until it has drawn). */
    var lockColor: dev.caulis.render.Color = dev.caulis.render.Color(0f, 0f, 0f)
        set(value) {
            field = value
            running?.desktop?.lockColor = value
        }

    /** The colour under everything: what shows where no window and no wallpaper is. */
    var backgroundColor: dev.caulis.render.Color = dev.caulis.render.Color(0.16f, 0.18f, 0.22f)
        set(value) {
            field = value
            running?.desktop?.backgroundColor = value
        }

    /**
     * Which programs may take the keyboard by themselves (a window asking for fullscreen, an
     * X11 program activating or un-minimizing itself): [FocusStealing.SAME_PROGRAM] (the
     * default: only while one of its own windows has it), [FocusStealing.ALWAYS] or
     * [FocusStealing.NEVER]. A window that may not is marked urgent instead. [focusStealingRule]
     * decides instead, when set.
     */
    var focusStealing = FocusStealing.SAME_PROGRAM
        set(value) {
            field = value
            running?.applyCoreSettings()
        }

    /**
     * Which window gets the keyboard when the screen is unlocked:
     * [dev.caulis.core.UnlockFocus.FOCUSED_BEFORE] (the default: the one that had it when it
     * locked, else the newest of those open before) or [dev.caulis.core.UnlockFocus.NEWEST_BEFORE].
     * A window that opened while locked never gets it (what you type right after unlocking
     * could be a password).
     */
    var unlockFocus = dev.caulis.core.UnlockFocus.FOCUSED_BEFORE
        set(value) {
            field = value
            running?.applyCoreSettings()
        }

    /** Sets [unlockFocus] (Java). */
    fun unlockFocus(focus: dev.caulis.core.UnlockFocus): Caulis = apply { unlockFocus = focus }

    /** Your own rule for [focusStealing]: may [Window] take the focus for this [dev.caulis.core.WindowRequest]? Null: [focusStealing]. */
    var focusStealingRule: ((Window, dev.caulis.core.WindowRequest) -> Boolean)? = null
        set(value) {
            field = value
            running?.applyCoreSettings()
        }

    /** Idle inhibitors (a video playing) keep the screen on while it is locked too (false: they count for nothing then). */
    var idleInhibitorsWhileLocked = false
        set(value) {
            field = value
            running?.applyCoreSettings()
        }

    /**
     * The longest text from a program passed on to others (titles, app ids, panel names; X11
     * titles too), in UTF-8 bytes (16 to 3000): longer is cut there.
     */
    var maxForwardedStringBytes = 1024
        set(value) {
            require(value in 16..3000) { "maxForwardedStringBytes is 16 to 3000, not $value" }
            field = value
            running?.applyCoreSettings()
        }

    /** How many rectangles a program's region (where it takes input, where it is opaque) keeps exactly (1 to 100000). */
    var maxRegionParts = 256
        set(value) {
            require(value in 1..100_000) { "maxRegionParts is 1 to 100000, not $value" }
            field = value
            running?.applyCoreSettings()
        }

    /**
     * The programs (by name, as ps shows them: "waybar") that may see the list of windows
     * (taskbars, docks); null (the default): those the privileged rule ([privilegedClients]) lets in.
     */
    var windowListPrograms: MutableSet<String>? = null

    /** How long to wait after Xwayland failed to start before trying again (doubled each time), in milliseconds. */
    var xwaylandRestartMillis = 1000L
        set(value) {
            require(value in 0..600_000) { "xwaylandRestartMillis is 0 to 600000, not $value" }
            field = value
            running?.applyCoreSettings()
        }

    /** The longest that wait gets, in milliseconds. */
    var xwaylandRestartMaxMillis = 60_000L
        set(value) {
            require(value in 0..3_600_000) { "xwaylandRestartMaxMillis is 0 to 3600000, not $value" }
            field = value
            running?.applyCoreSettings()
        }

    /** After this many failed starts in a row Xwayland is not tried again until the action "xwayland-retry". */
    var xwaylandMaxFailedStarts = 5
        set(value) {
            require(value in 1..1000) { "xwaylandMaxFailedStarts is 1 to 1000, not $value" }
            field = value
            running?.applyCoreSettings()
        }

    /**
     * Which key is a modifier in your bindings: [BindingModifiers.XKB] (the default: as the
     * keyboard layout and its options say — caps:super makes Caps Lock a Super) or
     * [BindingModifiers.PHYSICAL] (the keys printed Ctrl, Alt, Super, Shift, whatever the layout).
     */
    var bindingModifiers = BindingModifiers.XKB
        set(value) {
            field = value
            running?.rebindKeys()
        }

    /**
     * How windows look: rounded corners, a border, shadows, blur, transparency. Off by
     * default; e.g. `effects = Effects.MODERN` or
     * `effects = Effects(cornerRadius = 10, shadow = Shadow(), blur = Blur(), inactiveOpacity = 0.9f)`.
     * Can also be changed while running.
     */
    var effects: Effects = Effects.NONE
        set(value) {
            field = value
            running?.desktop?.effects = value
        }

    /**
     * How special workspaces (Hyprland's `special`) look and behave: what is behind them
     * dimmed and blurred, their gap and size, their animation, hiding when empty or on the
     * lock, new windows joining the one shown, its own layout (else [layout]):
     * `specialWorkspaces = SpecialWorkspaces(dim = 0.5f, blur = Blur(6, 3), gap = 60)`.
     * Show one with [toggleSpecialWorkspace] (or the action `special-workspace toggle [name]`).
     */
    var specialWorkspaces: dev.caulis.core.SpecialWorkspaces = dev.caulis.core.SpecialWorkspaces.USUAL
        set(value) {
            field = value
            running?.desktop?.specialWorkspaces = value
        }

    /**
     * How big everything is: 1.0, 1.25, 1.5, 2.0 ... (also fractions). Programs are told and
     * draw at exactly this size, sharp — X11 programs too. Can be changed while running.
     */
    var scale: Double = 1.0
        set(value) {
            require(value in 0.5..4.0) { "scale must be between 0.5 and 4" }
            field = value
            running?.applyScale()
        }

    /**
     * Who draws windows' title bars: [TitleBars.PROGRAM] (each program its own, the default),
     * [TitleBars.COMPOSITOR] (the same for all, see [titleBarStyle]) or [TitleBars.NONE].
     * Programs that do not take part (GTK 4 apps) always draw their own — never two bars.
     */
    var titleBars: TitleBars = TitleBars.PROGRAM
        set(value) {
            field = value
            running?.desktop?.titleBars = value
        }

    /** How the compositor's title bars look: height, font, colours, buttons, or a painter of your own. */
    var titleBarStyle: TitleBarStyle = TitleBarStyle()
        set(value) {
            field = value
            running?.desktop?.titleBarStyle = value
        }

    internal val titleBarRules = ArrayList<(Window) -> TitleBars?>()

    /**
     * How the compositor's own notices look and where they are — mistakes in your
     * configuration, what does not work, and what you [notify]:
     *
     * ```
     * noticeStyle = NoticeStyle()                                        // a strip at the top; windows and panels make way (the default)
     * noticeStyle = NoticeStyle(placement = NoticePlacement.Banner(room = Room.COVER))  // over everything
     * noticeStyle = NoticeStyle(placement = NoticePlacement.Banner(room = Room.COVER, errorRoom = Room.PUSH_ALL))  // errors push, the rest covers
     * noticeStyle = NoticeStyle(placement = NoticePlacement.Toast())     // cards at the bottom right, like notifications
     * noticeStyle = NoticeStyle(placement = NoticePlacement.Hidden)      // not drawn: show them yourself with onNotice
     * ```
     *
     * Colours, font, how long each level stays and a painter of your own are in [NoticeStyle].
     * Can be changed while running.
     */
    var noticeStyle: NoticeStyle = NoticeStyle()
        set(value) {
            field = value
            running?.desktop?.noticeStyle = value
        }

    /**
     * Which lines of the log become notices on screen. By default your code's mistakes and
     * problems with the settings (the "config" area: a key used twice, a rule that failed,
     * ...) and every error. `noticesFromLog = { false }` for none.
     */
    @set:JvmSynthetic
    var noticesFromLog: (Log.Entry) -> Boolean = { entry -> (entry.area == "config" && entry.isWarning) || entry.isError }

    internal val noticeListeners = ArrayList<Caulis.(Notice) -> Unit>()

    /** Runs [action] for every notice (e.g. to send it to your panel when [noticeStyle] is Hidden). */
    @JvmSynthetic
    fun onNotice(action: Caulis.(Notice) -> Unit): AutoCloseable = listen(noticeListeners, action)

    /**
     * Shows [text] on screen as a notice of the compositor (see [noticeStyle]), e.g.
     * `notify("Recording started")` or `notify("No battery found", NoticeLevel.WARNING)`.
     */
    @JvmOverloads
    fun notify(text: String, level: NoticeLevel = NoticeLevel.INFO, title: String? = null): Notice = desktop.notify(text, level, title)

    /**
     * Title bars for some programs: `titleBarsFor { if (it.appId == "spotify") TitleBars.COMPOSITOR else null }`
     * (null = [titleBars]). The first rule with an answer wins.
     */
    @JvmSynthetic
    fun titleBarsFor(rule: (Window) -> TitleBars?) {
        titleBarRules += rule
        running?.rulesChanged() // (while running: open windows are asked at once)
    }

    /**
     * Run X11 programs too (through Xwayland, started only when the first one connects). Off
     * = Wayland programs only. Only at the start.
     */
    var xwayland = true

    // --- monitors -------------------------------------------------------------------------

    /**
     * How workspaces behave with several monitors (only after a restart):
     *
     * - [WorkspaceMode.PER_MONITOR] — numbers shared, Super+N shows N where the focus is (sway, Hyprland)
     * - [WorkspaceMode.PINNED] — workspaces are screens: each stays on its monitor (one bar for all)
     * - [WorkspaceMode.SEPARATE] — each monitor has its own 1, 2, 3, ... (dwm, awesome)
     * - [WorkspaceMode.SPANNING] — one workspace over all monitors (GNOME)
     * - [WorkspaceMode.PRIMARY_ONLY] — only the [primaryMonitor] switches; the others are plain screens
     */
    var workspaceMode = WorkspaceMode.PER_MONITOR

    /** The primary monitor's connector ("DP-2"); null: the first one. See [WorkspaceMode.PRIMARY_ONLY]. */
    var primaryMonitor: String? = null

    /**
     * How many workspaces there are (1 to 99; Super+1..9 reach the first nine). 1: no
     * workspaces at all — one desktop per monitor, and the workspace keys are free.
     */
    var workspaceCount = 9
        set(value) {
            require(value in 1..99) { "workspaceCount is 1 to 99, not $value" }
            field = value
        }

    internal val workspaceRules = ArrayList<WorkspaceRule>()

    /**
     * How workspace [number] is:
     *
     * ```
     * workspace(1) { name = "web"; monitor = "DP-2" }          // always on DP-2
     * workspace(3) { layout = ScrollingColumns(); persistent = true }
     * ```
     */
    @JvmSynthetic
    fun workspace(number: Int, configure: WorkspaceRule.() -> Unit) {
        workspaceRules += WorkspaceRule(number).apply(configure)
        running?.applyWorkspaces() // (while running: at once)
    }

    /** What one monitor should be like; see [monitor]. */
    class MonitorSettings internal constructor(
        /** Its connector: "eDP-1" (a laptop's screen), "DP-2", "HDMI-A-1", ... (the log lists them). */
        val name: String,
    ) {
        /** Its own scale (else [Caulis.scale]). */
        var scale: Double? = null

        /** Where it is on the desktop, in desktop units (else to the right of the others). */
        var x: Int? = null
        var y: Int? = null

        fun position(x: Int, y: Int) {
            this.x = x
            this.y = y
        }

        /** The mode it should use if it has it, e.g. 2560 x 1440 at 144 Hz (else its own preferred one). */
        var width: Int? = null
        var height: Int? = null
        var refreshHz: Double? = null

        @JvmOverloads
        fun mode(width: Int, height: Int, refreshHz: Double? = null) {
            this.width = width
            this.height = height
            this.refreshHz = refreshHz
        }

        /** False: the monitor is not used at all. */
        var enabled = true

        /** How it is turned: Transform.ROTATE_90 for a screen standing on its side (null: as it is). */
        var transform: dev.caulis.core.Transform? = null

        /** The most bits per colour the link may use (6 to 16; null: 8). On the screen only, with the next mode set. */
        var maxBpc: Int? = null
            set(value) {
                require(value == null || value in 6..16) { "maxBpc is 6 to 16, not $value" }
                field = value
            }

        /** Variable refresh rate (FreeSync), if the monitor has it (null: off). On the screen only. */
        var adaptiveSync: Boolean? = null
    }

    internal val monitorSettings = LinkedHashMap<String, MonitorSettings>()

    /**
     * Settings of the monitor at connector [name], e.g.
     * `monitor("DP-2") { scale = 1.5; position(1920, 0) }` or `monitor("HDMI-A-1") { enabled = false }`.
     * Can be changed while running (all of it: only what differs changes).
     */
    fun monitor(name: String, settings: MonitorSettings.() -> Unit) {
        monitorSettings.getOrPut(name) { MonitorSettings(name) }.apply(settings)
        running?.applyMonitorSettings()
    }

    /**
     * In a window inside another session: how many windows to open, each one a monitor of
     * its own (named NESTED-1, NESTED-2, ...) — to try out a desktop with several monitors.
     * Only at the start.
     */
    var nestedMonitors = 1

    /**
     * Headless ([Backend.HEADLESS]): how many monitors (HEADLESS-1, HEADLESS-2, ...), 1 to 8;
     * their size is their [monitor] mode, else 1920x1080. Only at the start.
     */
    var headlessMonitors = 1
        set(value) {
            require(value in 1..8) { "headlessMonitors is 1 to 8, not $value" }
            field = value
        }

    /**
     * Start programs the Wayland way when they can (GTK, Qt, Electron, Firefox are told to
     * prefer it), and through X11 when that fails: a program that quits at once without a
     * window is started again through Xwayland. Off = programs choose for themselves.
     * Variables you set yourself ([environment], or before the compositor started) win.
     */
    var waylandFirst = true

    /**
     * Java programs (Swing, AWT) on a fractional scale such as 1.5: they can only draw at whole
     * scales, so they get an X server of their own, draw at the next whole scale (2) and are
     * shown at exactly yours — sharp and the right size. Off = they draw at the nearest whole
     * scale with the other X11 programs (sharp, but up to a third too big or too small).
     * Only at the start.
     */
    var sharpJavaScaling = true

    /** Programs always started through X11, by name: `x11Programs += "steam"`. */
    val x11Programs: MutableSet<String> = LinkedHashSet()

    /**
     * With [waylandFirst]: a program that connected to the compositor and quit with an error
     * within [x11FallbackMillis], before it showed a window, is started again through X11 (and
     * from then on that way). Programs that never connected (command-line tools, scripts) are
     * never started twice. Off: nothing is started again.
     */
    var x11Fallback = true

    /** How soon a program must fail for [x11Fallback], in milliseconds. */
    var x11FallbackMillis = 3000L
        set(value) {
            require(value in 0..60_000) { "x11FallbackMillis is 0 to 60000, not $value" }
            field = value
        }

    /**
     * When the compositor quits, the programs it started that still run (and their children)
     * are asked to end (SIGTERM) — a program that is no window (swayidle, a script, mpd) would
     * else stay behind and run twice after the next start. Off: they are left running.
     */
    var stopProgramsOnQuit = true

    /**
     * Programs started with the session (a bar, a tray applet): one after another, once the
     * desktop is shown, once per session — a reload starts only the entries it adds. See [Autostart].
     */
    val autostart = Autostart()

    /** Changes [autostart]: `autostart { app("waybar"); shell("sleep 1; notify-send hi") }`. */
    @JvmSynthetic
    fun autostart(configure: Autostart.() -> Unit) {
        autostart.configure()
    }

    /** The same for Java. */
    fun autostart(configure: Consumer<Autostart>): Caulis = apply { configure.accept(autostart) }

    /**
     * The start, smooth: the screens stay dark until the compositor is warm, then the desktop
     * fades in (off by default). See [Reveal].
     */
    val reveal = Reveal()

    /** Changes [reveal]: `reveal { enabled = true; fade = 600.milliseconds }`. */
    @JvmSynthetic
    fun reveal(configure: Reveal.() -> Unit) {
        reveal.configure()
    }

    /** The same for Java. */
    fun reveal(configure: Consumer<Reveal>): Caulis = apply { configure.accept(reveal) }

    /** A sound when the session starts (off by default). See [StartupSound]. */
    val startupSound = StartupSound()

    /** Changes [startupSound]: `startupSound { enabled = true; preset = "airy" }`. */
    @JvmSynthetic
    fun startupSound(configure: StartupSound.() -> Unit) {
        startupSound.configure()
    }

    /** The same for Java. */
    fun startupSound(configure: Consumer<StartupSound>): Caulis = apply { configure.accept(startupSound) }

    internal val revealActions = ArrayList<Caulis.() -> Unit>()

    /**
     * Runs [action] once the desktop is shown at the start (when it begins to fade in with
     * [reveal]; at once without) — once per session: not again after a reload.
     */
    @JvmSynthetic
    fun onReveal(action: Caulis.() -> Unit): AutoCloseable = listenTo(revealActions, action)

    /** The same for Java. */
    fun onReveal(action: Consumer<Caulis>): Caulis = apply { onReveal { action.accept(this) } }

    /** True once the desktop is shown at the start (see [reveal]). */
    val revealed: Boolean get() = liveOrNull()?.sessionStart?.revealed ?: false

    /**
     * The screen stays dark until [work] calls `done` (from any thread) — or [Reveal.maxWait]
     * passed: work that should be done before the desktop shows (a bar drawn once, a wallpaper
     * loaded). [work] runs on the compositor's thread when it starts. Nothing waits when the
     * reveal is off or over.
     */
    @JvmSynthetic
    fun whenWarm(name: String, work: (done: () -> Unit) -> Unit) {
        val start = liveOrNull()?.sessionStart
        if (start == null) reveal.whenWarm(name, work) else start.whenWarm(name, work)
    }

    /** The same for Java: [work] gets a Runnable to run when it is done. */
    fun whenWarm(name: String, work: Consumer<Runnable>): Caulis = apply { whenWarm(name) { done -> work.accept(Runnable { done() }) } }

    /**
     * The screen stays dark until the result is closed (or [Reveal.maxWait] passed); see
     * [whenWarm]. Callable from any thread.
     */
    fun holdReveal(name: String): AutoCloseable = liveOrNull()?.sessionStart?.hold(name) ?: reveal.hold(name)

    /**
     * Starts the program [command] (its words, no shell) in [directory] in the queue of
     * [autostart]: after the reveal, one after another, [gapMillis] before the next one (null:
     * [Autostart.staggerMillis]) — for session restore, or a start of your own that should
     * not compete with the others. Asked for before it runs, it waits for the start.
     */
    @JvmOverloads
    fun startLater(command: List<String>, directory: java.nio.file.Path? = null, gapMillis: Long? = null) {
        val start = liveOrNull()?.sessionStart
        if (start != null) start.startLater(command, directory?.toFile(), gapMillis)
        else pendingStarts += Triple(command.toList(), directory?.toFile(), gapMillis)
    }

    // startLater before the compositor runs: handed to it when it starts.
    internal val pendingStarts = ArrayList<Triple<List<String>, java.io.File?, Long?>>()

    /** How each program of [autostart] (and of [startLater]) is, one per line ("waybar: running (pid 4242, ...)"). */
    fun autostartStatus(): List<String> = liveOrNull()?.sessionStart?.status().orEmpty()

    /** Plays the startup sound now (to try it; [preset] another preset than the configured one), on a thread of its own. */
    @JvmOverloads
    fun playStartupSound(preset: String? = null) {
        require(preset == null || preset in StartupChimes.PRESETS) { "there is no preset \"$preset\" (there are ${StartupChimes.PRESETS.joinToString()})" }
        live().sessionStart.playSound(preset)
    }

    /**
     * Keyboard layouts as for `setxkbmap`, e.g. "us,ru" (see [keyboardOptions]). Changed while
     * running, every program gets the new keymap at once.
     */
    var keyboardLayouts = "us"
        set(value) {
            field = value
            running?.applyKeymap()
        }

    /**
     * Keyboard options as for `setxkbmap`, e.g. "grp:win_space_toggle" (Super+Space switches
     * layouts) or "grp:alt_shift_toggle". Changed while running: at once.
     */
    var keyboardOptions = ""
        set(value) {
            field = value
            running?.applyKeymap()
        }

    /**
     * Applies settings your code changed while the compositor runs — the keyboard's layouts,
     * the cursor, the touchpad, focus, idle timers, ... — as a reload of a configuration file
     * does (a compositor with a configuration of its own calls it after reading that).
     */
    fun applySettings() {
        running?.applySettingsNow()
    }

    /**
     * Your own on-screen display for what the laptop's keys change (volume, brightness, the
     * microphone, the touchpad): [handler] gets the title ("Volume 45 %"), the level (0..1 or
     * null) and whether it is off (muted); true takes it — Caulis's own pill is not shown.
     */
    fun onScreenDisplay(handler: ScreenDisplayHandler) {
        osdHandler = { title, level, off -> handler.show(title, level, off) }
        running?.desktop?.osdHandler = osdHandler
    }

    internal var osdHandler: ((String, Double?, Boolean) -> Boolean)? = null

    /** The keyboard layout in use: its number in [keyboardLayouts] ("us,ru": 0 is us, 1 ru). */
    val keyboardLayoutNow: Int get() = liveOrNull()?.seat?.layoutIndex ?: 0

    /** The names of the keyboard layouts ([keyboardLayouts] split: "us,ru" gives us and ru). */
    val keyboardLayoutNames: List<String> get() = keyboardLayouts.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Switches the keyboard layout to number [index] of [keyboardLayouts] (counting around: one past the last is the first). */
    fun switchKeyboardLayout(index: Int) {
        val seat = liveOrNull()?.seat ?: return
        seat.setLayout(index)
        keyboardLog.info { "Keyboard layout: ${keyboardLayoutNames.getOrNull(seat.layoutIndex) ?: seat.layoutIndex}" }
    }

    /**
     * Num Lock on (true) or off (false) from the start; null leaves it to the keyboard.
     * Changed while running, it switches at once (a reload only when the setting changed:
     * the key may have switched it since).
     */
    var numLock: Boolean? = null
        set(value) {
            field = value
            running?.applyNumLock()
        }

    /** Keyboard layout variants, one per layout as for setxkbmap: "", "dvorak", ",phonetic". Changed while running: at once. */
    var keyboardVariants = ""
        set(value) {
            field = value
            running?.applyKeymap()
        }

    /** The keyboard model as for setxkbmap ("pc105"). Changed while running: at once. */
    var keyboardModel = "pc105"
        set(value) {
            field = value
            running?.applyKeymap()
        }

    /**
     * The screen locker [lock] (and the default Super+L) starts, e.g. "swaylock -c 1e1e2e",
     * "gtklock" or "hyprlock". While it runs, nothing of the desktop is visible and only it
     * gets the keyboard; it unlocks when you type your password.
     */
    var lockCommand = "swaylock"

    /**
     * The cursor theme, e.g. "Caulis-Black", "Adwaita", "Breeze". Null = the default: a theme
     * that came with a library, else `XCURSOR_THEME` if set, else Caulis's own sharp white
     * cursors ("Caulis").
     * [CursorTheme.available] lists what is there.
     */
    var cursorTheme: String? = null
        set(value) {
            field = value
            running?.applyCursor()
        }

    // --- light and dark --------------------------------------------------------------------

    /**
     * Light or dark for programs: [ColorScheme.DARK] or [ColorScheme.LIGHT] is what GTK 4,
     * libadwaita, Qt 6, Firefox, Chromium and Electron programs follow (through
     * xdg-desktop-portal and gsettings). [ColorScheme.DEFAULT] (the default): nothing is said,
     * each program looks as it does by itself. With a [colorSchedule], the schedule decides.
     */
    var colorScheme: ColorScheme = ColorScheme.DEFAULT
        set(value) {
            field = value
            running?.applyAppearance()
        }

    /**
     * Dark and light by themselves: `ColorSchedule.Times(dark = LocalTime.of(20, 0), light =
     * LocalTime.of(7, 0))`, or from sunset to sunrise at a place: `ColorSchedule.Sun(55.75,
     * 37.62)`. Null (the default): only [colorScheme]. [switchColorScheme] overrides it until
     * its next change.
     */
    var colorSchedule: ColorSchedule? = null
        set(value) {
            field = value
            running?.applyAppearance()
        }

    /** The accent colour programs are told (buttons, selections; libadwaita and others follow it), or null: their own. */
    var accentColor: dev.caulis.render.Color? = null
        set(value) {
            field = value
            running?.applyAppearance()
        }

    /**
     * GTK 3 programs follow a theme, not the scheme: the GTK theme for light and for dark, e.g.
     * `"Adwaita" to "Adwaita-dark"`, switched with the scheme. Null (the default): the theme is
     * left as it is.
     */
    var gtkThemes: Pair<String, String>? = null
        set(value) {
            field = value
            running?.applyAppearance()
        }

    /**
     * Whether the compositor's environment (WAYLAND_DISPLAY, DISPLAY, XDG_CURRENT_DESKTOP,
     * XDG_SESSION_TYPE, and what you set in [environment]) is handed to the session's services
     * at the start — systemd's user manager and D-Bus activation — so portals, notifications
     * and screen sharing started by them reach this compositor. Null (the default): on a real
     * screen only. Only at the start.
     */
    var exportEnvironment: Boolean? = null

    /**
     * The services started again after [exportEnvironment] when they still run with another
     * session's WAYLAND_DISPLAY (a link they open would go nowhere) — those running only.
     * The desktop portals by default; empty: none.
     */
    val portalsToRestart: MutableList<String> = mutableListOf(
        "xdg-desktop-portal.service", "xdg-desktop-portal-gtk.service", "xdg-desktop-portal-wlr.service",
        "xdg-desktop-portal-gnome.service", "xdg-desktop-portal-kde.service",
    )

    /**
     * Whether programs are told the scheme (the portal and gsettings, which are your whole
     * session's). Null (the default): on a real screen only — a nested or headless compositor
     * (a test) does not change your desktop's look.
     */
    var appearanceToPrograms: Boolean? = null
        set(value) {
            field = value
            running?.applyAppearance()
        }

    /** The scheme programs are told now (from the schedule, by hand, or [colorScheme]). */
    val colorSchemeNow: ColorScheme get() = liveOrNull()?.appearance?.colorScheme ?: colorScheme

    /**
     * Light or dark now, by hand (a key, `--do color-scheme dark`): until the schedule's next
     * change, or for as long as the compositor runs without one. Null: back to the schedule
     * or [colorScheme].
     */
    fun switchColorScheme(scheme: ColorScheme?) {
        live().switchColorScheme(scheme)
    }

    /** Runs [action] whenever the scheme changes (to restyle a bar of your own, ...). */
    @JvmSynthetic
    fun onColorSchemeChanged(action: Caulis.(ColorScheme) -> Unit): AutoCloseable = listen(colorSchemeListeners, action)

    internal val colorSchemeListeners = ArrayList<Caulis.(ColorScheme) -> Unit>()

    /** The cursor size in pixels (24 is usual; 32 or 48 for a bigger one). Null = `XCURSOR_SIZE` or 24. Changed while running: at once. */
    var cursorSize: Int? = null
        set(value) {
            require(value == null || value in 8..256) { "cursorSize must be between 8 and 256" }
            field = value
            running?.applyCursor()
        }

    /** Hide the cursor while typing; it comes back when the mouse moves. Changed while running: at once. */
    var hideCursorWhileTyping = false
        set(value) {
            field = value
            running?.applyCursor()
        }

    /** Hide the cursor after the mouse was still for this many seconds (0 = never). Changed while running: at once. */
    var hideCursorAfterSeconds = 0
        set(value) {
            require(value >= 0) { "hideCursorAfterSeconds must not be negative" }
            field = value
            running?.applyCursor()
        }

    /** The terminal [openTerminal] (and the default Super+Return) starts (a command, run by /bin/sh). */
    var terminal = "foot"

    /**
     * The usual keys (Super+Q, Super+1..9, Alt+Tab, Ctrl+Alt+Backspace, ...): each has a
     * name and can be changed or turned off, see [Keys]. Your own [bind] calls win over them.
     */
    val keys = Keys()

    /** Changes the usual keys: `keys { close = "Super+Shift+Q"; dumpState = null }` (see [Keys]). */
    @JvmSynthetic
    fun keys(configure: Keys.() -> Unit) {
        keys.configure()
    }

    /**
     * Changes the animations line by line, as Hyprland's `animations { }` (see [animations],
     * [AnimationsBuilder][dev.caulis.core.AnimationsBuilder]):
     * `animations { enabled = true; millis = 250; animation("windowsIn", style = "popin 80%") }`.
     */
    @JvmSynthetic
    fun animations(configure: dev.caulis.core.AnimationsBuilder.() -> Unit) {
        val builder = dev.caulis.core.AnimationsBuilder(animations).apply(configure)
        builder.millis?.let { animationMillis = it }
        animations = builder.animations
    }

    /**
     * What programs started inside are told the desktop is called (`XDG_CURRENT_DESKTOP`).
     * Desktop portals use it to choose who handles screen sharing (see the guide).
     */
    var desktopName = "caulis"

    /**
     * More environment for the programs it starts, e.g. `environment["MOZ_ENABLE_WAYLAND"] = "1"`;
     * null takes a variable away. They win over what Caulis would choose (the [waylandFirst]
     * toolkit variables, J2D_UISCALE, ...) — only WAYLAND_DISPLAY, DISPLAY, the activation token
     * and the IPC socket are always Caulis's own.
     */
    val environment = LinkedHashMap<String, String?>()

    /**
     * Where the output of programs started with [launch] goes: [ProgramOutput.DISCARD] (the
     * default), [ProgramOutput.LOG] (each line to the log, area "clients", with the program's
     * name) or [ProgramOutput.FILE] (appended to [programOutputFile]). [spawn]'s programs
     * always write where the compositor does.
     */
    var programOutput = ProgramOutput.DISCARD

    /** The file for [ProgramOutput.FILE]; null: `~/.cache/<configName>/programs.log`. */
    var programOutputFile: java.nio.file.Path? = null

    /**
     * The name of your compositor's folder of settings, shaders and pictures:
     * `~/.config/<configName>` (or `$XDG_CONFIG_HOME/<configName>`), see [configFolder].
     * Also names its IPC socket. Only at the start.
     */
    var configName = "caulis"
        set(value) {
            require(value.isNotBlank() && '/' !in value) { "configName is a folder name, not \"$value\"" }
            field = value
        }

    /** Another place for that folder (null: the usual one, see [configName]). Only at the start. */
    var configDir: java.nio.file.Path? = null

    /** Where your files go: [configDir], or `$XDG_CONFIG_HOME/<configName>`, or `~/.config/<configName>`. */
    val configFolder: java.nio.file.Path
        get() = configDir ?: (System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") }?.let { java.nio.file.Path.of(it) }
            ?: java.nio.file.Path.of(System.getProperty("user.home"), ".config")).resolve(configName)

    /**
     * A file by name: "aurora.glsl" is in [configFolder], "~/walls/sea.jpg" in your home,
     * "/usr/share/backgrounds/x.jpg" where it says. For shaders and pictures:
     *
     * ```
     * background = Background.shader(WallpaperShader.file(file("aurora.glsl")))   // ~/.config/<configName>/aurora.glsl
     * background = Background.image(file("walls/sea.jpg"))
     * ```
     *
     * Saved again while running, a shader is used at once and a picture shown anew.
     */
    fun file(name: String): java.nio.file.Path = when {
        name == "~" -> java.nio.file.Path.of(System.getProperty("user.home"))
        name.startsWith("~/") -> java.nio.file.Path.of(System.getProperty("user.home"), name.removePrefix("~/"))
        name.startsWith("/") -> java.nio.file.Path.of(name)
        else -> configFolder.resolve(name)
    }

    /** The window's title when nested (and the name in messages, "Caulis is running ..."). Only at the start. */
    var title = "Caulis"

    /** The window's app id when nested (what the outer session knows it as). Only at the start. */
    var appId = "dev.caulis.Nested"

    /**
     * How much the log says: 0 nearly nothing, 1 problems (the default), 2 what happens
     * (windows, monitors, layouts, ...), 3 everything step by step. `CAULIS_LOG` (e.g.
     * `CAULIS_LOG=3`) wins over this, so you can look closer without changing code.
     * See [Log] and [log] for single areas. Changed by a reload: at once.
     */
    var logLevel: Int = Log.PROBLEMS
        set(value) {
            require(value in 0..3) { "logLevel must be 0, 1, 2 or 3" }
            field = value
        }

    /**
     * Also write the log to this file (e.g. Path.of("/tmp/my-compositor.log")). `CAULIS_LOG_FILE`
     * wins over it. A reload that takes it away closes the file.
     */
    var logFile: java.nio.file.Path? = null

    internal val logAreas = LinkedHashMap<String, Int>()

    /**
     * One area of the log in more (or less) detail than [logLevel], e.g. `log("focus", 3)`
     * to follow every focus change. The areas: [Log.AREAS] ("wayland", "windows", "focus",
     * "input", "layout", "render", "drm", ...).
     */
    fun log(area: String, level: Int): Caulis = apply {
        require(level in 0..3) { "a log level is 0, 1, 2 or 3" }
        require(area in Log.AREAS) { "there is no log area \"$area\"; the areas: ${Log.AREAS.keys.joinToString()}" }
        logAreas[area] = level
    }

    // (0.1's log returned nothing: kept for programs compiled against it.)
    @Deprecated("Returns the Caulis now", level = DeprecationLevel.HIDDEN)
    @JvmName("log")
    fun logReturningNothing(area: String, level: Int) {
        log(area, level)
    }

    /** Writes everything the window manager knows now (windows, focus, panels, ...) to the log. Super+F12 by default. */
    fun dumpState() {
        Log.write("state", Log.QUIET, "D", "The state now:\n" + desktop.describe().trimEnd(), null)
    }

    /**
     * Change your code while the compositor runs: when a file in the project's `src`
     * changes, it is compiled and taken on at once — keys, rules, looks, layouts, events;
     * windows and programs stay where they are. If it does not compile or fails, the old
     * configuration keeps running and a notice on screen says what is wrong. Works when
     * started from the project (`./gradlew run`), not in an installed program. [onStart] runs
     * only once; [onReload] after each reload. Only at the start.
     */
    var hotReload = true

    /**
     * Read `config.json` from [configFolder] too — settings as data, written by hand or by the
     * Nix module (see the guide) — on top of what the code says — and `config.lua` / `config.kts`
     * when their modules are there. Looked for in [configFolder], else in
     * `$XDG_CONFIG_DIRS/<configName>` (`/etc/xdg`, where the NixOS module puts it). Saved
     * again (made, or taken away) while running, it is taken on at once. Only at the start.
     */
    var useConfigFile = true

    /**
     * Open the IPC socket (`$XDG_RUNTIME_DIR/<configName>-<wayland display>.sock`, see
     * caulis.proto): bars, scripts and tools run actions, ask what is on screen and hear what
     * changes — `my-compositor --do "workspace 2"`, `--state`, `--events`. Only at the start.
     */
    var ipc = true

    /** Print frame timing every few seconds. Only at the start. */
    var stats = false

    /**
     * The pointer on the display's own cursor plane (screen mode): it moves without new
     * pictures being drawn, with less delay. Off: always drawn into the pictures. Only at the start.
     */
    var hardwareCursor = true

    /**
     * Which programs may use the privileged features: screenshots and screen sharing,
     * clipboard managers, panels and lock screens, arranging monitors, gamma, managing other
     * programs' windows. All by default (as most compositors); e.g. only some programs:
     * `privilegedClients = { client -> client.program in setOf("grim", "waybar", "swaylock") }`.
     * Asked when a program connects.
     */
    var privilegedClients: (dev.caulis.protocol.Client) -> Boolean = { true }

    /** Show fullscreen programs' pictures directly on the monitor (screen mode). Only at the start. */
    var directScanout = true

    /** Quit by itself after this many seconds (handy for trying things out safely). Only at the start. */
    var exitAfterSeconds: Long? = null

    /** Advanced: the session socket to open the nested window in (null = WAYLAND_DISPLAY). Only at the start. */
    var nestedIn: java.nio.file.Path? = null

    /** Advanced: where programs' socket is created (null = XDG_RUNTIME_DIR, the usual place). Only at the start. */
    var socketDirectory: java.nio.file.Path? = null

    /**
     * Whether [run] answers the program's own command line first (`--check-config`, `--do`,
     * `--state`, ... see [dev.caulis.wm.config.CommandLine]) and then ends the program with
     * its status. False: [run] never reads the command line nor ends the program — for a
     * compositor with a command line of its own (it can hand the words to [run] with
     * arguments). True by default.
     */
    var commandLine = true

    /** One of your keys: its combination, how it behaves, the key mode it belongs to (null: the usual keys). */
    class Binding internal constructor(
        /** The keys, as given ("Super+Q"). */
        val combination: String,
        /** How it behaves (repeat, release, while locked, a description). */
        val options: BindOptions,
        /** The key mode it belongs to ([Caulis.keyMode]); null: the usual keys. */
        val keyMode: String?,
        /** What it does. */
        val action: Caulis.() -> Unit,
    ) {
        /** The old name of [keyMode]. */
        @Deprecated("Renamed to keyMode", ReplaceWith("keyMode"))
        val mode: String? get() = keyMode

        // The configuration it belongs to (none once it is taken away).
        internal var owner: Caulis? = null

        /** False: its keys do nothing of yours (they go to programs) until it is true again. */
        var enabled = true
            set(value) {
                if (field == value) return
                field = value
                owner?.liveOrNull()?.rebindKeys()
            }

        /** Takes it away for good. */
        fun unbind() {
            val from = owner ?: return
            owner = null
            from.bindings -= this
            from.liveOrNull()?.let { r ->
                r.config.bindings -= this
                r.forget(this)
                r.rebindKeys()
            }
        }
    }

    internal val bindings = ArrayList<Binding>()
    internal val startActions = ArrayList<Caulis.() -> Unit>()
    internal val floatRules = ArrayList<(Window) -> Boolean>()
    internal val effectRules = ArrayList<(Window, Effects) -> Effects>()
    internal val panelEffectRules = ArrayList<(LayerSurface) -> Effects?>()
    internal val panelRules = ArrayList<PanelRule>()
    internal val scratchpads = ArrayList<ScratchpadSetup>()
    internal val swipes = ArrayList<Triple<Int, Direction, Caulis.() -> Unit>>()

    /**
     * Swiping on the touchpad with this many fingers drags the view (a canvas, a strip of
     * columns) right under them; when they lift, it settles on the nearest whole place.
     * 0 turns it off (the swipe then goes to the program under the pointer).
     */
    var dragViewFingers = 3

    /**
     * Pinching with this many fingers opens the overview, following the fingers (spreading
     * them closes it again). 0 turns it off.
     */
    var overviewPinchFingers = 4

    /**
     * Swiping with this many fingers left / right goes to the next / previous workspace.
     * 0 turns it off.
     */
    var workspaceSwipeFingers = 4

    internal class ScratchpadSetup(val name: String, val key: String?, val command: String?, val style: Scratchpad.Style, val match: (Window) -> Boolean)
    internal val windowOpened = ArrayList<Caulis.(Window) -> Unit>()
    internal val windowClosed = ArrayList<Caulis.(Window) -> Unit>()
    internal val focusChanged = ArrayList<Caulis.(Window?) -> Unit>()
    internal val workspaceChanged = ArrayList<Caulis.(Int) -> Unit>()
    internal val windowChanged = ArrayList<Caulis.(Window) -> Unit>()
    internal val monitorAdded = ArrayList<Caulis.(String) -> Unit>()
    internal val monitorRemoved = ArrayList<Caulis.(String) -> Unit>()
    internal val unloadActions = ArrayList<Caulis.() -> Unit>()

    // Adds [action] to [list] (while running, it listens at once); close the result to take it away.
    private fun <T> listen(list: MutableList<T>, action: T): AutoCloseable {
        val into = addTo(list, action)
        liveOrNull()?.rewireEvents()
        return AutoCloseable {
            takeFrom(list, into, action)
            liveOrNull()?.rewireEvents()
        }
    }

    // Adds [item] to [list] of the configuration in use: this one — or, for one a reload replaced,
    // the one in use now. Added in onStart (or by a replaced configuration: its threads and timers
    // are the session's), it stays through reloads (see onStart). Returns the list it went into.
    private fun <T> addTo(list: MutableList<T>, item: T): MutableList<T> {
        val r = liveOrNull()
        val index = addableLists().indexOfFirst { it === list }
        @Suppress("UNCHECKED_CAST")
        val into = if (running == null && r != null && index >= 0) r.config.addableLists()[index] as MutableList<T> else list
        into += item
        if (r != null && index >= 0 && (r.startingUp || running == null)) r.keepThroughReloads(index, item)
        return into
    }

    // Takes [item] away again: from [list], from [into], and from the configuration in use now
    // (one kept through reloads is in each new one).
    private fun <T> takeFrom(list: MutableList<T>, into: MutableList<T>, item: T) {
        list -= item
        into -= item
        val r = liveOrNull() ?: return
        val index = addableLists().indexOfFirst { it === list }
        if (index < 0) return
        r.config.addableLists()[index].removeIf { it === item }
        r.forget(item)
    }

    /**
     * Runs [action] when the key [combination] is pressed, written however you like:
     * `bind("Super+Shift+Q")`, `bind("super shift q")`, `bind("super", "shift", "q")`,
     * `bind("wIn+Q")`, `bind("Super+Й")` (the same key as Super+Q). A wrong name is
     * reported right here.
     */
    @JvmSynthetic // Java uses the Consumer version below
    fun bind(vararg combination: String, action: Caulis.() -> Unit) = bind(combination.joinToString("+"), BindOptions(), action)

    /**
     * The same, behaving as [options] say:
     *
     * ```
     * bind("XF86AudioRaiseVolume", BindOptions(repeat = true, whileLocked = true)) { launch("wpctl set-volume @DEFAULT_SINK@ 5%+") }
     * bind("Super", BindOptions(description = "overview")) { desktop.toggleOverview() }   // a tap of Super alone
     * bind("Super+WheelDown") { run("workspace next") }
     * bind("MouseSide") { run("workspace back") }
     * ```
     */
    @JvmSynthetic
    fun bind(combination: String, options: BindOptions, action: Caulis.() -> Unit): Binding {
        KeyBindings.parseAllowingTap(combination) // a wrong name is reported here, not at the start
        return addBinding(Binding(combination, options, null, action))
    }

    // Adds [binding] (while running, its keys work at once).
    internal fun addBinding(binding: Binding): Binding {
        val r = liveOrNull()
        binding.owner = if (running == null && r != null) r.config else this
        addTo(bindings, binding)
        r?.rebindKeys()
        return binding
    }

    /**
     * Keys of a mode of their own (a "submap"): while it is on ([enterKeyMode], or the action
     * `mode resize`), these keys are the compositor's and the usual ones are not; keys not
     * named here go to programs. Quitting and text consoles always work.
     *
     * ```
     * bind("Super+R") { enterKeyMode("resize") }
     * keyMode("resize") {
     *     bind("Left", BindOptions(repeat = true)) { run("resize left") }
     *     bind("Escape") { enterKeyMode(null) }
     * }
     * ```
     */
    @JvmSynthetic
    fun keyMode(name: String, configure: KeyMode.() -> Unit) {
        require(name.isNotBlank() && name != "reset" && name != "default") { "a key mode needs a name of its own, not \"$name\"" }
        modeNames += name
        KeyMode(name).configure()
        running?.rebindKeys() // (one without keys yet: known at once)
    }

    /** The same for Java: `keyMode("resize", m -> m.bind("Escape", c -> c.enterKeyMode(null)))`. */
    fun keyMode(name: String, configure: Consumer<KeyMode>): Caulis = apply { keyMode(name) { configure.accept(this) } }

    /** The old name of [keyMode]. */
    @Deprecated("Renamed to keyMode (\"mode\" was also the backend)", ReplaceWith("keyMode(name, configure)"))
    fun mode(name: String, configure: KeyMode.() -> Unit) = keyMode(name, configure)

    // Every mode named (one without keys too: none of the usual keys while it is on).
    internal val modeNames = LinkedHashSet<String>()

    /** What [keyMode] binds keys with. */
    inner class KeyMode internal constructor(
        /** The key mode's name ("resize"). */
        val name: String,
    ) {
        /** A key of this mode (see [Caulis.bind]). */
        @JvmSynthetic
        fun bind(vararg combination: String, action: Caulis.() -> Unit) = bind(combination.joinToString("+"), BindOptions(), action)

        /** A key of this mode behaving as [options] say (see [Caulis.bind]). */
        @JvmSynthetic
        fun bind(combination: String, options: BindOptions, action: Caulis.() -> Unit): Binding {
            KeyBindings.parseAllowingTap(combination)
            return addBinding(Binding(combination, options, name, action))
        }

        /** The same for Java. */
        fun bind(combination: String, action: Consumer<Caulis>): Binding = bind(combination, BindOptions()) { action.accept(this) }

        /** The same for Java. */
        fun bind(combination: String, options: BindOptions, action: Consumer<Caulis>): Binding = bind(combination, options) { action.accept(this) }
    }

    /** Turns the key mode [name] on (see [keyMode]); null: back to the usual keys. */
    fun enterKeyMode(name: String?) = live().enterMode(name)

    /** The old name of [enterKeyMode]. */
    @Deprecated("Renamed to enterKeyMode", ReplaceWith("enterKeyMode(name)"))
    fun enterMode(name: String?) = enterKeyMode(name)

    /** The key mode that is on (null: the usual keys). */
    val keyModeNow: String? get() = liveOrNull()?.keyMode

    /** The old name of [keyModeNow]. */
    @Deprecated("Renamed to keyModeNow", ReplaceWith("keyModeNow"))
    val keyMode: String? get() = keyModeNow

    // --- input --------------------------------------------------------------------------------

    /**
     * How mice, trackballs, touchpads and drawing tablets behave — all of them, touchpads,
     * tablets, and single devices by name (`my-compositor --devices` lists them):
     *
     * ```
     * input.pointers.speed = -0.3
     * input.pointers.accelProfile = AccelProfile.FLAT
     * input.touchpads.naturalScroll = true
     * input.touchpads.disableWhileTyping = true
     * input.devices["Logitech G Pro*"] = DeviceSettings().apply { speed = -0.6 }
     * input.tablets.monitor = "DP-1"                          // the pen covers this monitor
     * input.tablets.area = TabletArea(0.0, 0.0, 0.5, 0.5)     // with a quarter of the tablet
     * ```
     */
    val input = InputSettings()

    /** Held keys repeat this many times a second (0: never). Changed while running: at once. */
    var repeatRate = Seat.REPEAT_RATE
        set(value) {
            require(value in 0..1000) { "repeatRate is 0 to 1000, not $value" }
            field = value
            running?.applyKeyRepeat()
        }

    /** How long a key is held before it repeats, in milliseconds. Changed while running: at once. */
    var repeatDelayMillis: Long = Seat.REPEAT_DELAY.toLong()
        set(value) {
            require(value in 1..10_000) { "repeatDelayMillis is 1 to 10000, not $value" }
            field = value
            running?.applyKeyRepeat()
        }

    /** The old name of [repeatDelayMillis]. */
    @Deprecated("Renamed to repeatDelayMillis", ReplaceWith("repeatDelayMillis"))
    var repeatDelay: Int
        get() = repeatDelayMillis.toInt()
        set(value) {
            repeatDelayMillis = value.toLong()
        }

    /** The keyboard gets to the window under the pointer as soon as the pointer is over it. */
    var focusFollowsMouse = false

    /** A window asking for the focus with a good token gets it (false: it is only marked urgent). */
    var focusOnActivate = true

    /**
     * A key, a click or the pointer moving switches screens on again that were switched off
     * (dpms off) — after [wakeScreensAfterMillis], so the key that switched them off does not.
     */
    var wakeScreensOnInput = true

    /** Input sooner than this after screens were switched off does not wake them (see [wakeScreensOnInput]). */
    var wakeScreensAfterMillis = 1000L
        set(value) {
            require(value in 0..60_000) { "wakeScreensAfterMillis is 0 to 60000, not $value" }
            field = value
        }

    /** A fullscreen window on screen keeps the computer awake (videos, games): no idle timers then. */
    var fullscreenKeepsAwake = false

    internal class IdleWatch(val seconds: Long, val idle: Caulis.() -> Unit, val resume: Caulis.() -> Unit)
    internal val idleWatches = ArrayList<IdleWatch>()

    /**
     * Runs [idle] after [seconds] without a key, a click or the pointer moving, and [resume]
     * when someone is back. Programs that keep a window on screen (video players), and
     * [fullscreenKeepsAwake], hold it off:
     *
     * ```
     * onIdle(300, resume = { run("dpms on") }) { run("dpms off") }
     * onIdle(600) { lock() }
     * ```
     *
     * Added while running, it counts from then.
     */
    @JvmSynthetic
    fun onIdle(seconds: Long, resume: Caulis.() -> Unit = {}, idle: Caulis.() -> Unit) {
        require(seconds in 1..86_400) { "onIdle: 1 to 86400 seconds, not $seconds" }
        idleWatches += IdleWatch(seconds, idle, resume)
        running?.applyIdleWatches()
    }

    /** The same for Java: [idle] after [seconds] without input, [resume] when someone is back. */
    fun onIdle(seconds: Long, idle: Consumer<Caulis>, resume: Consumer<Caulis>): Caulis = apply {
        onIdle(seconds, resume = { resume.accept(this) }) { idle.accept(this) }
    }

    /** The same for Java, with nothing on resume. */
    fun onIdle(seconds: Long, idle: Consumer<Caulis>): Caulis = apply { onIdle(seconds) { idle.accept(this) } }

    internal val keyHandlers = ArrayList<Caulis.(KeyInput) -> Boolean>()
    internal val motionHandlers = ArrayList<Caulis.(dx: Double, dy: Double) -> Boolean>()
    internal val buttonHandlers = ArrayList<Caulis.(button: Int, pressed: Boolean) -> Boolean>()
    internal val scrollHandlers = ArrayList<Caulis.(vertical: Boolean, value: Double, steps: Double) -> Boolean>()

    /**
     * Every key, before the compositor's keys and programs see it (wlroots style): true takes
     * it — no binding and no program gets it. Take a key's release when you took its press
     * (a program would keep a key it saw going down). On a locked screen only the laptop's
     * special keys (XF86…) are told: never what is typed into the locker. The last added is
     * asked first.
     */
    @JvmSynthetic
    fun onKey(handler: Caulis.(key: KeyInput) -> Boolean): AutoCloseable = listenTo(keyHandlers, handler)

    /** The pointer moved by [dx], [dy] (units): true takes the movement (the pointer stays where it is). Not on a locked screen. */
    @JvmSynthetic
    fun onPointerMotion(handler: Caulis.(dx: Double, dy: Double) -> Boolean): AutoCloseable = listenTo(motionHandlers, handler)

    /**
     * For tests (headless, CAULIS_MODE=headless): input as if it came from a mouse — the
     * pointer to ([x], [y]) on the desktop, then a click of [button] (272 left, 273 right, 274
     * middle; 0: none) — through everything real input goes through: the hooks
     * ([onPointerMotion], [onPointerButton]), the bindings, the windows and widgets. Call it on the
     * compositor's thread (from a key, a command, [post]), as the other simulate functions.
     */
    @JvmOverloads
    fun simulatePointer(x: Double, y: Double, button: Int = 0) {
        val running = live()
        val time = (System.nanoTime() / 1_000_000).toInt()
        running.pointerMovedTo(time, x, y)
        if (button != 0) {
            running.pointerButton(time, button, true)
            running.pointerButton(time + 20, button, false)
        }
    }

    /** For tests: the mouse moved by [dx], [dy] (as a mouse moves it: the configuration's onPointerMotion sees it). */
    fun simulateMotion(dx: Double, dy: Double) {
        val running = live()
        running.pointerMovedBy((System.nanoTime() / 1_000_000).toInt(), dx, dy, dx, dy)
    }

    /** For tests: a mouse [button] (272 left) pressed or let go where the pointer is (see [simulatePointer]). */
    fun simulateButton(button: Int, pressed: Boolean) {
        val running = live()
        running.pointerButton((System.nanoTime() / 1_000_000).toInt(), button, pressed)
    }

    /** For tests: a key ([evdevCode], e.g. 56 left Alt, 42 left Shift, 30 A) pressed or let go, as from a keyboard (see [simulatePointer]). */
    fun simulateKey(evdevCode: Int, pressed: Boolean) {
        val running = live()
        running.key((System.nanoTime() / 1_000_000).toInt(), evdevCode, pressed)
    }

    /** For tests: the wheel turned [notches] (positive: down), as from a mouse (see [simulatePointer]). */
    fun simulateScroll(notches: Int) {
        val running = live()
        running.pointerAxis((System.nanoTime() / 1_000_000).toInt(), dev.caulis.core.input.Scroll(dev.caulis.core.input.Scroll.Source.WHEEL, vertical = 15.0 * notches, vertical120 = 120 * notches))
    }

    /**
     * For tests: a pen over ([x], [y]) on the desktop (desktop units: the tablet's own mapping is
     * left out) pressing with [pressure] (0..1); it comes near a pretend tablet first if it was
     * away. [down] true touches the tablet with its tip, false lifts it (null: as it is). It goes
     * where a real pen goes: to a program that takes tablet events as a pen, elsewhere as the
     * pointer ([input].tablets settings count). On the compositor's thread, as [simulatePointer].
     */
    @JvmOverloads
    fun simulatePen(x: Double, y: Double, pressure: Double = 0.0, down: Boolean? = null) {
        val running = live()
        val time = (System.nanoTime() / 1_000_000).toInt()
        val pen = running.testPen
        running.testTablet.settings = input.forTablet(running.testTablet.name)
        if (!running.testPenNear) {
            running.testPenNear = true
            running.desktop.tabletProximity(time, running.testTablet, pen, x, y, TabletAxes(pressure, distance = 0.0, tiltX = 0.0, tiltY = 0.0), near = true)
        } else {
            running.desktop.tabletMotion(time, pen, x, y, TabletAxes(pressure))
        }
        if (down != null) running.desktop.tabletTip(time, pen, down)
    }

    /** For tests: a button of the pen (0x14b its lower one, 0x14c its upper one) pressed or let go (see [simulatePen]). */
    fun simulatePenButton(button: Int, pressed: Boolean) {
        val running = live()
        running.desktop.tabletButton((System.nanoTime() / 1_000_000).toInt(), running.testPen, button, pressed)
    }

    /** For tests: the pen goes away from the tablet (lifted first, if it touched; see [simulatePen]). */
    fun simulatePenAway() {
        val running = live()
        if (!running.testPenNear) return
        running.testPenNear = false
        val x = running.desktop.pointerX
        val y = running.desktop.pointerY
        running.desktop.tabletProximity((System.nanoTime() / 1_000_000).toInt(), running.testTablet, running.testPen, x, y, TabletAxes.NONE, near = false)
    }

    /**
     * For tests: a button (0 the first) of a pretend tablet pad (four buttons) pressed or let go —
     * a binding ("Pad1") or the program with the keyboard gets it. On the compositor's thread.
     */
    fun simulatePadButton(button: Int, pressed: Boolean) {
        val running = live()
        running.padButton((System.nanoTime() / 1_000_000).toInt(), running.testPad, button, pressed)
    }

    /** A mouse button (272 left, 273 right, 274 middle): true takes it. Not on a locked screen. */
    @JvmSynthetic
    fun onPointerButton(handler: Caulis.(button: Int, pressed: Boolean) -> Boolean): AutoCloseable = listenTo(buttonHandlers, handler)

    /** The wheel or two-finger scrolling: [value] in units, [steps] in notches (0 when smooth). True takes it. Not on a locked screen. */
    @JvmSynthetic
    fun onScroll(handler: Caulis.(vertical: Boolean, value: Double, steps: Double) -> Boolean): AutoCloseable = listenTo(scrollHandlers, handler)

    internal val gestureHandlers = ArrayList<Caulis.(dev.caulis.core.input.Gesture) -> Boolean>()

    /**
     * Touchpad gestures as they come — swipes, pinches and holds with their fingers, movement,
     * scale and rotation — for gestures of your own. [handler] is asked at each gesture's
     * start; true takes the gesture: every update and its end go to it alone (not to the
     * built-in gestures, nor to programs). The last added is asked first.
     *
     * ```
     * onGesture { g ->
     *     when (g) {
     *         is Gesture.SwipeBegin -> g.fingers == 3          // three-finger swipes are mine
     *         is Gesture.SwipeUpdate -> { moveMyView(g.dx, g.dy); true }
     *         else -> false                                     // (only the start's answer counts)
     *     }
     * }
     * ```
     */
    @JvmSynthetic
    fun onGesture(handler: Caulis.(gesture: dev.caulis.core.input.Gesture) -> Boolean): AutoCloseable = listenTo(gestureHandlers, handler)

    internal val lidActions = ArrayList<Caulis.(Boolean) -> Unit>()

    /** Runs [action] when the laptop's lid closes (true) or opens (false); on the screen only. */
    @JvmSynthetic
    fun onLid(action: Caulis.(closed: Boolean) -> Unit): AutoCloseable = listen(lidActions, action)

    internal val reloadActions = ArrayList<Caulis.() -> Unit>()

    /** Runs [action] after the configuration was reloaded (see [hotReload]); [onStart] runs only once. */
    @JvmSynthetic
    fun onReload(action: Caulis.() -> Unit): AutoCloseable = listen(reloadActions, action)

    /**
     * Runs [action] once everything is ready (e.g. to start a panel or wallpaper program) —
     * once only: not again after a reload.
     *
     * The rule for what lives how long: what [action] starts lives as long as the compositor —
     * timers ([after], [every]), widgets and drawings, the bus's objects and listeners
     * ([sessionBus]), event listeners ([onWindowOpened], ...) and keys added in it stay through
     * reloads. What the configuration itself asks for (outside onStart) belongs to that
     * configuration: a reload takes it away, and the reloaded code asks for it anew. A
     * configuration a reload replaced still reaches the running compositor ([desktop],
     * [post], [notify], [launch], ...), so threads and timers it started go on working.
     */
    @JvmSynthetic
    fun onStart(action: Caulis.() -> Unit) {
        startActions += action
    }

    internal val rules = ArrayList<WindowRule>()

    /**
     * A rule for some windows: which ones, where they open and how they look (see [WindowRule]):
     *
     * ```
     * rule { appId("firefox"); workspace = 2 }
     * rule { appId("spotify"); workspace = 9; focus = false }
     * rule { appId("pavucontrol"); floating = true; size(700, 500); center() }
     * rule { title("Picture-in-Picture"); floating = true; size(480, 270); position(20, 20) }
     * rule { appId("mpv"); titleBars = TitleBars.NONE; opacity = 1f }
     * ```
     */
    @JvmSynthetic
    fun rule(configure: WindowRule.() -> Unit) {
        rules += WindowRule().apply(configure)
        running?.rulesChanged() // (while running: title bars and margins of open windows at once)
    }

    /**
     * A rule for some panels (bars, launchers, notifications), see [PanelRule]:
     * `panelRule { namespace("fuzzel"); dimAround = 0.4f; blur = true }`.
     */
    @JvmSynthetic
    fun panelRule(configure: PanelRule.() -> Unit) {
        panelRules += PanelRule().apply(configure)
    }

    /**
     * A shader the whole screen goes through last ([ScreenShader][dev.caulis.core.ScreenShader]): `ScreenShader.GRAYSCALE`,
     * `ScreenShader.warm(0.4f)`, your own code or file. Null: none. Can be changed while running.
     */
    var screenShader: dev.caulis.core.ScreenShader? = null
        set(value) {
            field = value
            running?.let { it.desktop.screenShader = value }
        }

    /**
     * Windows for which [rule] is true float above the tiled ones instead of joining the
     * layout, e.g. `floatWhen { it.appId == "pavucontrol" }`. Dialogs always float.
     */
    @JvmSynthetic
    fun floatWhen(rule: (Window) -> Boolean) {
        floatRules += rule
    }

    /**
     * Changes the [effects] of some windows: [rule] gets a window and the effects it would
     * have, and returns the ones it gets, e.g. no blur and full opacity for videos:
     * `effectsFor { window, fx -> if (window.appId == "mpv") fx.copy(blur = null, inactiveOpacity = 1f) else fx }`.
     */
    @JvmSynthetic
    fun effectsFor(rule: (Window, Effects) -> Effects) {
        effectRules += rule
    }

    /**
     * Effects for panels, docks and bars (none by default), by what they call themselves:
     * `panelEffects { if (it.namespace == "waybar") Effects(blur = Blur()) else null }`.
     * Null = no effects.
     */
    @JvmSynthetic
    fun panelEffects(rule: (LayerSurface) -> Effects?) {
        panelEffectRules += rule
    }

    /**
     * A special workspace, shown and hidden with [key] above whatever workspace is on screen:
     *
     * ```
     * scratchpad("terminal", key = "Super+Grave", command = "foot --app-id terminal")
     * scratchpad("music", key = "Super+M", command = "spotify", style = Scratchpad.Edge(Side.RIGHT)) { it.appId == "spotify" }
     * scratchpad("notes", key = "Super+N", command = "gnome-text-editor", style = Scratchpad.Overlay())
     * ```
     *
     * The first press starts [command] when none of its windows is open; the window slides
     * in as soon as it appears. Windows belong to it when [match] says so — by default
     * when their app id is [name]. Styles: [Scratchpad.Edge] (slides in from an edge, the
     * default), [Scratchpad.Center] (floating in the middle), [Scratchpad.Overlay] (a whole
     * workspace above the current one).
     */
    @JvmSynthetic
    fun scratchpad(
        name: String,
        key: String? = null,
        command: String? = null,
        style: Scratchpad.Style = Scratchpad.Edge(),
        match: (Window) -> Boolean = { it.appId == name },
    ) {
        scratchpads += ScratchpadSetup(name, key, command, style, match)
        running?.applyScratchpads() // (while running: its key works at once)
    }

    /**
     * Runs [action] when the touchpad is swiped with [fingers] fingers towards [direction]
     * (when the fingers lift). By default, four fingers left/right go to the next/previous
     * workspace.
     */
    @JvmSynthetic
    fun swipe(fingers: Int, direction: Direction, action: Caulis.() -> Unit) {
        require(fingers in 3..5) { "Touchpads report gestures with 3 to 5 fingers, not $fingers" }
        swipes += Triple(fingers, direction, action)
    }

    /** The same for Java. */
    fun swipe(fingers: Int, direction: Direction, action: Consumer<Caulis>): Caulis = apply { swipe(fingers, direction) { action.accept(this) } }

    /** Shows or hides the scratchpad [name] (starting its program if needed). */
    fun toggleScratchpad(name: String) {
        live().toggleScratchpad(name)
    }

    /**
     * Shows the special workspace [name] over the workspace on the focused monitor, or hides
     * it (Hyprland's `togglespecialworkspace`); "special" is the one without a name.
     */
    @JvmOverloads
    fun toggleSpecialWorkspace(name: String = dev.caulis.core.SpecialWorkspaces.DEFAULT_NAME) {
        live().desktop.toggleSpecialWorkspace(name)
    }

    /**
     * Moves [window] (null: the focused one) into the special workspace [name], shown with
     * it — or, [silent], waiting there unseen (Hyprland's `movetoworkspacesilent special:name`).
     */
    @JvmOverloads
    fun moveToSpecialWorkspace(window: Window? = null, name: String = dev.caulis.core.SpecialWorkspaces.DEFAULT_NAME, silent: Boolean = false) {
        val desktop = live().desktop
        (window ?: desktop.focusedWindow)?.let { desktop.moveToSpecialWorkspace(it, name, silent) }
    }

    /** Moves [window] (null: the focused one) out of its special workspace onto the workspace on screen. */
    @JvmOverloads
    fun moveOutOfSpecialWorkspace(window: Window? = null) {
        val desktop = live().desktop
        (window ?: desktop.focusedWindow)?.let { desktop.moveOutOfSpecialWorkspace(it) }
    }

    /** Runs [action] when a window appears, e.g. to move it to a workspace. */
    @JvmSynthetic
    fun onWindowOpened(action: Caulis.(Window) -> Unit): AutoCloseable = listen(windowOpened, action)

    /** Runs [action] when a window goes away. */
    @JvmSynthetic
    fun onWindowClosed(action: Caulis.(Window) -> Unit): AutoCloseable = listen(windowClosed, action)

    /** Runs [action] when a window's title or app id changed. */
    @JvmSynthetic
    fun onWindowChanged(action: Caulis.(Window) -> Unit): AutoCloseable = listen(windowChanged, action)

    /** Runs [action] when another window gets the keyboard (null = none). */
    @JvmSynthetic
    fun onFocusChanged(action: Caulis.(Window?) -> Unit): AutoCloseable = listen(focusChanged, action)

    /** Runs [action] with the number of the workspace that is now shown. */
    @JvmSynthetic
    fun onWorkspaceChanged(action: Caulis.(Int) -> Unit): AutoCloseable = listen(workspaceChanged, action)

    /** Runs [action] with a monitor's connector ("DP-2") when it is plugged in (or switched on). */
    @JvmSynthetic
    fun onMonitorAdded(action: Caulis.(String) -> Unit): AutoCloseable = listen(monitorAdded, action)

    /** Runs [action] with a monitor's connector when it is unplugged (or switched off). */
    @JvmSynthetic
    fun onMonitorRemoved(action: Caulis.(String) -> Unit): AutoCloseable = listen(monitorRemoved, action)

    /**
     * Runs [action] just before this configuration is replaced by a reloaded one — to stop
     * what it started (its timers end by themselves).
     */
    @JvmSynthetic
    fun onUnload(action: Caulis.() -> Unit): AutoCloseable = listen(unloadActions, action)

    internal val evaluators = HashMap<String, (String) -> Result<String>>()

    /**
     * For a configuration language (caulis-lua): code typed at `--repl` or sent with `--lua`
     * runs in it — [evaluate] returns what the code returned as text, or the mistake.
     */
    fun evaluator(language: String, evaluate: (String) -> Result<String>) {
        evaluators[language] = evaluate
    }

    internal val ownActions = LinkedHashMap<String, dev.caulis.wm.config.Actions.Own>()

    /**
     * An action of your compositor's own, for text: keys in config.json and config.lua,
     * `my-compositor --do`, the IPC socket, [run] — e.g.
     *
     * ```
     * action("toggle-bar", description = "shows or hides my bar") { _, _ -> bar.toggle() }
     * action("gaps", argument = "number", description = "sets the gaps") { argument, _ -> layout = Dwindle(Gaps(argument.toInt())) }
     * ```
     *
     * [run] gets the text after the name ("" if none) and the window it is for (null: the
     * focused one); it may throw IllegalArgumentException for a wrong argument. A built-in
     * action's name cannot be taken.
     */
    @JvmSynthetic
    fun action(name: String, argument: String = "", description: String = "", run: Caulis.(argument: String, window: Window?) -> Unit) {
        dev.caulis.wm.config.Actions.addOwn(name, argument, description)
        ownActions[name] = dev.caulis.wm.config.Actions.Own(name, argument, description, run)
    }

    /** The same for Java. */
    fun action(name: String, argument: String, description: String, run: ActionHandler): Caulis = apply {
        action(name, argument, description) { text, window -> run.run(this, text, window) }
    }

    // --- the same for Java (each returns this, so calls can be chained) -----------------
    // (Kotlin has the settings themselves; these are Java's way to chain them.)

    fun layout(layout: Layout): Caulis = apply { this.layout = layout }
    fun terminal(command: String): Caulis = apply { terminal = command }
    fun lockCommand(command: String): Caulis = apply { lockCommand = command }
    fun scale(scale: Double): Caulis = apply { this.scale = scale }
    fun workspaceMode(mode: WorkspaceMode): Caulis = apply { workspaceMode = mode }
    fun monitor(name: String, settings: Consumer<MonitorSettings>): Caulis = apply { monitor(name) { settings.accept(this) } }
    fun nestedMonitors(count: Int): Caulis = apply { nestedMonitors = count }
    fun waylandFirst(on: Boolean): Caulis = apply { waylandFirst = on }
    fun x11Program(name: String): Caulis = apply { x11Programs += name }
    fun sharpJavaScaling(on: Boolean): Caulis = apply { sharpJavaScaling = on }
    fun titleBars(titleBars: TitleBars): Caulis = apply { this.titleBars = titleBars }
    fun noticeStyle(style: NoticeStyle): Caulis = apply { noticeStyle = style }
    fun animations(animations: Animations): Caulis = apply { this.animations = animations }
    fun animations(configure: Consumer<dev.caulis.core.AnimationsBuilder>): Caulis = apply { animations { configure.accept(this) } }
    fun configName(name: String): Caulis = apply { configName = name }
    fun background(background: Background): Caulis = apply { this.background = background }
    fun backgroundFor(rule: java.util.function.BiFunction<String, Int, Background?>): Caulis = apply { backgroundFor { monitor, workspace -> rule.apply(monitor, workspace) } }
    fun noticesFromLog(rule: java.util.function.Predicate<Log.Entry>): Caulis = apply { noticesFromLog = { rule.test(it) } }
    fun onNotice(action: java.util.function.BiConsumer<Caulis, Notice>): Caulis = apply { onNotice { action.accept(this, it) } }
    fun titleBarsFor(rule: java.util.function.Function<Window, TitleBars?>): Caulis = apply { titleBarsFor { rule.apply(it) } }
    fun logLevel(level: Int): Caulis = apply { logLevel = level }

    /** The old name of [log] for Java. */
    @Deprecated("Use log(area, level), which returns the Caulis too", ReplaceWith("log(area, level)"))
    fun logArea(area: String, level: Int): Caulis = log(area, level)

    /** The old way to set [cursorTheme] and [cursorSize] together. */
    @Deprecated("Use cursorTheme(theme) and cursorSize(size)", ReplaceWith("cursorTheme(theme).cursorSize(size)"))
    fun cursor(theme: String?, size: Int?): Caulis = cursorTheme(theme).cursorSize(size)
    fun cursorTheme(theme: String?): Caulis = apply { cursorTheme = theme }
    fun cursorSize(size: Int?): Caulis = apply { cursorSize = size }

    /** The old name of [backend] for Java. */
    @Deprecated("Use backend(Backend)", ReplaceWith("backend(Caulis.Backend.valueOf(mode.name))"))
    @Suppress("DEPRECATION")
    fun mode(mode: Mode): Caulis = apply { this.mode = mode }
    fun backend(backend: Backend): Caulis = apply { this.backend = backend }
    @JvmOverloads
    fun keyboard(layouts: String, options: String = ""): Caulis = apply {
        keyboardLayouts = layouts
        keyboardOptions = options
    }
    fun keys(configure: Consumer<Keys>): Caulis = apply { configure.accept(keys) }
    fun bind(combination: String, action: Consumer<Caulis>): Caulis = apply { bind(combination) { action.accept(this) } }
    fun bind(combination: List<String>, action: Consumer<Caulis>): Caulis = apply { bind(*combination.toTypedArray()) { action.accept(this) } }
    fun bind(combination: String, options: BindOptions, action: Consumer<Caulis>): Caulis = apply { bind(combination, options) { action.accept(this) } }
    fun onStart(action: Consumer<Caulis>): Caulis = apply { onStart { action.accept(this) } }
    fun onReload(action: Consumer<Caulis>): Caulis = apply { onReload { action.accept(this) } }
    fun onUnload(action: Consumer<Caulis>): Caulis = apply { onUnload { action.accept(this) } }
    fun onSleep(action: Consumer<Caulis>): Caulis = apply { onSleep { action.accept(this) } }
    fun onWake(action: Consumer<Caulis>): Caulis = apply { onWake { action.accept(this) } }
    fun onQuit(action: Consumer<Caulis>): Caulis = apply { onQuit { action.accept(this) } }
    fun focusStealingRule(rule: java.util.function.BiPredicate<Window, dev.caulis.core.WindowRequest>): Caulis = apply { focusStealingRule = { w, why -> rule.test(w, why) } }
    fun onKey(handler: java.util.function.BiPredicate<Caulis, KeyInput>): Caulis = apply { onKey { handler.test(this, it) } }
    fun onPointerMotion(handler: PointerMotionHandler): Caulis = apply { onPointerMotion { dx, dy -> handler.moved(this, dx, dy) } }
    fun onPointerButton(handler: PointerButtonHandler): Caulis = apply { onPointerButton { button, pressed -> handler.button(this, button, pressed) } }
    fun onScroll(handler: ScrollHandler): Caulis = apply { onScroll { vertical, value, steps -> handler.scrolled(this, vertical, value, steps) } }
    fun onGesture(handler: java.util.function.BiPredicate<Caulis, dev.caulis.core.input.Gesture>): Caulis = apply { onGesture { handler.test(this, it) } }
    fun onFocusChanged(action: java.util.function.BiConsumer<Caulis, Window?>): Caulis = apply { onFocusChanged { action.accept(this, it) } }
    fun onWorkspaceChanged(action: java.util.function.BiConsumer<Caulis, Int>): Caulis = apply { onWorkspaceChanged { action.accept(this, it) } }
    fun onWindowChanged(action: java.util.function.BiConsumer<Caulis, Window>): Caulis = apply { onWindowChanged { action.accept(this, it) } }
    fun onMonitorAdded(action: java.util.function.BiConsumer<Caulis, String>): Caulis = apply { onMonitorAdded { action.accept(this, it) } }
    fun onMonitorRemoved(action: java.util.function.BiConsumer<Caulis, String>): Caulis = apply { onMonitorRemoved { action.accept(this, it) } }
    fun onLid(action: java.util.function.BiConsumer<Caulis, Boolean>): Caulis = apply { onLid { action.accept(this, it) } }
    fun onColorSchemeChanged(action: java.util.function.BiConsumer<Caulis, ColorScheme>): Caulis = apply { onColorSchemeChanged { action.accept(this, it) } }
    fun workspace(number: Int, configure: Consumer<WorkspaceRule>): Caulis = apply { workspace(number) { configure.accept(this) } }
    fun rule(configure: Consumer<WindowRule>): Caulis = apply { rule { configure.accept(this) } }
    fun panelRule(configure: Consumer<PanelRule>): Caulis = apply { panelRule { configure.accept(this) } }
    fun floatWhen(rule: java.util.function.Predicate<Window>): Caulis = apply { floatWhen { rule.test(it) } }
    fun effects(effects: Effects): Caulis = apply { this.effects = effects }
    fun specialWorkspaces(settings: dev.caulis.core.SpecialWorkspaces): Caulis = apply { this.specialWorkspaces = settings }
    fun effectsFor(rule: java.util.function.BiFunction<Window, Effects, Effects>): Caulis = apply { effectsFor { window, fx -> rule.apply(window, fx) } }
    fun panelEffects(rule: java.util.function.Function<LayerSurface, Effects?>): Caulis = apply { panelEffects { rule.apply(it) } }
    fun scratchpad(name: String, key: String?, command: String?, style: Scratchpad.Style): Caulis =
        apply { scratchpad(name, key, command, style) { it.appId == name } }
    fun scratchpad(name: String, key: String?, command: String?, style: Scratchpad.Style, match: java.util.function.Predicate<Window>): Caulis =
        apply { scratchpad(name, key, command, style) { match.test(it) } }
    fun onWindowOpened(action: java.util.function.BiConsumer<Caulis, Window>): Caulis = apply { onWindowOpened { action.accept(this, it) } }
    fun onWindowClosed(action: java.util.function.BiConsumer<Caulis, Window>): Caulis = apply { onWindowClosed { action.accept(this, it) } }

    // --- while running ------------------------------------------------------------------

    // The running compositor while this is its configuration (null before, and once a reload
    // replaced it): settings changed here apply to the desktop only then.
    internal var running: Running? = null

    // The compositor this configuration ran in — kept after a reload replaced it, so what it
    // started (threads, timers from onStart) still reaches the desktop through it.
    internal var session: Running? = null

    // The compositor to act on: this configuration's, or the one a replaced one ran in.
    internal fun liveOrNull(): Running? = running ?: session?.takeIf { it.isOpen }

    internal fun live(): Running = liveOrNull() ?: throw IllegalStateException("Caulis is not running yet")

    // What the checks before starting warned about: shown on screen too, once running.
    internal var startWarnings: List<Preflight.Problem> = emptyList()

    // What is wrong in config.json (shown on screen once running).
    internal var configProblems: List<String> = emptyList()
    // What configuration files (config.json, config.lua, ...) added to the lists below, so that
    // applying them again replaces it instead of adding it twice.
    internal var configAdded: List<Pair<MutableList<*>, List<Any?>>> = emptyList()

    // The lists a configuration file can add to.
    internal fun addableLists(): List<MutableList<*>> = listOf(
        titleBarRules, noticeListeners, bindings, startActions, floatRules, effectRules, panelEffectRules, panelRules,
        scratchpads, swipes, windowOpened, windowClosed, focusChanged, workspaceChanged, reloadActions, rules,
        windowChanged, monitorAdded, monitorRemoved, unloadActions, waitingTimers, sleepActions, wakeActions,
        workspaceRules, lidActions, idleWatches, gestureHandlers, keyHandlers, motionHandlers, buttonHandlers, scrollHandlers,
        colorSchemeListeners, quitActions, revealActions, autostart.list,
    )

    /** The window manager (workspaces, focus, layouts). Only while running. */
    val desktop: Desktop get() = live().desktop

    /** The clipboard: what is on it, and putting something there yourself (once it runs). */
    val clipboard: dev.caulis.core.DataDeviceManager get() = live().clipboard

    /** The window with the keyboard focus, if any. */
    val focusedWindow: Window? get() = liveOrNull()?.desktop?.focusedWindow

    /**
     * Starts [command] (run by /bin/sh) as a program inside the compositor — the Wayland way
     * first ([waylandFirst]), or through X11 with [x11] = true.
     */
    @JvmOverloads
    fun launch(command: String, x11: Boolean = false) {
        live().launch(command, x11)
    }

    /**
     * Starts the program [command] (its words: no shell) inside the compositor, as [launch]
     * does, in [directory] (null: the compositor's own), and gives back its process — to know
     * its windows by their pid, to write to it (its `outputStream` is its input), or to stop
     * it. Its output goes where the compositor's does.
     * Null if it cannot be started (the log says why).
     */
    @JvmOverloads
    fun spawn(command: List<String>, directory: java.nio.file.Path? = null): Process? =
        live().spawn(command, directory?.toFile())

    /** Starts the [terminal] (as the default Super+Return does). */
    fun openTerminal() = launch(terminal)

    /**
     * The environment programs started inside get — WAYLAND_DISPLAY, DISPLAY, XDG_CURRENT_DESKTOP,
     * the cursor and your [environment] — for programs you start some other way.
     */
    fun programEnvironment(): Map<String, String> = live().programEnvironment()

    /**
     * The session bus (D-Bus), on the compositor's thread: offer objects of your own to other
     * programs (`export`, `requestName`, `emitSignal`) or call theirs. Connected when first
     * asked for, shared, closed when the compositor ends; null if there is none.
     *
     * What this configuration exports and listens to ([DBusScope][dev.caulis.core.dbus.DBusScope])
     * is taken away when a reload replaces it — but what [onStart] does stays for the whole
     * session (see [onStart]).
     */
    val sessionBus: dev.caulis.core.dbus.DBusScope? get() = busScope(session = true)

    /**
     * The system bus — NetworkManager, BlueZ, UPower, power profiles — for your code (null when
     * there is none), on the compositor's thread like the session bus, and kept the same way.
     * Only while running.
     */
    val systemBus: dev.caulis.core.dbus.DBusScope? get() = busScope(session = false)

    // This configuration's view of a bus (made when first asked for, taken away with it) — or
    // the compositor's own while onStart runs and for a configuration a reload replaced.
    private val busScopes = HashMap<Boolean, dev.caulis.core.dbus.DBusScope>()

    private fun busScope(session: Boolean): dev.caulis.core.dbus.DBusScope? {
        val r = live()
        if (r.startingUp || running == null) return r.sessionScope(session)
        busScopes[session]?.let { return it }
        val connection = (if (session) r.sessionBus else r.systemBus) ?: return null
        return dev.caulis.core.dbus.DBusScope(connection).also { scope ->
            busScopes[session] = scope
            added += scope
        }
    }

    /**
     * Runs [action] written as text, as in config.json's "bind" and on the IPC socket:
     * `run("workspace 3")`, `run("exec firefox")`, `run("close", window)` — on [window], or the
     * focused one (null). Throws IllegalArgumentException when it is not an action.
     */
    @JvmOverloads
    fun run(action: String, window: Window? = null) {
        dev.caulis.wm.config.Actions.parseFor(action)(this, window)
    }

    // Timers started with [after] (cancelled when this configuration is replaced by a reload).
    internal val timers = ArrayList<dev.caulis.event.EventLoop.Timer>()

    // What this configuration added to the running desktop (drawings): taken away on a reload.
    internal val added = ArrayList<AutoCloseable>()

    /**
     * A part of the compositor's own interface — a bar, a dock, a launcher ([dev.caulis.core.Widget];
     * caulis-compose's `compose { }` makes one in Compose); close the result to take it away.
     * Asked for in the configuration itself, it is shown when it runs; a reload takes it away
     * (and closes it).
     */
    fun addWidget(widget: dev.caulis.core.Widget): AutoCloseable {
        val r = liveOrNull() ?: return later { addWidget(widget) }
        val handle = r.desktop.addWidget(widget)
        val closing = AutoCloseable {
            handle.close()
            (widget as? AutoCloseable)?.close()
        }
        return owned(r, closing)
    }

    // What [r] runs for this configuration: taken away with it on a reload — or kept for the
    // whole session when asked for in onStart, or by a configuration a reload replaced.
    private fun owned(r: Running, resource: AutoCloseable): AutoCloseable {
        val list = if (r.startingUp || running == null) r.sessionResources else added
        list += resource
        return AutoCloseable {
            list -= resource
            resource.close()
        }
    }

    /**
     * Pictures of your own that take no input — a bar, a border, rounded screen corners — at
     * [level] (see [dev.caulis.core.Drawing]); close the result to take it away. Asked for in
     * the configuration itself, it is shown when it runs; a reload takes it away.
     */
    fun addDrawing(level: dev.caulis.core.DrawingLevel, drawing: dev.caulis.core.Drawing): AutoCloseable {
        val r = liveOrNull() ?: return later { addDrawing(level, drawing) }
        return owned(r, r.desktop.addDrawing(level, drawing))
    }

    // A timer asked for before the compositor runs (in the configuration itself): it starts
    // when this configuration does, and ends with it.
    internal class WaitingTimer(val start: () -> AutoCloseable) : AutoCloseable {
        private var started: AutoCloseable? = null
        private var cancelled = false

        fun begin() {
            if (!cancelled && started == null) started = start()
        }

        override fun close() {
            cancelled = true
            started?.close()
        }
    }

    internal val waitingTimers = ArrayList<WaitingTimer>()

    // The timers asked for before this configuration ran start now (it runs).
    internal fun startWaitingTimers() = waitingTimers.toList().forEach { it.begin() }

    // Not running yet: [start] waits until this configuration runs.
    private fun later(start: () -> AutoCloseable): AutoCloseable = WaitingTimer(start).also { waitingTimers += it }

    /**
     * Runs [action] once, [millis] from now, on the compositor's thread; close the result to
     * cancel it. Asked for in the configuration itself, it counts from when it runs. A reload
     * cancels the timers of the configuration before (not those started in [onStart]).
     */
    @JvmSynthetic
    fun after(millis: Long, action: Caulis.() -> Unit): AutoCloseable {
        val r = liveOrNull() ?: return later { after(millis, action) }
        // (Asked for in onStart, or by a replaced configuration: the session's, not cancelled by reloads.)
        val mine = !(r.startingUp || running == null)
        lateinit var timer: dev.caulis.event.EventLoop.Timer
        timer = r.loop.after(millis.coerceAtLeast(0)) {
            timers -= timer
            r.safely("a timer") { action(this) }
        }
        if (mine) timers += timer
        return AutoCloseable {
            timer.cancel()
            timers -= timer
        }
    }

    /** The same for Java. */
    fun after(millis: Long, action: Consumer<Caulis>): AutoCloseable = after(millis) { action.accept(this) }

    /**
     * Runs [action] soon on the compositor's thread — callable from any thread (work done on
     * one of your own, then shown). Nothing happens if the compositor is not running.
     */
    @JvmSynthetic
    fun post(action: Caulis.() -> Unit) {
        val r = liveOrNull() ?: return
        r.loop.post { r.safely("posted work") { action(this) } }
    }

    /** The same for Java. */
    fun post(action: Consumer<Caulis>) = post { action.accept(this) }

    /**
     * Runs [action] every [millis] from now on, on the compositor's thread, until the result is
     * closed (or the configuration is reloaded). Asked for in the configuration itself, it
     * starts when it runs.
     */
    @JvmSynthetic
    fun every(millis: Long, action: Caulis.() -> Unit): AutoCloseable {
        require(millis >= 10) { "every: at least 10 milliseconds apart, not $millis" }
        val r = liveOrNull() ?: return later { every(millis, action) }
        val mine = !(r.startingUp || running == null)
        var stopped = false
        var timer: dev.caulis.event.EventLoop.Timer? = null
        fun next() {
            timer = r.loop.after(millis) {
                timers -= timer!!
                if (stopped) return@after
                r.safely("a repeating timer") { action(this) }
                if (!stopped) next()
            }.also { if (mine) timers += it }
        }
        next()
        return AutoCloseable {
            stopped = true
            timer?.let { it.cancel(); timers -= it }
        }
    }

    /** The same for Java. */
    fun every(millis: Long, action: Consumer<Caulis>): AutoCloseable = every(millis) { action.accept(this) }

    /** [window]'s number: the same while it is open, never used again (programs know it by this). */
    fun idOf(window: Window): Long = live().windowIds.of(window)

    /** The open window with the number [id] (see [idOf]), or null. */
    fun windowWithId(id: Long): Window? = liveOrNull()?.windowIds?.window(id)

    /** Moves the focused window to the monitor beside its own in [direction]; false if there is none. */
    fun moveToMonitorBeside(direction: Direction): Boolean {
        val window = focusedWindow ?: return false
        val target = desktop.monitorBeside(direction.dx, direction.dy, desktop.monitorOf(window)) ?: return false
        desktop.moveToMonitor(window, target)
        return true
    }

    /**
     * Locks the screen: with your [locker], else with [lockCommand]. When the screen is not
     * locked [lockShowMillis] later (the locker is not installed, or failed), a notice says so.
     */
    fun lock() {
        val own = locker
        if (own != null) {
            // (What it starts is the locker: its unlock may end the compositor's own lock screen.)
            liveOrNull()?.startingLocker { own(this) } ?: own(this)
            // (Only a locker that locked the desktop, or can say whether it shows, is watched.)
            if (lockerShows != null) liveOrNull()?.watchLock("your locker")
        } else {
            val running = live()
            // (No second try through X11: a locker is Wayland's.)
            running.startingLocker { running.launch(lockCommand, x11 = false, fallback = false) }
            running.watchLock("\"${Launcher.programName(lockCommand)}\"")
        }
    }

    /**
     * What happens when a lock that was asked for does not show in time ([lockShowMillis];
     * before sleep [sleepLockWaitMillis]) — the locker is not installed, or it failed:
     * [LockFailure.OWN_LOCK] (the default) locks with the compositor's own plain lock screen
     * ([lockColor], no password: Enter starts the locker again, and when the locker the
     * compositor started — by [lock], or what your [locker] started — locks and unlocks, it
     * ends; another program that locks and unlocks does not end it); [LockFailure.NOTICE]
     * only says so, and the computer then sleeps unlocked.
     */
    var lockFailure = LockFailure.OWN_LOCK

    /**
     * How long a lock ([lock]: Super+L, idle, logind's Lock) may take to show before it counts
     * as failed ([lockFailure]), in milliseconds.
     */
    var lockShowMillis = 3000L
        set(value) {
            require(value in 100..60_000) { "lockShowMillis is 100 to 60000, not $value" }
            field = value
        }

    /**
     * A lock screen of your own instead of [lockCommand] (a compositor that draws its own):
     * used by [lock], the default Super+L, logind's Lock and the lock before sleep, so only one
     * lock screen asks for the password. It should lock at once (sleep follows right after) —
     * e.g. with the compositor's own lock: `locker { desktop.lockInternally(myLockWidget) }`
     * (then the desktop knows it is locked: no [lockerShows] needed).
     */
    var locker: (Caulis.() -> Unit)? = null

    /**
     * True while your [locker]'s lock screen shows (null: not known — taken as shown as soon as
     * it was asked for). With it, sleep waits until it shows (a few seconds at most) and a
     * lock asked for again (logind's Lock, the lock before sleep) is not asked of it twice.
     */
    var lockerShows: (Caulis.() -> Boolean)? = null

    /** Sets [locker] and, if known, how to tell it shows ([lockerShows]). */
    @JvmOverloads
    fun locker(shows: (Caulis.() -> Boolean)? = null, lock: Caulis.() -> Unit): Caulis = apply {
        locker = lock
        lockerShows = shows
    }

    /**
     * Lock the screen before the computer sleeps (suspend, hibernate), so it wakes up locked
     * ([lockCommand]; it needs logind, on the screen).
     */
    var lockBeforeSleep = true

    /** How long sleep waits for the lock screen to show ([lockBeforeSleep]) before it goes on anyway, in milliseconds. */
    var sleepLockWaitMillis = 3000L
        set(value) {
            require(value in 0..30_000) { "sleepLockWaitMillis is 0 to 30000, not $value" }
            field = value
        }

    /**
     * When the lock before sleep did not show and the compositor's own lock screen went up
     * instead ([lockFailure]): how long it is on screen before the computer sleeps, in
     * milliseconds (so the screen the computer wakes up to is the locked one).
     */
    var sleepOwnLockShowMillis = 200L
        set(value) {
            require(value in 0..5_000) { "sleepOwnLockShowMillis is 0 to 5000, not $value" }
            field = value
        }

    /** What a laptop's keys do: steps, limits and the programs used (see [SystemKeys]). */
    val systemKeys = SystemKeys()

    /** Changes [systemKeys]: `systemKeys { volumeStep = 2 }`. */
    @JvmSynthetic
    fun systemKeys(configure: SystemKeys.() -> Unit) {
        systemKeys.configure()
    }

    /** The same for Java. */
    fun systemKeys(configure: Consumer<SystemKeys>): Caulis = apply { configure.accept(systemKeys) }

    /**
     * The monitor touch screens (and other devices that point at a place, not move) cover,
     * by connector ("eDP-1"); null: the laptop's own screen ([builtInConnectors]), else the first.
     */
    var touchscreenMonitor: String? = null

    /**
     * Which monitors are a laptop's own screen, by how their connector's name begins (eDP-1,
     * LVDS-1, DSI-1 by default): the display modes, the lid, touch screens and pen screens go by it.
     */
    val builtInConnectors: MutableList<String> = mutableListOf("eDP", "LVDS", "DSI")

    // True if the monitor at connector [name] is a laptop's own screen.
    internal fun isBuiltIn(name: String): Boolean = builtInConnectors.any { name.startsWith(it) }

    /**
     * How long a scratchpad's program may take to show its window before another press of its
     * key starts it again, in milliseconds.
     */
    var scratchpadStartMillis = 5000L
        set(value) {
            require(value in 0..60_000) { "scratchpadStartMillis is 0 to 60000, not $value" }
            field = value
        }

    /**
     * How long one call into the code of a configuration language (config.lua: a key, an
     * event, a layout) may run before it is stopped as stuck, in milliseconds.
     */
    var scriptCallLimitMillis = 500L
        set(value) {
            require(value in 10..60_000) { "scriptCallLimitMillis is 10 to 60000, not $value" }
            field = value
        }

    /**
     * How many compiled versions of config.kts stay in its cache (`~/.cache/<configName>/config-kts`):
     * each change of the script, or of the compositor, makes another; going back to a kept one
     * starts at once instead of compiling again.
     */
    var compiledScriptsKept = 4
        set(value) {
            require(value in 1..100) { "compiledScriptsKept is 1 to 100, not $value" }
            field = value
        }

    /**
     * A monitor or projector never seen before is used beside the laptop's screen, and the
     * display modes are shown so another can be chosen at once (the laptop's display key, F7 on
     * many, shows them any time: [Keys.displays]). False: it is just used beside it.
     */
    var displayChooserOnPlug = true

    /** With the lid closed, the laptop's screen is off while another monitor is in use. */
    var lidTurnsScreenOff = true

    /** The way the laptop's screen and another monitor are used together (on the screen only; else nothing happens). */
    fun useDisplays(mode: DisplayMode) {
        liveOrNull()?.displays?.use(mode)
    }

    internal val sleepActions = ArrayList<Caulis.() -> Unit>()
    internal val wakeActions = ArrayList<Caulis.() -> Unit>()

    /** Runs [action] just before the computer sleeps (the screen is being locked meanwhile). */
    @JvmSynthetic
    fun onSleep(action: Caulis.() -> Unit): AutoCloseable = listenTo(sleepActions, action)

    /** Runs [action] when the computer woke up. */
    @JvmSynthetic
    fun onWake(action: Caulis.() -> Unit): AutoCloseable = listenTo(wakeActions, action)

    internal val quitActions = ArrayList<Caulis.() -> Unit>()

    /**
     * Runs [action] when the compositor quits, while the desktop and the buses are still there
     * — to stop threads, programs ([spawn]) and the like of your own. ([onUnload] is for a
     * reload; this one for the end.)
     */
    @JvmSynthetic
    fun onQuit(action: Caulis.() -> Unit): AutoCloseable = listenTo(quitActions, action)

    // (These are called when it happens: nothing to hand to the desktop.)
    private fun <T> listenTo(list: MutableList<T>, action: T): AutoCloseable {
        val into = addTo(list, action)
        return AutoCloseable { takeFrom(list, into, action) }
    }

    /** Closes the focused window (asks its program to). */
    fun closeFocused() = desktop.closeFocused()

    /** Stops the compositor; [run] returns. */
    fun quit() {
        liveOrNull()?.quit()
    }

    /**
     * Starts the compositor and returns when it is closed. First checks that everything
     * it needs is there; throws [CannotStartException] with plain explanations if not.
     */
    fun run() {
        // --check-config, --print-nix-options, ...: answered, nothing started ([commandLine]).
        if (commandLine) dev.caulis.wm.config.CommandLine.handle(this)?.let { status -> exitProcess(status) }
        start()
    }

    /**
     * Starts the compositor with [arguments] as its command line, and returns the exit status
     * when it ends — never ending the program itself: what the arguments ask for
     * (`--check-config`, `--do ...`, see [dev.caulis.wm.config.CommandLine]) is answered
     * instead of starting; no arguments start it. For `fun main(args: Array<String>) =
     * exitProcess(caulis.run(args))` or a command line of your own ([commandLine] = false).
     * Throws [CannotStartException] as [run] does.
     */
    fun run(arguments: Array<String>): Int {
        dev.caulis.wm.config.CommandLine.handle(this, arguments.toList())?.let { return it }
        start()
        return 0
    }

    // Starts and runs until it ends.
    private fun start() {
        // Java's 2D graphics only draws into pictures here (see Desktop): never into windows.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
        // What the code says alone (without config.json): the start for applying the files
        // again when main() cannot be run again (see Running.takeOnConfig). Also for a main()
        // run again by a reload: else a later reload whose main() fails would apply the files
        // onto the running configuration itself (its top-level os.execute run again, settings
        // taken out of the files not going back).
        codeOnly = snapshot()
        // The configuration file on top of what the code says (also on every reload).
        if (useConfigFile) configProblems = dev.caulis.wm.config.ConfigFile.applyTo(this)
        // A reload runs main() again: hand this configuration over instead of starting.
        Reloader.capturing.get()?.let { slot ->
            slot[0] = this
            throw Reloader.Captured()
        }
        // Whose main() this is (to run it again when config.json changes).
        Reloader.startedFrom = Thread.currentThread().stackTrace.lastOrNull { it.methodName == "main" }?.className
        applyLogSettings()
        // CAULIS_MODE=headless|nested|screen chooses without changing the code.
        val chosen = System.getenv("CAULIS_MODE")?.takeIf { it.isNotBlank() }?.let { value ->
            Backend.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: throw CannotStartException("CAULIS_MODE=$value: there are ${Backend.entries.joinToString { it.name.lowercase() }}")
        } ?: backend
        val actual = when (chosen) {
            Backend.AUTO -> if (System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()) Backend.SCREEN else Backend.NESTED
            else -> chosen
        }
        val problems = Preflight.check(this, nested = actual == Backend.NESTED, headless = actual == Backend.HEADLESS)
        if (!Preflight.report(problems, title)) {
            throw CannotStartException("$title cannot start (see the problems listed above)")
        }
        startWarnings = problems.filter { !it.fatal }
        backendInUse = actual
        when (actual) {
            Backend.NESTED -> runNested(this)
            Backend.HEADLESS -> runHeadless(this)
            else -> runOnScreen(this)
        }
    }

    // A copy of this configuration as the code made it (see start).
    internal var codeOnly: Caulis? = null

    // The backend it was started with (AUTO and CAULIS_MODE decided), once it starts.
    internal var backendInUse: Backend? = null

    // The log settings from code, then CAULIS_LOG / CAULIS_LOG_FILE on top (they win). Areas
    // the configuration before set and this one does not go back to the usual level; a log
    // file it no longer names is closed. ([before]: the configuration a reload replaces.)
    internal fun applyLogSettings(before: Caulis? = null) {
        Log.level = logLevel
        before?.logAreas?.keys?.filter { it !in logAreas }?.forEach(Log::clearLevel)
        for ((area, level) in logAreas) Log.setLevel(area, level)
        System.getenv("CAULIS_LOG")?.takeIf { it.isNotBlank() }?.let(Log::configure)
        if (System.getenv("CAULIS_LOG_FILE").isNullOrBlank()) {
            if (logFile != null) { if (Log.file != logFile) Log.file = logFile } else if (before?.logFile != null && Log.file == before.logFile) Log.file = null
        }
        Log.write("log", Log.EVENTS, "I", "Log level ${Log.level}" + Log.AREAS.keys.filter { Log.levelOf(it) != Log.level }
            .joinToString("") { ", $it ${Log.levelOf(it)}" } + (Log.file?.let { ", also written to $it" } ?: ""), null)
    }

    companion object {
        /** A new compositor with default settings (for Java; Kotlin can use [caulis]). */
        @JvmStatic
        fun create() = Caulis()
    }
}

/**
 * Configures and runs a compositor (see [Caulis]). Problems that stop it from starting are
 * printed in plain words and end the program.
 */
fun caulis(configure: Caulis.() -> Unit) {
    val compositor = Caulis().apply(configure)
    try {
        compositor.run()
    } catch (e: CannotStartException) {
        exitProcess(1) // the reasons were already printed
    } catch (e: Exception) {
        System.err.println("The compositor stopped: ${e.message}")
        e.cause?.let { System.err.println("  (${it.message})") }
        System.err.println("\nDetails for a bug report:")
        e.printStackTrace()
        // What happened just before (kept even when the log level was low).
        val recent = Log.recent().takeLast(40)
        if (recent.isNotEmpty()) {
            System.err.println("\nThe last events:")
            recent.forEach(System.err::println)
        }
        runCatching { compositor.running?.desktop?.describe() }.getOrNull()?.let {
            System.err.println("\nThe state when it stopped:\n$it")
        }
        exitProcess(1)
    }
}

/** The compositor cannot start because something it needs is missing (explained on stderr). */
class CannotStartException(message: String) : RuntimeException(message)

private val keyboardLog = Logger("keyboard")
