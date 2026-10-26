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
import dev.caulis.log.Log
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
 * on the screen when started from a text console ([mode]).
 */
class Caulis {

    // --- settings (before run) ------------------------------------------------------------

    enum class Mode {
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

    var mode = Mode.AUTO

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
     * How long windows take to glide to new places, minimize into the dock, zoom into the
     * overview, ... in milliseconds (0 = no animations, they jump).
     */
    var animationMillis = 220L
        set(value) {
            require(value >= 0) { "animationMillis must not be negative" }
            field = value
            running?.desktop?.animationMillis = value
        }

    /**
     * Which animations, each changeable on its own — windows appearing, going away, moving,
     * minimizing into the dock and coming back, workspaces changing, the overview:
     *
     * ```
     * animations = Animations(minimize = WindowAnimation.pop())                 // no genie
     * animations = Animations(windowOpen = WindowAnimation.fade() + WindowAnimation.slide(dy = 80))
     * animations = Animations(workspaceSwitch = WorkspaceAnimation.FADE.lasting(300), curve = Curve.OVERSHOOT)
     * animations = Animations(windowOpen = WindowAnimation { m -> WindowFrame(m.to, alpha = m.progress.toFloat()) })
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
    }

    /**
     * Run X11 programs too (through Xwayland, started only when the first one connects). Off
     * = Wayland programs only.
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
    fun workspace(number: Int, configure: WorkspaceRule.() -> Unit) {
        workspaceRules += WorkspaceRule(number).apply(configure)
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

        fun mode(width: Int, height: Int, refreshHz: Double? = null) {
            this.width = width
            this.height = height
            this.refreshHz = refreshHz
        }

        /** False: the monitor is not used at all. */
        var enabled = true

        /** How it is turned: Transform.ROTATE_90 for a screen standing on its side (null: as it is). */
        var transform: dev.caulis.core.Transform? = null
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
     */
    var nestedMonitors = 1

    /**
     * Headless ([Mode.HEADLESS]): how many monitors (HEADLESS-1, HEADLESS-2, ...); their size
     * is their [monitor] mode, else 1920x1080.
     */
    var headlessMonitors = 1

    /**
     * Start programs the Wayland way when they can (GTK, Qt, Electron, Firefox are told to
     * prefer it), and through X11 when that fails: a program that quits at once without a
     * window is started again through Xwayland. Off = programs choose for themselves.
     */
    var waylandFirst = true

    /**
     * Java programs (Swing, AWT) on a fractional scale such as 1.5: they can only draw at whole
     * scales, so they get an X server of their own, draw at the next whole scale (2) and are
     * shown at exactly yours — sharp and the right size. Off = they draw at the nearest whole
     * scale with the other X11 programs (sharp, but up to a third too big or too small).
     */
    var sharpJavaScaling = true

    /** Programs always started through X11, by name: `x11Programs += "steam"`. */
    val x11Programs: MutableSet<String> = LinkedHashSet()

    /** Keyboard layouts as for `setxkbmap`, e.g. "us,ru", and options, e.g. "grp:win_space_toggle". */
    var keyboardLayouts = "us"
    var keyboardOptions = ""

    /** Keyboard layout variants, one per layout as for setxkbmap: "", "dvorak", ",phonetic". Restart to change. */
    var keyboardVariants = ""

    /** The keyboard model as for setxkbmap ("pc105"). Restart to change. */
    var keyboardModel = "pc105"

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
     * XDG_SESSION_TYPE) is handed to the session's services at the start — systemd's user
     * manager and D-Bus activation — so portals, notifications and screen sharing started
     * by them reach this compositor. Null (the default): on a real screen only.
     */
    var exportEnvironment: Boolean? = null

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
    val colorSchemeNow: ColorScheme get() = running?.appearance?.colorScheme ?: colorScheme

    /**
     * Light or dark now, by hand (a key, `--do color-scheme dark`): until the schedule's next
     * change, or for as long as the compositor runs without one. Null: back to the schedule
     * or [colorScheme].
     */
    fun switchColorScheme(scheme: ColorScheme?) {
        checkNotNull(running) { "Caulis is not running yet" }.switchColorScheme(scheme)
    }

    /** Runs [action] whenever the scheme changes (to restyle a bar of your own, ...). */
    fun onColorSchemeChanged(action: Caulis.(ColorScheme) -> Unit): AutoCloseable = listen(colorSchemeListeners, action)

    internal val colorSchemeListeners = ArrayList<Caulis.(ColorScheme) -> Unit>()

    /** The cursor size in pixels (24 is usual; 32 or 48 for a bigger one). Null = `XCURSOR_SIZE` or 24. */
    var cursorSize: Int? = null
        set(value) {
            require(value == null || value in 8..256) { "cursorSize must be between 8 and 256" }
            field = value
            running?.applyCursor()
        }

    /** Hide the cursor while typing; it comes back when the mouse moves. */
    var hideCursorWhileTyping = false
        set(value) {
            field = value
            running?.applyCursor()
        }

    /** Hide the cursor after the mouse was still for this many seconds (0 = never). */
    var hideCursorAfterSeconds = 0
        set(value) {
            require(value >= 0) { "hideCursorAfterSeconds must not be negative" }
            field = value
            running?.applyCursor()
        }

    /** The terminal [openTerminal] (and the default Super+Return) starts. */
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
     * What programs started inside are told the desktop is called (`XDG_CURRENT_DESKTOP`).
     * Desktop portals use it to choose who handles screen sharing (see the guide).
     */
    var desktopName = "caulis"

    /**
     * More environment for the programs it starts, e.g. `environment["MOZ_ENABLE_WAYLAND"] = "1"`;
     * null takes a variable away. (WAYLAND_DISPLAY, DISPLAY and the like are set by Caulis itself.)
     */
    val environment = LinkedHashMap<String, String?>()

    /**
     * The name of your compositor's folder of settings, shaders and pictures:
     * `~/.config/<configName>` (or `$XDG_CONFIG_HOME/<configName>`), see [configFolder].
     */
    var configName = "caulis"
        set(value) {
            require(value.isNotBlank() && '/' !in value) { "configName is a folder name, not \"$value\"" }
            field = value
        }

    /** Another place for that folder (null: the usual one, see [configName]). */
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

    /** Name of the window in nested mode (and what the outer session knows it as). */
    var title = "Caulis"
    var appId = "dev.caulis.Nested"

    /**
     * How much the log says: 0 nearly nothing, 1 problems (the default), 2 what happens
     * (windows, monitors, layouts, ...), 3 everything step by step. `CAULIS_LOG` (e.g.
     * `CAULIS_LOG=3`) wins over this, so you can look closer without changing code.
     * See [Log] and [log] for single areas.
     */
    var logLevel: Int = Log.PROBLEMS
        set(value) {
            require(value in 0..3) { "logLevel must be 0, 1, 2 or 3" }
            field = value
        }

    /** Also write the log to this file (e.g. Path.of("/tmp/my-compositor.log")). `CAULIS_LOG_FILE` wins over it. */
    var logFile: java.nio.file.Path? = null

    internal val logAreas = LinkedHashMap<String, Int>()

    /**
     * One area of the log in more (or less) detail than [logLevel], e.g. `log("focus", 3)`
     * to follow every focus change. The areas: [Log.AREAS] ("wayland", "windows", "focus",
     * "input", "layout", "render", "drm", ...).
     */
    fun log(area: String, level: Int) {
        require(level in 0..3) { "a log level is 0, 1, 2 or 3" }
        require(area in Log.AREAS) { "there is no log area \"$area\"; the areas: ${Log.AREAS.keys.joinToString()}" }
        logAreas[area] = level
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
     * only once; [onReload] after each reload.
     */
    var hotReload = true

    /**
     * Read `config.json` from [configFolder] too — settings as data, written by hand or by the
     * Nix module (see the guide) — on top of what the code says. Saved again while running,
     * it is taken on at once.
     */
    var useConfigFile = true

    /**
     * Open the IPC socket (`$XDG_RUNTIME_DIR/<configName>-<wayland display>.sock`, see
     * caulis.proto): bars, scripts and tools run actions, ask what is on screen and hear what
     * changes — `my-compositor --do "workspace 2"`, `--state`, `--events`.
     */
    var ipc = true

    /** Print frame timing every few seconds. */
    var stats = false

    /**
     * The pointer on the display's own cursor plane (screen mode): it moves without new
     * pictures being drawn, with less delay. Off: always drawn into the pictures.
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

    /** Show fullscreen programs' pictures directly on the monitor (screen mode). */
    var directScanout = true

    /** Quit by itself after this many seconds (handy for trying things out safely). */
    var exitAfterSeconds: Long? = null

    /** Advanced: the session socket to open the nested window in (null = WAYLAND_DISPLAY). */
    var nestedIn: java.nio.file.Path? = null

    /** Advanced: where programs' socket is created (null = XDG_RUNTIME_DIR, the usual place). */
    var socketDirectory: java.nio.file.Path? = null

    /** One of your keys: its combination, how it behaves, the key mode it belongs to (null: the usual keys). */
    class Binding internal constructor(val combination: String, val options: BindOptions, val mode: String?, val action: Caulis.() -> Unit) {
        // The configuration it belongs to (none once it is taken away).
        internal var owner: Caulis? = null

        /** False: its keys do nothing of yours (they go to programs) until it is true again. */
        var enabled = true
            set(value) {
                if (field == value) return
                field = value
                owner?.running?.rebindKeys()
            }

        /** Takes it away for good. */
        fun unbind() {
            val from = owner ?: return
            owner = null
            from.bindings -= this
            from.running?.rebindKeys()
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
        list += action
        running?.rewireEvents()
        return AutoCloseable {
            list -= action
            running?.rewireEvents()
        }
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
        binding.owner = this
        bindings += binding
        running?.rebindKeys()
        return binding
    }

    /**
     * Keys of a mode of their own (a "submap"): while it is on ([enterMode], or the action
     * `mode resize`), these keys are the compositor's and the usual ones are not; keys not
     * named here go to programs. Quitting and text consoles always work.
     *
     * ```
     * bind("Super+R") { enterMode("resize") }
     * mode("resize") {
     *     bind("Left", BindOptions(repeat = true)) { run("resize left") }
     *     bind("Escape") { enterMode(null) }
     * }
     * ```
     */
    fun mode(name: String, configure: KeyMode.() -> Unit) {
        require(name.isNotBlank() && name != "reset" && name != "default") { "a mode needs a name of its own, not \"$name\"" }
        modeNames += name
        KeyMode(name).configure()
    }

    // Every mode named (one without keys too: none of the usual keys while it is on).
    internal val modeNames = LinkedHashSet<String>()

    /** What [mode] binds keys with. */
    inner class KeyMode internal constructor(val name: String) {
        @JvmSynthetic
        fun bind(vararg combination: String, action: Caulis.() -> Unit) = bind(combination.joinToString("+"), BindOptions(), action)

        @JvmSynthetic
        fun bind(combination: String, options: BindOptions, action: Caulis.() -> Unit): Binding {
            KeyBindings.parseAllowingTap(combination)
            return addBinding(Binding(combination, options, name, action))
        }
    }

    /** Turns the key mode [name] on (see [mode]); null: back to the usual keys. */
    fun enterMode(name: String?) = checkNotNull(running) { "Caulis is not running yet" }.enterMode(name)

    /** The key mode that is on (null: the usual keys). */
    val keyMode: String? get() = running?.keyMode

    // --- input --------------------------------------------------------------------------------

    /**
     * How mice, trackballs and touchpads behave — all of them, touchpads, and single devices
     * by name (`my-compositor --devices` lists them):
     *
     * ```
     * input.pointers.speed = -0.3
     * input.pointers.accelProfile = AccelProfile.FLAT
     * input.touchpads.naturalScroll = true
     * input.touchpads.disableWhileTyping = true
     * input.devices["Logitech G Pro*"] = DeviceSettings().apply { speed = -0.6 }
     * ```
     */
    val input = InputSettings()

    /** Held keys repeat this many times a second (0: never). */
    var repeatRate = Seat.REPEAT_RATE

    /** Milliseconds a key is held before it repeats. */
    var repeatDelay = Seat.REPEAT_DELAY

    /** The keyboard gets to the window under the pointer as soon as the pointer is over it. */
    var focusFollowsMouse = false

    /** A window asking for the focus with a good token gets it (false: it is only marked urgent). */
    var focusOnActivate = true

    /**
     * A key, a click or the pointer moving switches screens on again that were switched off
     * (dpms off) — after a second, so the key that switched them off does not.
     */
    var wakeScreensOnInput = true

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
     */
    fun onIdle(seconds: Long, resume: Caulis.() -> Unit = {}, idle: Caulis.() -> Unit) {
        require(seconds in 1..86_400) { "onIdle: 1 to 86400 seconds, not $seconds" }
        idleWatches += IdleWatch(seconds, idle, resume)
    }

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
    fun onLid(action: Caulis.(closed: Boolean) -> Unit): AutoCloseable = listen(lidActions, action)

    internal val reloadActions = ArrayList<Caulis.() -> Unit>()

    /** Runs [action] after the configuration was reloaded (see [hotReload]); [onStart] runs only once. */
    @JvmSynthetic
    fun onReload(action: Caulis.() -> Unit): AutoCloseable = listen(reloadActions, action)

    /** Runs [action] once everything is ready (e.g. to start a panel or wallpaper program). */
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

    /** Shows or hides the scratchpad [name] (starting its program if needed). */
    fun toggleScratchpad(name: String) {
        checkNotNull(running) { "Caulis is not running yet" }.toggleScratchpad(name)
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

    // --- the same for Java (each returns this, so calls can be chained) -----------------

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
    fun configName(name: String): Caulis = apply { configName = name }
    fun background(background: Background): Caulis = apply { this.background = background }
    fun backgroundFor(rule: java.util.function.BiFunction<String, Int, Background?>): Caulis = apply { backgroundFor { monitor, workspace -> rule.apply(monitor, workspace) } }
    fun noticesFromLog(rule: java.util.function.Predicate<Log.Entry>): Caulis = apply { noticesFromLog = { rule.test(it) } }
    fun onNotice(action: java.util.function.BiConsumer<Caulis, Notice>): Caulis = apply { onNotice { action.accept(this, it) } }
    fun titleBarsFor(rule: java.util.function.Function<Window, TitleBars?>): Caulis = apply { titleBarsFor { rule.apply(it) } }
    fun logLevel(level: Int): Caulis = apply { logLevel = level }
    fun logArea(area: String, level: Int): Caulis = apply { log(area, level) }
    fun cursor(theme: String?, size: Int?): Caulis = apply {
        cursorTheme = theme
        cursorSize = size
    }
    fun mode(mode: Mode): Caulis = apply { this.mode = mode }
    fun keyboard(layouts: String, options: String = ""): Caulis = apply {
        keyboardLayouts = layouts
        keyboardOptions = options
    }
    fun keys(configure: Consumer<Keys>): Caulis = apply { configure.accept(keys) }
    fun bind(combination: String, action: Consumer<Caulis>): Caulis = apply { bind(combination) { action.accept(this) } }
    fun bind(combination: List<String>, action: Consumer<Caulis>): Caulis = apply { bind(*combination.toTypedArray()) { action.accept(this) } }
    fun onStart(action: Consumer<Caulis>): Caulis = apply { onStart { action.accept(this) } }
    fun rule(configure: Consumer<WindowRule>): Caulis = apply { rule { configure.accept(this) } }
    fun panelRule(configure: Consumer<PanelRule>): Caulis = apply { panelRule { configure.accept(this) } }
    fun floatWhen(rule: java.util.function.Predicate<Window>): Caulis = apply { floatWhen { rule.test(it) } }
    fun effects(effects: Effects): Caulis = apply { this.effects = effects }
    fun effectsFor(rule: java.util.function.BiFunction<Window, Effects, Effects>): Caulis = apply { effectsFor { window, fx -> rule.apply(window, fx) } }
    fun panelEffects(rule: java.util.function.Function<LayerSurface, Effects?>): Caulis = apply { panelEffects { rule.apply(it) } }
    fun scratchpad(name: String, key: String?, command: String?, style: Scratchpad.Style): Caulis =
        apply { scratchpad(name, key, command, style) { it.appId == name } }
    fun onWindowOpened(action: java.util.function.BiConsumer<Caulis, Window>): Caulis = apply { onWindowOpened { action.accept(this, it) } }
    fun onWindowClosed(action: java.util.function.BiConsumer<Caulis, Window>): Caulis = apply { onWindowClosed { action.accept(this, it) } }

    // --- while running ------------------------------------------------------------------

    internal var running: Running? = null

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
        colorSchemeListeners,
    )

    /** The window manager (workspaces, focus, layouts). Only while running. */
    val desktop: Desktop get() = checkNotNull(running) { "Caulis is not running yet" }.desktop

    /** The clipboard: what is on it, and putting something there yourself (once it runs). */
    val clipboard: dev.caulis.core.DataDeviceManager get() = checkNotNull(running) { "Caulis is not running yet" }.clipboard

    /** The window with the keyboard focus, if any. */
    val focusedWindow: Window? get() = running?.desktop?.focusedWindow

    /**
     * Starts [command] (run by /bin/sh) as a program inside the compositor — the Wayland way
     * first ([waylandFirst]), or through X11 with [x11] = true.
     */
    @JvmOverloads
    fun launch(command: String, x11: Boolean = false) {
        checkNotNull(running) { "Caulis is not running yet" }.launch(command, x11)
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
        checkNotNull(running) { "Caulis is not running yet" }.spawn(command, directory?.toFile())

    fun openTerminal() = launch(terminal)

    /**
     * The environment programs started inside get — WAYLAND_DISPLAY, DISPLAY, XDG_CURRENT_DESKTOP,
     * the cursor and your [environment] — for programs you start some other way.
     */
    fun programEnvironment(): Map<String, String> = checkNotNull(running) { "Caulis is not running yet" }.programEnvironment()

    /**
     * The session bus (D-Bus), on the compositor's thread: offer objects of your own to other
     * programs (`export`, `requestName`, `emitSignal`) or call theirs. Connected when first
     * asked for, shared, closed when the compositor ends; null if there is none.
     */
    val sessionBus: dev.caulis.core.dbus.DBusConnection? get() = checkNotNull(running) { "Caulis is not running yet" }.sessionBus

    /**
     * Runs [action] written as text, as in config.json's "bind" and on the IPC socket:
     * `run("workspace 3")`, `run("exec firefox")`, `run("close", window)` — on [window], or the
     * focused one (null). Throws IllegalArgumentException when it is not an action.
     */
    fun run(action: String, window: Window? = null) {
        dev.caulis.wm.config.Actions.parseFor(action)(this, window)
    }

    // Timers started with [after] (cancelled when this configuration is replaced by a reload).
    internal val timers = ArrayList<dev.caulis.event.EventLoop.Timer>()

    // What this configuration added to the running desktop (drawings): taken away on a reload.
    internal val added = ArrayList<AutoCloseable>()

    /**
     * Pictures of your own that take no input — a bar, a border, rounded screen corners — at
     * [level] (see [dev.caulis.core.Drawing]); close the result to take it away. Asked for in
     * the configuration itself, it is shown when it runs; a reload takes it away.
     */
    /**
     * A part of the compositor's own interface — a bar, a dock, a launcher ([dev.caulis.core.Widget];
     * caulis-compose's `compose { }` makes one in Compose); close the result to take it away.
     * Asked for in the configuration itself, it is shown when it runs; a reload takes it away
     * (and closes it).
     */
    fun addWidget(widget: dev.caulis.core.Widget): AutoCloseable {
        val r = running ?: return later { addWidget(widget) }
        val handle = r.desktop.addWidget(widget)
        val closing = AutoCloseable {
            handle.close()
            (widget as? AutoCloseable)?.close()
        }
        added += closing
        return AutoCloseable {
            closing.close()
            added -= closing
        }
    }

    /**
     * Pictures of your own that take no input — a bar, a border, rounded screen corners — at
     * [level] (see [dev.caulis.core.Drawing]); close the result to take it away. Asked for in
     * the configuration itself, it is shown when it runs; a reload takes it away.
     */
    fun addDrawing(level: dev.caulis.core.DrawingLevel, drawing: dev.caulis.core.Drawing): AutoCloseable {
        val r = running ?: return later { addDrawing(level, drawing) }
        val handle = r.desktop.addDrawing(level, drawing)
        added += handle
        return AutoCloseable {
            handle.close()
            added -= handle
        }
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
     * cancels the timers of the configuration before.
     */
    fun after(millis: Long, action: Caulis.() -> Unit): AutoCloseable {
        val r = running ?: return later { after(millis, action) }
        lateinit var timer: dev.caulis.event.EventLoop.Timer
        timer = r.loop.after(millis.coerceAtLeast(0)) {
            timers -= timer
            r.safely("a timer") { action(this) }
        }
        timers += timer
        return AutoCloseable {
            timer.cancel()
            timers -= timer
        }
    }

    /**
     * Runs [action] soon on the compositor's thread — callable from any thread (work done on
     * one of your own, then shown). Nothing happens if the compositor is not running.
     */
    fun post(action: Caulis.() -> Unit) {
        val r = running ?: return
        r.loop.post { r.safely("posted work") { action(this) } }
    }

    /**
     * Runs [action] every [millis] from now on, on the compositor's thread, until the result is
     * closed (or the configuration is reloaded). Asked for in the configuration itself, it
     * starts when it runs.
     */
    fun every(millis: Long, action: Caulis.() -> Unit): AutoCloseable {
        require(millis >= 10) { "every: at least 10 milliseconds apart, not $millis" }
        val r = running ?: return later { every(millis, action) }
        var stopped = false
        var timer: dev.caulis.event.EventLoop.Timer? = null
        fun next() {
            timer = r.loop.after(millis) {
                timers -= timer!!
                if (stopped) return@after
                r.safely("a repeating timer") { action(this) }
                if (!stopped) next()
            }.also { timers += it }
        }
        next()
        return AutoCloseable {
            stopped = true
            timer?.let { it.cancel(); timers -= it }
        }
    }

    /** [window]'s number: the same while it is open, never used again (programs know it by this). */
    fun idOf(window: Window): Long = checkNotNull(running) { "Caulis is not running yet" }.windowIds.of(window)

    /** The open window with the number [id] (see [idOf]), or null. */
    fun windowWithId(id: Long): Window? = running?.windowIds?.window(id)

    /** Moves the focused window to the monitor beside its own in [direction]; false if there is none. */
    fun moveToMonitorBeside(direction: Direction): Boolean {
        val window = focusedWindow ?: return false
        val target = desktop.monitorBeside(direction.dx, direction.dy, desktop.monitorOf(window)) ?: return false
        desktop.moveToMonitor(window, target)
        return true
    }

    /** Locks the screen with [lockCommand]. */
    fun lock() = launch(lockCommand)

    /**
     * Lock the screen before the computer sleeps (suspend, hibernate), so it wakes up locked
     * ([lockCommand]; it needs logind, on the screen).
     */
    var lockBeforeSleep = true

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
        running?.displays?.use(mode)
    }

    internal val sleepActions = ArrayList<Caulis.() -> Unit>()
    internal val wakeActions = ArrayList<Caulis.() -> Unit>()

    /** Runs [action] just before the computer sleeps (the screen is being locked meanwhile). */
    @JvmSynthetic
    fun onSleep(action: Caulis.() -> Unit): AutoCloseable = listenTo(sleepActions, action)

    /** Runs [action] when the computer woke up. */
    @JvmSynthetic
    fun onWake(action: Caulis.() -> Unit): AutoCloseable = listenTo(wakeActions, action)

    // (These are called when it happens: nothing to hand to the desktop.)
    private fun <T> listenTo(list: MutableList<T>, action: T): AutoCloseable {
        list += action
        return AutoCloseable { list -= action }
    }

    fun closeFocused() = desktop.closeFocused()

    /** Stops the compositor; [run] returns. */
    fun quit() {
        running?.quit()
    }

    /**
     * Starts the compositor and returns when it is closed. First checks that everything
     * it needs is there; throws [CannotStartException] with plain explanations if not.
     */
    fun run() {
        // Java's 2D graphics only draws into pictures here (see Desktop): never into windows.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
        // --check-config, --print-nix-options, ...: answered, nothing started.
        dev.caulis.wm.config.CommandLine.handle(this)?.let { status -> exitProcess(status) }
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
            Mode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: throw CannotStartException("CAULIS_MODE=$value: there are ${Mode.entries.joinToString { it.name.lowercase() }}")
        } ?: mode
        val actual = when (chosen) {
            Mode.AUTO -> if (System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()) Mode.SCREEN else Mode.NESTED
            else -> chosen
        }
        val problems = Preflight.check(this, nested = actual == Mode.NESTED, headless = actual == Mode.HEADLESS)
        if (!Preflight.report(problems, title)) {
            throw CannotStartException("$title cannot start (see the problems listed above)")
        }
        startWarnings = problems.filter { !it.fatal }
        when (actual) {
            Mode.NESTED -> runNested(this)
            Mode.HEADLESS -> runHeadless(this)
            else -> runOnScreen(this)
        }
    }

    // The log settings from code, then CAULIS_LOG / CAULIS_LOG_FILE on top (they win).
    internal fun applyLogSettings() {
        Log.level = logLevel
        for ((area, level) in logAreas) Log.setLevel(area, level)
        System.getenv("CAULIS_LOG")?.takeIf { it.isNotBlank() }?.let(Log::configure)
        if (System.getenv("CAULIS_LOG_FILE").isNullOrBlank()) logFile?.let { Log.file = it }
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
