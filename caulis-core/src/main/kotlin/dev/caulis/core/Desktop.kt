package dev.caulis.core

import dev.caulis.log.Logger
import dev.caulis.core.cursor.CursorTheme
import dev.caulis.core.input.PenPointer
import dev.caulis.core.input.Scroll
import dev.caulis.core.input.Seat
import dev.caulis.core.input.Tablet
import dev.caulis.core.input.TabletAxes
import dev.caulis.core.input.TabletTool
import dev.caulis.core.input.Tablets
import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.render.Color
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Layout
import dev.caulis.tiling.TilingCommand

/**
 * The window manager: workspaces, layouts (floating or tiled — see [Layout]), focus,
 * maximize/fullscreen/minimize, moving and resizing with the pointer, popups. It also draws
 * everything — only the parts that changed (see [DesktopView]).
 */
class Desktop(
    /** What draws everything (for pictures of your own: [Overlay], screenshots). */
    val renderer: Renderer,
    internal val seat: Seat,
    internal val compositor: Compositor,
    screenWidth: Int,
    screenHeight: Int,
) : XdgShell.Listener, LayerShell.Listener, SessionLockManager.Listener, AutoCloseable {

    // Parts of the desktop in classes of their own (in this module; they reach back into the
    // desktop for what they need). First, so that nothing below finds them missing.
    internal val touch = DesktopTouch(this, seat)
    internal val pens = DesktopPens(this, seat)
    internal val ownTitleBars = DesktopTitleBars(this)
    internal val pointerGrab = DesktopPointerGrab(this, seat)

    internal val cursors = DesktopCursorLook(this)
    internal val planes = DesktopCursorPlanes(this)
    internal val hits = DesktopHitTest(this)
    internal val popupHost = DesktopPopups(this)
    internal val keys = DesktopKeys(this)
    internal val overview = DesktopOverview(this)
    internal val capturing = DesktopCapture(this)
    internal val events = DesktopEvents(this)
    internal val report = DesktopReport(this)
    internal val widgetHost = DesktopWidgets(this)
    internal val layerHost = DesktopLayers(this)
    internal val lockHost = DesktopLock(this)
    internal val pads = DesktopScratchpads(this)
    internal val scaleFade = DesktopScaleFade(this)
    internal val looks = DesktopLooks(this)
    internal val animator = DesktopAnimations(this)
    internal val arranger = DesktopArrange(this)
    internal val focuser = DesktopFocus(this)
    internal val windowRoles = DesktopWindows(this)
    internal val pointerInput = DesktopPointer(this)
    internal val sceneBuilder = DesktopScene(this)
    internal val frames = DesktopFrames(this)
    internal val screens = DesktopMonitors(this)
    internal val workspaceHost = DesktopWorkspaces(this)
    internal val overlayHost = DesktopOverlays(this)

    // What the protocol objects (Activation, ServerDecorations, ForeignToplevels) ask of the desktop.
    internal val protocols = DesktopProtocols(this)

    // Our listener for saved shader files (taken away again in close).
    private var shaderSaved: (() -> Unit)? = null

    init {
        // Text (title bars, notices) is drawn with Java's 2D graphics into pictures, never in
        // windows of its own: without this it would connect to the X server of DISPLAY (the
        // outer session's, or one that is gone) and fail when it cannot.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
        warmUpFonts()
        // A shader file was saved: draw with its new code (only a weak link: tests make many desktops).
        val me = java.lang.ref.WeakReference(this)
        val listener: () -> Unit = {
            me.get()?.let { desktop ->
                desktop.compositor.display.loop.post {
                    if (desktop.screenShader != null) desktop.views.forEach { it.damageEverything() } // (its file, perhaps)
                    desktop.version++
                    desktop.onChange()
                }
            }
        }
        WatchedText.anyChange += listener
        shaderSaved = listener
        // A window's last look, taken while its picture is still there (it may be going away).
        compositor.pictureGoing = { surface ->
            val window = allWindows.firstOrNull { it.surface === surface && it.mapped }
            if (window != null) animator.lastLookOf(window)?.let { look -> animator.lastLooks.put(window, look)?.target?.close() }
        }
    }

    /**
     * Lets go of what the desktop keeps on the GPU (title bars, pictures of animations and
     * fades, backgrounds) and of its listeners. Call it when the compositor ends.
     */
    override fun close() {
        shaderSaved?.let { WatchedText.anyChange -= it }
        shaderSaved = null
        ownTitleBars.close()
        scaleFade.fades.keys.toList().forEach(scaleFade::dropFade)
        animator.flights.forEach { it.target.close() }
        animator.flights.clear()
        animator.lastLooks.values.forEach { it.target.close() }
        animator.lastLooks.clear()
        animator.appearingPictures.values.forEach { it.close() }
        animator.appearingPictures.clear()
        backdrops.close()
        wallpaperShaders.close()
        looks.paintsTimer?.cancel()
        osd.close()
    }

    // --- monitors, size and scale ---------------------------------------------------------

    internal val monitorList = ArrayList<Monitor>()

    // What wallpaper shaders share (their clock, the pointer, their frames), wherever drawn.
    internal val wallpaperShaders = ShaderWallpapers(compositor.display.loop) {
        // (Said in the render log's report of what keeps the screens drawn.)
        if (renderLog.level >= 2) frames.busy.moving(frames.WALLPAPER_SHADERS)
        version++
        onChange()
    }

    // The backgrounds of the monitors (declared first: drawing asks for them).
    internal val backdrops = Backdrops(renderer, compositor.display.loop, changed = {
        version++
        onChange()
    }, shaderWorks = { name, code, problem -> looks.shaderWorks(name, code, problem) }, shaders = wallpaperShaders).also { it.fade = { usual -> animation("fadeBackground", usual) } }

    /**
     * Every wallpaper shader ([WallpaperShader], the background's or a drawing's) stands still:
     * its time and the pointer as they were, nothing drawn anew for it — for a dimmed screen,
     * a laptop saving its battery. False again: they go on from where they stopped.
     */
    var wallpaperShadersPaused: Boolean
        get() = wallpaperShaders.paused
        set(value) {
            wallpaperShaders.paused = value
        }

    /**
     * The seconds on wallpaper shaders' clock (their `time`). Set it to show a chosen moment
     * (they go on from there; with [wallpaperShadersPaused], they stand there).
     */
    var wallpaperShadersClock: Double
        get() = wallpaperShaders.seconds(System.nanoTime())
        set(value) {
            wallpaperShaders.setSeconds(value)
        }

    /**
     * The most frames a second any wallpaper shader is drawn at, whatever it asks (0, the
     * default: as each asks, [WallpaperShader.framesPerSecond]) — e.g. fewer on battery.
     */
    var wallpaperFpsLimit: Int
        get() = wallpaperShaders.fpsLimit
        set(value) {
            wallpaperShaders.fpsLimit = value
        }

    // The compositor's own notices (declared first: arranging a monitor asks for their room).
    internal val noticeBoard = NoticeBoard(
        renderer, compositor.display.loop,
        changed = {
            version++
            onChange()
        },
        roomChanged = {
            for (monitor in monitorList.toList()) layerHost.arrangeLayers(monitor)
            version++
            onChange()
        },
    ).also { it.fade = { usual -> animation("fadeNotices", usual) } }

    /** The monitors showing the desktop, in the order they came (the first is the main one). */
    val monitors: List<Monitor> get() = monitorList

    /** The monitor with the focus: new windows open there, Super+N changes its workspace. */
    @Suppress("UNNECESSARY_LATEINIT") // set in init below, but notices before that ask isInitialized
    lateinit var focusedMonitor: Monitor
        internal set

    // Stands in until the backend adds the first real monitor (and in tests): the size given.
    internal var placeholder: Monitor? = null

    /**
     * How many screen pixels one desktop unit is: 1.0, 1.25, 1.5, 2.0, ... Windows, layouts,
     * the pointer and panels are in desktop units; only drawing is in pixels, so a bigger
     * scale makes everything bigger and as sharp as the screen is. Programs are told, and
     * those that can (most today) draw at exactly this scale.
     *
     * The scale of the monitor with the focus; setting it sets it on every monitor (see
     * [setScale] for one), and monitors added later get it too.
     */
    var scale: Double
        get() = focusedMonitor.scale
        set(value) {
            require(value in 0.5..4.0) { "a scale must be between 0.5 and 4, not $value" }
            defaultScale = value
            for (monitor in monitorList) setScale(monitor, value)
        }

    /**
     * What monitors added later get; setting it changes no monitor there is (for setting each
     * one's own at once, without a step through this one).
     */
    var defaultScale = 1.0
        set(value) {
            require(value in 0.5..4.0) { "a scale must be between 0.5 and 4, not $value" }
            field = value
        }

    /** The largest scale of all monitors (X11 programs draw at it and are made smaller elsewhere). */
    val largestScale: Double get() = monitorList.maxOf { it.scale }

    /**
     * Shows [view]'s part of [monitor]'s background (a view moving over a big wallpaper and
     * zooming, as a canvas does); null: the whole background as usual.
     */
    fun setBackgroundView(monitor: Monitor, view: BackgroundView?) = screens.setBackgroundView(monitor, view)

    /** Gives [monitor] the scale [value] (the picture melts into the new one). */
    fun setScale(monitor: Monitor, value: Double) = screens.setScale(monitor, value)

    /**
     * A monitor came: it shows a workspace of its own from now on. [x], [y] (desktop units)
     * place it; without them it goes to the right of the others. [scale] is its own, or the
     * desktop's ([Desktop.scale]).
     */
    fun addMonitor(name: String, output: Output?, pixelWidth: Int, pixelHeight: Int, scale: Double = defaultScale, x: Int? = null, y: Int? = null): Monitor = screens.addMonitor(name, output, pixelWidth, pixelHeight, scale, x, y)

    /** A monitor went away: its workspaces stay (Super+N brings them back on another monitor). */
    fun removeMonitor(monitor: Monitor) = screens.removeMonitor(monitor)

    /**
     * Turns [monitor] ([Transform.ROTATE_90] for a screen standing on its side): its size and
     * what programs are told follow. The backend draws it turned (if it can, see [MonitorControl.canTransform]).
     */
    fun setTransform(monitor: Monitor, transform: Transform) = screens.setTransform(monitor, transform)

    /** Switches [monitor]'s screen off (false) or on again (DPMS); false if the backend cannot. */
    fun setPower(monitor: Monitor, on: Boolean): Boolean = screens.setPower(monitor, on)

    /** Runs [listener] when a monitor's screen is switched off or on. */
    fun onPowerChanged(listener: (Monitor) -> Unit): AutoCloseable = events.onPowerChanged(listener)

    /** Puts [monitor] at [x], [y] on the desktop (units); null: to the right of the others. */
    fun moveMonitor(monitor: Monitor, x: Int?, y: Int?) = screens.moveMonitor(monitor, x, y)

    /** [monitor] is now [pixelWidth] x [pixelHeight] pixels (a new mode, the nested window resized). */
    fun resizeMonitor(monitor: Monitor, pixelWidth: Int, pixelHeight: Int) = screens.resizeMonitor(monitor, pixelWidth, pixelHeight)

    /** The size in pixels of the monitor with the focus. */
    val pixelWidth: Int get() = focusedMonitor.pixelWidth
    val pixelHeight: Int get() = focusedMonitor.pixelHeight

    /** The first monitor is now [pixelWidth] x [pixelHeight] pixels (with one monitor: the screen). */
    fun resizeScreen(pixelWidth: Int, pixelHeight: Int) = resizeMonitor(monitorList.first(), pixelWidth, pixelHeight)

    /** A rectangle in desktop units as pixels of the monitor it is on (edges rounded the same way everywhere). */
    fun toPixels(rect: Rect): Rect = monitorAt(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0).toPixels(rect)

    /** The monitor at a desktop point, or the nearest one. */
    fun monitorAt(x: Double, y: Double): Monitor =
        monitorList.firstOrNull { it.contains(x, y) } ?: monitorList.minBy { screens.distance(it.area, x, y) }

    /** The monitor showing [output], if any. */
    fun monitorOf(output: Output): Monitor? = monitorList.firstOrNull { it.output === output }

    /**
     * The monitor a window is on: the one its workspace is shown on (or was last) — or, for
     * a window your [director] places, the one it is drawn on most (its workspace is only
     * where it opened: a menu, its scale and its size limits are of where you see it).
     */
    fun monitorOf(window: Window): Monitor {
        if (director != null) animator.screenRects[window]?.takeIf { directed(window) }?.let { rect -> return monitorAt(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0) }
        return workspaceHost.workspaceOf(window)?.monitor ?: focusedMonitor
    }

    /** Size of the monitor with the focus, in desktop units. */
    val width: Int get() = focusedMonitor.area.width
    val height: Int get() = focusedMonitor.area.height

    /**
     * Called whenever something may have to be drawn again (a program drew, a window moved,
     * the pointer moved). The backend then asks for a frame on every screen.
     */
    var onChange: () -> Unit = {}

    internal val views = ArrayList<DesktopView>()

    /** A view of [monitor] (the first one if not said), for drawing it on its screen. */
    fun createView(monitor: Monitor = monitorList.first()): DesktopView = DesktopView(monitor).also { views += it }

    fun removeView(view: DesktopView) {
        views -= view
        for (feedback in view.inFlight) feedback.discard()
        view.inFlight.clear()
        lockHost.lockShownOn -= view
        lockHost.confirmLockIfShown() // one screen less to wait for
    }

    init {
        // Every commit may change what is on screen.
        compositor.onNewSurface { surface ->
            surface.onCommit { frames.surfaceCommitted(surface) }
            surface.afterCommit = { frames.commitSettled(surface) }
            surface.resource.onDestroy { frames.keptUpdating.remove(surface) }
        }
        seat.onCursorChange = ::changed
        // A subsurface gone at once (destroyed, taken off): its picture is not drawn any more.
        compositor.surfaceGone = { changed() }
        // The surface with the keyboard was destroyed before its window or menu closed (or its
        // program quit): once the dust settles, the next one gets the keyboard.
        seat.keyboardFocusDestroyed = { compositor.display.loop.post { if (seat.keyboardFocus == null) focuser.keyboardLost() } }
    }

    // Grows with every change; the list of things to draw is rebuilt only when it moved on.
    internal var version = 0L

    internal fun changed() {
        version++
        onChange()
        events.windowListChanged()
    }

    /**
     * A program's picture still being drawn by its GPU waits for it: until then the picture before
     * stays, and one slow program does not make every window miss the screen's refresh. Needs
     * Linux 6.0 (else programs go on as without this); only GPU buffers. Off by default.
     */
    var waitForClientGpu: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            renderLog.info { if (value) "Programs' pictures wait for their GPU" else "Programs' pictures do not wait for their GPU" }
            val fences = ClientFences(renderLog)
            compositor.clientFences = if (value) { state -> state.buffer?.dataAs<DmabufBuffer>()?.let { fences.of(it.dmabuf) }.orEmpty() } else null
        }

    /** Runs [action] on the compositor's thread in [millis] milliseconds (cancel it with what comes back). */
    fun after(millis: Long, action: () -> Unit): dev.caulis.event.EventLoop.Timer = compositor.display.loop.after(millis, action)

    /** Something your rules depend on changed (a window's tags): looks and effects are asked again. */
    fun requestRedraw() = changed()

    /**
     * Your compositor places the windows itself, every frame ([WindowDirector]); null: the
     * layouts do. The windows it manages leave their layouts at once.
     */
    var director: WindowDirector? = null
        set(value) {
            field = value
            layoutLog.info { if (value == null) "Windows are placed by the layouts again" else "Windows are placed by $value" }
            places = emptyMap()
            for (workspace in workspaceHost.workspaces.values) arranger.arrange(workspace)
            changed()
        }

    // True if the director places [window] itself.
    internal fun directed(window: Window): Boolean {
        val d = director ?: return false
        return userCode("Your WindowDirector (manages)", false) { d.manages(window) }
    }

    /**
     * Draws [drawing] at [level] from now on (with the others there, in the order added);
     * close the result to take it away. It takes no input: clicks go through to what is below.
     */
    fun addDrawing(level: DrawingLevel, drawing: Drawing): AutoCloseable = widgetHost.addDrawing(level, drawing)

    /** The [Widget] that has the keyboard now (see [focusWidget]), or null: a program has it. */
    var keyboardWidget: Widget? = null
        internal set

    /**
     * Shows [widget] from now on (the last added is on top); close the result to take it
     * away (and to close the widget, if it can be). Its reserved room is kept free at once.
     */
    fun addWidget(widget: Widget): AutoCloseable = widgetHost.addWidget(widget)

    /** The widgets changed their place or reserved room: windows and panels make way anew, and they are drawn again. */
    fun updateWidgets() {
        for (monitor in monitorList) layerHost.arrangeLayers(monitor)
        changed()
    }

    /**
     * Gives the keyboard to [widget] (programs lose it meanwhile), or back to the focused window
     * (null). A click elsewhere, or another window getting the focus, ends it too. While the
     * session is [locked] only the lock screen and the widgets that say [Widget.showsWhileLocked]
     * may have it; behind the compositor's own lock screen null gives it back to that screen.
     */
    fun focusWidget(wanted: Widget?) = widgetHost.focusWidget(wanted)

    // Where the director showed each of its windows in the frame last built (what is on screen
    // now: drawn there, and found there by the pointer).
    internal var places: Map<Window, WindowPlace> = emptyMap()
        internal set

    override fun windowInfoChanged(window: Window) = windowRoles.windowInfoChanged(window)

    /**
     * Everything the window manager knows right now, as text: the screen, the focus, every
     * workspace with its windows (where, tiled or floating, minimized, ...), scratchpads,
     * panels, the lock. Log it when something looks wrong (Super+F12 in caulis-wm).
     */
    fun describe(): String = report.describe()

    /**
     * Draws [monitor] as it is on screen into [target] (as big as the monitor in pixels),
     * with or without the [cursor]. For screenshots and screen sharing: what is on screen
     * does not change, and programs are not told about this picture.
     */
    fun capture(target: GpuTarget, cursor: Boolean, monitor: Monitor = monitorList.first()) = capturing.capture(target, cursor, monitor)

    /**
     * Draws [window] alone into [target] (as big as the window), see-through around it —
     * also when it is hidden or on another workspace. For sharing one window. A window hidden
     * from capture ([WindowBehavior.hiddenFromCapture]) is a black box, as on a shared screen.
     */
    fun captureWindow(window: Window, target: GpuTarget) = capturing.captureWindow(window, target)

    /** The size [captureWindow] draws [window] at, in pixels (at the scale of its monitor). */
    fun windowPixelSize(window: Window): Pair<Int, Int> = capturing.windowPixelSize(window)

    /** Asks for a refresh soon (the backend then calls [refresh]), e.g. because a screenshot waits. */
    internal fun requestFrame() = onChange()

    // --- the window list (for docks, taskbars and your own code) --------------------------------

    /** Every window: on all workspaces (minimized ones too) and in scratchpads. */
    val allWindows: List<Window>
        get() = workspaceHost.workspaces.values.flatMap { it.windows + it.minimized } + pads.allPads.flatMap { it.windows }

    /** True if [window] is minimized (on any workspace). */
    fun isMinimized(window: Window): Boolean = workspaceHost.workspaces.values.any { window in it.minimized }

    /**
     * Runs [listener] when the windows or what a window list shows of them changed: a window
     * opened or closed, its title, the focus, minimized, maximized, fullscreen. Many changes
     * at once (a whole batch from the programs) are one call, at the end of the batch.
     */
    fun onWindowListChanged(listener: () -> Unit): AutoCloseable = events.onWindowListChanged(listener)

    /**
     * Where [window] goes when it is minimized (and comes back from): [rect] inside
     * [surface], e.g. its icon in a dock. Null for none (it then just sinks down). Docks set
     * this through the wlr-foreign-toplevel protocol.
     */
    fun setMinimizeTarget(window: Window, surface: Surface?, rect: Rect?) = events.setMinimizeTarget(window, surface, rect)

    // --- events for your own code ------------------------------------------------------------

    /** Runs [listener] when a window's title or app id changes. Close the result to stop. */
    fun onWindowInfoChanged(listener: (Window) -> Unit): AutoCloseable = events.onWindowInfoChanged(listener)

    /** Runs [listener] when a window appears (after it got its place and the focus). Close the result to stop. */
    fun onWindowOpened(listener: (Window) -> Unit): AutoCloseable = events.onWindowOpened(listener)

    /** Runs [listener] when a window goes away. Close the result to stop. */
    fun onWindowClosed(listener: (Window) -> Unit): AutoCloseable = events.onWindowClosed(listener)

    /** Runs [listener] when another window gets the keyboard (null = none). Close the result to stop. */
    fun onFocusChanged(listener: (Window?) -> Unit): AutoCloseable = events.onFocusChanged(listener)

    /** Runs [listener] when another workspace is shown. Close the result to stop. */
    fun onWorkspaceChanged(listener: (Workspace) -> Unit): AutoCloseable = events.onWorkspaceChanged(listener)

    // The log, by area (see dev.caulis.log.Log.AREAS): CAULIS_LOG="1,focus=3" and so on.
    internal val log = Logger("config")
    internal val windowsLog = Logger("windows")
    internal val focusLog = Logger("focus")
    internal val inputLog = Logger("input")
    internal val layoutLog = Logger("layout")
    internal val layersLog = Logger("layers")
    internal val lockLog = Logger("lock")
    internal val cursorLog = Logger("cursor")
    internal val renderLog = Logger("render")
    internal val overlayLog = Logger("overlay")

    // --- workspaces -----------------------------------------------------------------------

    /**
     * The layout new workspaces start with. Setting it changes every workspace at once;
     * windows move to their new places right away (see [setLayout] for one workspace).
     */
    var layout: Layout = Floating
        set(value) {
            field = value
            for (workspace in workspaceHost.workspaces.values) if (workspace.number > 0 && workspaceHost.ruleOf(workspace.number)?.layout == null) setLayout(workspace, value)
        }

    /**
     * How workspaces behave with several monitors (see [WorkspaceMode]): shared numbers shown
     * on any monitor (the default), workspaces pinned to monitors, workspaces of each
     * monitor's own, one spanning all, or only on the primary monitor. Set it before monitors
     * come.
     */
    var workspaceMode: WorkspaceMode = WorkspaceMode.PER_MONITOR

    /**
     * The primary monitor's connector ("DP-2"); null, or not plugged in: the first monitor.
     * With [WorkspaceMode.PRIMARY_ONLY] only it has workspaces.
     */
    var primaryMonitorName: String? = null
        set(value) {
            field = value
            workspaceHost.arrangePrimary()
        }

    /** The primary monitor (see [primaryMonitorName]). */
    val primaryMonitor: Monitor get() = monitorList.firstOrNull { it.name == primaryMonitorName } ?: monitorList.first()

    /** How many workspaces there are at most, 1 to 99 (higher numbers are refused). 1: no workspaces — one desktop. */
    var workspaceCount = 9
        set(value) {
            require(value in 1..99) { "workspaceCount is 1 to 99, not $value" }
            field = value
        }

    /** Rules for single workspaces: names, home monitors, layouts, persistent ones (see [WorkspaceRule]). */
    var workspaceRules: List<WorkspaceRule> = emptyList()
        set(value) {
            field = value.toList()
            workspaceHost.applyWorkspaceRules()
        }

    /** The name of workspace [number] from its rule, or null. */
    fun workspaceName(number: Int): String? = workspaceHost.ruleOf(number)?.name

    /** The workspace shown before the one with the focus now (for "back and forth"), or null. */
    var previousWorkspaceNumber: Int? = null
        internal set

    /** The workspace on the monitor with the focus. */
    val activeWorkspace: Workspace get() = focusedMonitor.workspace

    /** The number of the workspace [window] is on (null: in a scratchpad, or not open). */
    fun workspaceNumberOf(window: Window): Int? = if (scratchpadOf(window) != null) null else workspaceHost.workspaceOf(window)?.number

    init {
        // Until the backend adds monitors: one of the size given (tests, the very first frame).
        val standIn = Monitor("default", null, screenWidth, screenHeight, 1.0)
        val first = Workspace(1, layout).also { it.monitor = standIn; it.leftOut = ::directed }
        workspaceHost.workspaces[DesktopWorkspaces.WorkspaceKey(1, null)] = first
        standIn.workspace = first
        monitorList += standIn
        placeholder = standIn
        focusedMonitor = standIn
    }

    init {
        // A surface not shown yet is on the monitor where new windows open.
        compositor.defaultOutputs = { listOfNotNull(focusedMonitor.output).ifEmpty { Output.of(compositor.display) } }
    }

    /** Workspaces that exist now (those shown and those with windows), in order. */
    val allWorkspaces: Collection<Workspace> get() = workspaceHost.workspaces.values

    /**
     * Numbers of the workspaces there are (shown, with windows, or persistent) that Super+N
     * reaches from the monitor with the focus, in order.
     */
    fun workspaceNumbers(): List<Int> = workspaceHost.workspaceNumbers()

    /** The workspace [steps] after (or before, negative) the active one among those there are, going round. */
    fun workspaceAfter(steps: Int): Int = workspaceHost.workspaceAfter(steps)

    /** The lowest workspace number with no windows (null: all [workspaceCount] have some). */
    fun firstEmptyWorkspace(): Int? = workspaceHost.firstEmptyWorkspace()

    /** Windows on the active workspace, bottom to top. */
    val windows: List<Window> get() = activeWorkspace.windows

    /** Minimized windows of the active workspace. */
    val minimized: List<Window> get() = activeWorkspace.minimized

    /**
     * Shows workspace [number] (created if needed) on the monitor with the focus; its top
     * window gets the keyboard. If another monitor shows it, the focus goes there instead.
     * With spanning workspaces, every monitor switches.
     */
    fun switchTo(number: Int) = workspaceHost.switchTo(number)

    /** Gives the focus to [monitor] (its top window gets the keyboard). */
    fun focusMonitor(monitor: Monitor) = workspaceHost.focusMonitor(monitor)

    /** The monitor next to the focused one in a direction ([dx], [dy] = -1, 0 or 1), if any. */
    fun monitorBeside(dx: Int, dy: Int, from: Monitor = focusedMonitor): Monitor? = screens.monitorBeside(dx, dy, from)

    /** Moves [window] to the workspace shown on [monitor] (it keeps its place there if floating). */
    fun moveToMonitor(window: Window, monitor: Monitor) = workspaceHost.moveToMonitor(window, monitor)

    /**
     * Moves [window] to workspace [number] (if floating, it keeps its place — on that
     * workspace's monitor when it has one of its own).
     */
    fun moveToWorkspace(window: Window, number: Int) = workspaceHost.moveToWorkspace(window, number)

    /** Uses [layout] on [workspace]; windows move to their new places right away. */
    fun setLayout(workspace: Workspace, layout: Layout) = workspaceHost.setLayout(workspace, layout)

    /** Panels, docks, wallpapers, ... that are set up (configured), any layer. */
    val layers: List<LayerSurface> get() = layerHost.layerSurfaces

    /**
     * The part of the monitor with the focus that panels and docks leave free (their
     * "exclusive zones"): windows are tiled and maximized in here. Each monitor has its own
     * ([Monitor.usableArea]).
     */
    val usableArea: Rect get() = focusedMonitor.usableArea

    /** The monitor [layer] is on: the one its program asked for, else the one it came on. */
    fun monitorOf(layer: LayerSurface): Monitor =
        layer.output?.let { output -> monitorList.firstOrNull { it.output === output } }
            ?: layerHost.layerMonitors.getOrPut(layer) { focusedMonitor }

    override fun layerCommitted(layer: LayerSurface) = layerHost.layerCommitted(layer)

    override fun layerUnmapped(layer: LayerSurface) = layerHost.layerUnmapped(layer)

    /**
     * True while the session is locked — by a screen locker program such as swaylock
     * (ext-session-lock), or by the compositor's own lock screen ([lockInternally]): keys, the
     * pointer, touches and pens go to the lock screen only, no window or panel takes the
     * focus, and windows cannot be recorded. Unlocking is the locker's job (after the user
     * typed the password); if a locker program crashes, the session stays locked and a newly
     * started locker takes over.
     */
    val locked: Boolean get() = lockHost.sessionLocked || lockHost.ownLock != null

    /**
     * The colour under everything: where no background, wallpaper or window is drawn (a
     * dark slate blue by default).
     */
    var backgroundColor: Color = Color(0.16f, 0.18f, 0.22f)
        set(value) {
            if (value != field) renderLog.info { "Background colour: $value" }
            field = value
            views.forEach { it.damageEverything() }
            changed()
        }

    /** The colour behind the locker (and all there is until it has drawn). */
    var lockColor: Color = Color(0f, 0f, 0f)

    /**
     * Behind the compositor's own lock screen when it shows the desktop ([lockInternally]
     * without hideDesktop): whether programs' panels (layer shell: bars, a notification
     * daemon's pop-ups), menus and tooltips, and X11's unmanaged windows are still drawn. False
     * (the default) leaves them out — what a notification or a bar shows may be private —
     * except the surfaces the lock's allowSurface lets in (a lock screen that is a panel
     * program); true draws them as the rest of the desktop. Windows are yours to hide (a
     * [WindowDirector] fading them out, as newm does). A locker program's lock, and
     * hideDesktop, show none of them either way.
     */
    var panelsShowWhileLocked: Boolean = false
        set(value) {
            if (value != field) lockLog.info { "Panels, menus and unmanaged windows behind the own lock screen: ${if (value) "drawn" else "hidden"}" }
            field = value
            changed()
        }

    /** The colour shown when the locker crashed: start a new one (e.g. from a text console). */
    var abandonedLockColor: Color = Color(0.35f, 0.05f, 0.05f)

    /** Runs [listener] when the session gets locked (true) or unlocked (false), by a locker program or [lockInternally]. */
    fun onLockChanged(listener: (Boolean) -> Unit): AutoCloseable = lockHost.onLockChanged(listener)

    /** True while the compositor's own lock screen is up ([lockInternally]; a locker program may have locked on top of it). */
    val lockedInternally: Boolean get() = lockHost.ownLock != null

    /**
     * What [lockInternally] with hideDesktop = true shows behind the lock screen, besides
     * [lockColor]: the desktop's own wallpaper, blurred and dimmed as it says. Null (the usual):
     * the plain colour. Never any window, panel or program's picture (a wallpaper program's
     * neither: only the wallpaper you set and your drawings at [DrawingLevel.WALLPAPER]). A
     * locker program's lock always shows the plain colour.
     */
    var lockBackdrop: LockBackdrop? = null
        set(value) {
            field = value
            changed()
        }

    /** The widget showing the compositor's own lock screen ([lockInternally]), or null. */
    val lockScreen: Widget? get() = lockHost.ownLock?.screen

    /**
     * Locks the session with the compositor's own lock screen: [screen], a [Widget] you draw
     * (a clock and a password field), gets the keyboard, and the session is [locked] exactly
     * as with a locker program — only [screen] and the widgets that say [Widget.showsWhileLocked]
     * get keys, the pointer, touches and pens; no window or panel takes the focus (new
     * windows, closed windows, fullscreen and activation requests change nothing until
     * [unlockInternally]); menus, the overview and overlays close; idle inhibitors stop
     * counting; windows cannot be recorded. It lasts across monitors coming and going.
     *
     * The desktop stays drawn behind [screen] (as newm dims it) unless [hideDesktop]: then
     * only [lockColor] and the usable widgets are drawn. [allowSurface] lets programs' surfaces
     * have input too (a lock screen that is a panel program; none by default). [screen] need
     * not be added with [addWidget]: if it is not, it is shown for the time of the lock.
     * Calling this while locked already puts [screen] in charge. Checking the password and
     * calling [unlockInternally] is your code's job.
     */
    @JvmOverloads
    fun lockInternally(screen: Widget, hideDesktop: Boolean = false, allowSurface: (Surface) -> Boolean = { false }) = lockHost.lockInternally(screen, hideDesktop, allowSurface)

    /**
     * Ends the compositor's own lock screen ([lockInternally]): the session is unlocked
     * (unless a locker program locked it meanwhile — then that one's unlock is still needed),
     * and the focused window, or the panel that keeps the keyboard, gets the keyboard again.
     */
    fun unlockInternally() = lockHost.unlockInternally()

    /**
     * Which window gets the focus when the session is unlocked: by default the one that had it
     * when the session was locked. A window that opened while locked never gets it (what the
     * user types right after unlocking could be a password).
     */
    var unlockFocus: UnlockFocus = UnlockFocus.FOCUSED_BEFORE
        set(value) {
            if (field != value) lockLog.info { "After unlocking the focus goes to: ${value.name.lowercase()}" }
            field = value
        }

    override fun lockRequested(lock: SessionLock): Boolean = lockHost.lockRequested(lock)

    override fun lockSurfaceSize(output: Output?): Pair<Int, Int> = lockHost.lockSurfaceSize(output)

    override fun lockSurfaceCommitted(surface: LockSurface) = lockHost.lockSurfaceCommitted(surface)

    override fun unlockRequested(lock: SessionLock) = lockHost.unlockRequested(lock)

    override fun lockAbandoned(lock: SessionLock) = lockHost.lockAbandoned(lock)

    // --- scratchpads (special workspaces) -----------------------------------------------------

    /**
     * A special workspace named [name]: windows for which [match] is true (e.g. a certain
     * app id) live in it, shown and hidden with [toggleScratchpad] above any workspace.
     */
    fun addScratchpad(name: String, style: Scratchpad.Style, match: (Window) -> Boolean): Scratchpad = pads.addScratchpad(name, style, match)

    /** The scratchpad named [name], if there is one. */
    fun scratchpad(name: String): Scratchpad? = pads.scratchpads[name]

    /** The scratchpad (or special workspace, see [Scratchpad.isSpecial]) [window] lives in, if any. */
    fun scratchpadOf(window: Window): Scratchpad? = pads.allPads.firstOrNull { window in it.windows }

    // --- special workspaces (Hyprland's "special", on the scratchpads' machinery) ----------

    /**
     * How special workspaces look and behave: dimming and blur behind them, their size, their
     * animation, whether they hide when empty or on the lock (see [SpecialWorkspaces]).
     */
    var specialWorkspaces: SpecialWorkspaces = SpecialWorkspaces.USUAL
        set(value) {
            field = value
            layoutLog.info { "Special workspaces: $value" }
            for (pad in pads.shownPads) if (pad.isSpecial && !pad.leaving) pads.placeScratchpad(pad, appearing = emptySet())
            changed()
        }

    /** The special workspaces made so far (each the first time it was named), by name. */
    val specialWorkspaceNames: List<String> get() = pads.specials.keys.toList()

    /** The special workspace [name] ("special" for the one without a name), made now if it is new. */
    @JvmOverloads
    fun specialWorkspace(name: String = SpecialWorkspaces.DEFAULT_NAME): Scratchpad = pads.specialWorkspace(name)

    /** The special workspace shown on [monitor] (null: none). */
    fun shownSpecialOn(monitor: Monitor): Scratchpad? = pads.shownPads.lastOrNull { it.isSpecial && !it.leaving && pads.padMonitor(it) === monitor }

    /**
     * Shows the special workspace [name] over the workspace on the focused monitor (hiding
     * another one shown there), or hides it if it is shown: Hyprland's `togglespecialworkspace`.
     * It shows even without windows (new ones then open in it). Returns true if it is shown now.
     */
    @JvmOverloads
    fun toggleSpecialWorkspace(name: String = SpecialWorkspaces.DEFAULT_NAME): Boolean = pads.toggleSpecialWorkspace(name)

    /** Shows the special workspace [name] on the focused monitor (nothing if it is shown there already). */
    @JvmOverloads
    fun showSpecialWorkspace(name: String = SpecialWorkspaces.DEFAULT_NAME) = pads.showSpecialWorkspace(name)

    /** Hides the special workspace [name] if it is shown. */
    @JvmOverloads
    fun hideSpecialWorkspace(name: String = SpecialWorkspaces.DEFAULT_NAME) = pads.hideSpecialWorkspace(name)

    /**
     * Moves [window] into the special workspace [name] (made if new). It shows there, with
     * the focus — unless [silent]: then it waits there (and the focus stays on this workspace),
     * as Hyprland's `movetoworkspacesilent special:name`.
     */
    @JvmOverloads
    fun moveToSpecialWorkspace(window: Window, name: String = SpecialWorkspaces.DEFAULT_NAME, silent: Boolean = false) = pads.moveToSpecialWorkspace(window, name, silent)

    /**
     * Moves [window] out of its special workspace (or scratchpad) onto the workspace shown on
     * the focused monitor, tiled there as usual. False if it is in none.
     */
    fun moveOutOfSpecialWorkspace(window: Window): Boolean = pads.moveOutOfSpecialWorkspace(window)

    /**
     * Moves a window out of a special workspace onto the workspace shown on the focused
     * monitor (Hyprland's `movetoworkspace +0` from a special one, for a key): the focused
     * window if it is in one; else the one focused last in the special workspace shown on the
     * monitor worked on ([WindowDirector.workMonitor], by default the focused one); else the one focused last in the special workspace used last, shown or
     * hidden — so the key brings a window back with its special workspace hidden too. Returns
     * the window moved, or null if there was none.
     */
    fun moveOutOfSpecialWorkspace(): Window? = pads.moveOutOfSpecialWorkspace()

    /**
     * Where the special workspace shown on [monitor] tiles its windows (inside its gap, as big
     * as [SpecialWorkspaces.size] says; desktop units), or null if none is shown there. Around
     * it the dimmed workspace shows: a window dragged there leaves the special workspace.
     */
    fun specialWorkspaceArea(monitor: Monitor): Rect? = shownSpecialOn(monitor)?.let(pads::specialArea)

    /**
     * A window of a shown special workspace dragged by ([dx], [dy]) from its tile (desktop
     * units), drawn there until [dropFromSpecialWorkspace]. For a window manager's own drag
     * (newm's Super+drag); [beginMove] does the same with the pointer.
     */
    fun dragInSpecialWorkspace(window: Window, dx: Int, dy: Int) = pads.dragInSpecialWorkspace(window, dx, dy)

    /**
     * The drag of [window] (of a special workspace) ends with the pointer at ([x], [y]): over
     * the dimmed workspace around the special workspace it moves out onto the workspace there
     * (as [moveOutOfSpecialWorkspace]), focused; within it, it goes back to its tile. Returns
     * true if it moved out.
     */
    fun dropFromSpecialWorkspace(window: Window, x: Double, y: Double): Boolean = pads.dropFromSpecialWorkspace(window, x, y)

    /**
     * Shows the scratchpad [name], or hides it if it is shown. Returns false if it has no
     * windows yet — then start its program; its window appears as soon as it opens.
     */
    fun toggleScratchpad(name: String): Boolean = pads.toggleScratchpad(name)

    /** Moves [window] from its workspace into the scratchpad [name] (and shows it). */
    fun moveToScratchpad(window: Window, name: String) = pads.moveToScratchpad(window, name)

    // --- placing windows: layouts and transactions ---------------------------------------

    /**
     * Decides whether a new window floats instead of being tiled. By default: dialogs and
     * windows that cannot be resized. Replace it for your own rules (e.g. by app id).
     */
    var floatRule: (Window) -> Boolean = { it.parent != null || it.fixedSize || it.prefersFloating }

    /**
     * Asks the active workspace's layout to do [command] for the focused window (move it,
     * resize it, preselect, pan the canvas, ...). Returns true if the layout did something;
     * windows then glide to their new places.
     */
    fun tilingCommand(command: TilingCommand): Boolean = arranger.tilingCommand(command)

    /**
     * Extra space around some windows inside their places in the layout (null: none), e.g.
     * from window rules. Asked whenever windows are arranged; after a change of its answer
     * for a window, call [marginsChanged].
     */
    var marginsFor: (Window) -> Margins? = { null }

    /** [window]'s margins may be different now: its workspace is arranged again. */
    fun marginsChanged(window: Window) = arranger.marginsChanged(window)

    // --- overview: the whole workspace at a glance ------------------------------------------

    /** True while the overview shows the whole workspace. */
    var inOverview = false
        internal set

    /**
     * Zooms out until every window of the workspace fits on screen — also those beyond the
     * edges of a canvas or a strip of columns — or back in. In the overview, a click on a
     * window brings it into view and focuses it; Escape or a click elsewhere just goes back.
     * Windows keep their sizes: only their pictures get smaller.
     */
    fun setOverview(on: Boolean) = overview.setOverview(on)

    fun toggleOverview() = setOverview(!inOverview)

    /** How much of the monitor the overview's windows take (0.3 to 1: the rest is a margin around them). */
    var overviewFill = 0.9
        set(value) {
            require(value in 0.3..1.0) { "overviewFill is 0.3 to 1" }
            field = value
        }

    /** How wide the edge around floating windows with our title bar is, to grab them for resizing (units). */
    var resizeBorder = 6
        set(value) {
            require(value in 0..50) { "resizeBorder is 0 to 50" }
            field = value
        }

    /** Two clicks on a title bar within this many milliseconds are a double click (maximize). */
    var doubleClickMillis = 400L

    /**
     * Opens the overview part of the way ([progress] 0 = normal view, 1 = fully zoomed out),
     * following the fingers of a pinch. Finish with [endOverviewGesture].
     */
    fun setOverviewProgress(progress: Double) = overview.setOverviewProgress(progress)

    /** The fingers lifted: the overview finishes opening ([open]) or closing, smoothly from where it is. */
    fun endOverviewGesture(open: Boolean) = overview.endOverviewGesture(open)

    // --- following the fingers --------------------------------------------------------------

    /**
     * Moves the tiled windows of the workspace by ([dx], [dy]) pixels, right under the
     * fingers of a swipe. Finish with [endViewDrag].
     */
    fun dragView(dx: Double, dy: Double) = overview.dragView(dx, dy)

    /**
     * The fingers lifted: the layout takes the new view, rounded to what fits (whole cells
     * on a canvas), and the windows glide there from where the fingers left them — or back,
     * if the layout cannot move its view. If the focused window went out of sight, the
     * window most in view gets the focus.
     */
    fun endViewDrag() = overview.endViewDrag()

    // --- surfaces that place themselves (X11 menus and tooltips) ---------------------------

    /**
     * Shows [surface] at [x], [y] (desktop units) above the windows, as it places itself —
     * an X11 menu or tooltip ("override-redirect" window). Again to move it.
     */
    fun showUnmanaged(surface: Surface, x: Int, y: Int) = overlayHost.showUnmanaged(surface, x, y)

    fun hideUnmanaged(surface: Surface) = overlayHost.hideUnmanaged(surface)

    /**
     * Gives the keyboard to [surface], shown with [showUnmanaged] — an X11 launcher, game or
     * menu that grabbed the keyboard; null gives it back to the focused window.
     */
    fun focusUnmanaged(surface: Surface?) = overlayHost.focusUnmanaged(surface)

    // --- title bars (server-side decorations) ---------------------------------------------

    /**
     * Who draws windows' title bars: the programs (the default), the compositor (the same
     * for all, see [titleBarStyle]) or nobody. Programs that do not take part (GTK 4 apps,
     * for instance, whose header bar is part of their window) always draw their own; the
     * compositor then adds nothing, so there are never two. Fullscreen windows have none.
     */
    var titleBars: TitleBars = TitleBars.PROGRAM
        set(value) {
            field = value
            decorationsChanged()
        }

    /** Title bars for one window, e.g. `{ if (it.appId == "spotify") TitleBars.COMPOSITOR else null }` (null = [titleBars]). */
    var titleBarsFor: ((Window) -> TitleBars?)? = null
        set(value) {
            field = value
            decorationsChanged()
        }

    /** How the compositor's title bars look (sizes, font, colours, buttons; or a painter of your own). */
    var titleBarStyle: TitleBarStyle = TitleBarStyle()
        set(value) {
            field = value
            ownTitleBars.close()
            for (window in allWindows) overlayHost.decorationOfChanged(window)
        }

    // --- the background -----------------------------------------------------------------------

    /**
     * What the desktop shows behind everything: a colour, a gradient, a picture, your own
     * drawing or a shader, still or moving (see [Background]). A wallpaper program on the
     * background layer draws on top of it.
     */
    var background: Background
        get() = backdrops.background
        set(value) {
            backdrops.background = value
        }

    /**
     * A background per monitor and workspace (number), e.g. a picture for each workspace:
     * `backgroundFor = { monitor, workspace -> Background.image(Path.of("$workspace.jpg")) }`.
     * Null (or a null answer): [background]. Asked every frame: keep it quick.
     */
    var backgroundFor: ((Monitor, Int) -> Background?)?
        get() = backdrops.backgroundFor
        set(value) {
            backdrops.backgroundFor = value
        }

    /** How long one background melts into the next (another workspace's, a new setting), in milliseconds. */
    var backgroundChangeMillis: Long
        get() = backdrops.changeMillis
        set(value) {
            require(value in 0..10_000) { "backgroundChangeMillis is 0 to 10000" }
            backdrops.changeMillis = value
        }

    // --- the compositor's own notices -------------------------------------------------------

    /**
     * How the compositor's notices look and where they are: a strip at the top that pushes
     * windows down (the default), cards in a corner, not drawn at all, ... (see [NoticeStyle]).
     */
    var noticeStyle: NoticeStyle
        get() = noticeBoard.style
        set(value) {
            noticeBoard.style = value
        }

    /**
     * Shows [text] on screen as the compositor's own message — on the monitor with the focus
     * (or every one, see [NoticeStyle.everyMonitor]); clicking it closes it. The same message
     * again is counted instead of shown twice.
     */
    @JvmOverloads
    fun notify(text: String, level: NoticeLevel = NoticeLevel.INFO, title: String? = null): Notice =
        noticeBoard.add(level, text, title, if (this::focusedMonitor.isInitialized) focusedMonitor.name else null)

    internal val osd = Osd(renderer).also { it.fade = { animation("fadeOsd") } }

    /** What the on-screen display says now ("Volume 45 %"), or null when it is not shown. */
    val onScreenDisplay: String? get() = osd.look?.title

    /**
     * Shows what a key changed on the on-screen display (a pill at the bottom of the focused
     * monitor): [title] ("Volume 45 %", "Touchpad off") and, with a [level] 0..1, a bar —
     * grey when [off] (muted). A new one replaces it at once; it goes by itself.
     */
    @JvmOverloads
    fun showLevel(title: String, level: Double? = null, off: Boolean = false) = overlayHost.showLevel(title, level, off)

    /**
     * An on-screen display of your own: told what [showLevel] would show ("Volume 45 %", the
     * level 0..1 or null, muted); true takes it (the desktop's own pill is not shown).
     */
    var osdHandler: ((title: String, level: Double?, off: Boolean) -> Boolean)? = null

    /**
     * The on-screen display with a short list: [title] and [choices], the one at [chosen]
     * marked (a key pressed again moves the mark; see the display modes of a laptop's key).
     */
    fun showChoices(title: String, choices: List<String>, chosen: Int) = overlayHost.showChoices(title, choices, chosen)

    /** The [Overlay] open now (it has the keyboard and the pointer), or null. */
    var overlay: Overlay? = null
        internal set

    /** Opens [overlay] above everything (one closes the one open before). */
    fun openOverlay(overlay: Overlay) = overlayHost.openOverlay(overlay)

    /** Closes the [overlay] open now (if any); programs get the keyboard and pointer again. */
    fun closeOverlay() = overlayHost.closeOverlay()

    /** The [overlay] looks different now: drawn again. */
    fun redrawOverlay() = overlayHost.redrawOverlay()

    /** Closes [notice] (it fades away). */
    fun dismiss(notice: Notice) = noticeBoard.dismiss(notice)

    /** Puts text on the clipboard (a notice's copy button uses it); set by whoever owns the clipboard. */
    var copyText: ((String) -> Unit)? = null

    /** The notices open now, oldest first. */
    val notices: List<Notice> get() = noticeBoard.all.filter { it.open }

    /** Runs [listener] for every new notice (also with [NoticePlacement.Hidden]). Close the result to stop. */
    fun onNotice(listener: (Notice) -> Unit): AutoCloseable {
        noticeBoard.listeners += listener
        return AutoCloseable { noticeBoard.listeners -= listener }
    }

    // Where notices go that belong to no monitor there is now.
    internal fun noticeFallback(monitor: Monitor) = if (this::focusedMonitor.isInitialized) focusedMonitor else monitor

    /** Who draws [window]'s title bar now. */
    fun titleBarsOf(window: Window): TitleBars = titleBarsFor?.let { rule -> userCode("Your titleBarsFor", null) { rule(window) } } ?: titleBars

    /** How high the compositor's title bar over [window] is, in desktop units (0: it has none). */
    fun titleHeight(window: Window): Int = overlayHost.titleHeight(window)

    // Set by ServerDecorations: tells the programs again when the settings change.
    internal var decorationsChanged: () -> Unit = {}

    /** Asks [titleBarsFor] again for every window (its answer may have changed, e.g. with a title). */
    fun titleBarsChanged() = decorationsChanged()

    // --- effects ---------------------------------------------------------------------------

    /**
     * How windows look: rounded corners, borders, shadows, blur, transparency (see [Effects]).
     * Off by default. Per window it can be changed with [windowEffects].
     */
    var effects: Effects = Effects.NONE
        set(value) {
            field = value
            changed()
        }

    /**
     * The effects of one window, e.g. no blur for a video player or no shadow for menus:
     * `desktop.windowEffects = { if (it.appId == "mpv") desktop.effects.copy(blur = null) else desktop.effects }`.
     * Fullscreen windows never get effects.
     */
    var windowEffects: (Window) -> Effects = { effects }
        set(value) {
            field = value
            changed()
        }

    /**
     * True for windows in a group (tabs, a stack) of your compositor's: their border is
     * [Border.groupedFocused] / [Border.groupedUnfocused], and a shader paint learns it
     * (`grouped`). Caulis' own layouts have no groups: false for every window by default.
     */
    var windowGrouped: (Window) -> Boolean = { false }
        set(value) {
            field = value
            renderLog.info("Your windowGrouped rule is in use")
            changed()
        }

    /**
     * The effects of panels, docks and other layer surfaces (none by default), e.g. blur
     * behind a see-through bar: `{ if (it.namespace == "waybar") Effects(blur = Blur()) else Effects.NONE }`.
     */
    var layerEffects: (LayerSurface) -> Effects = { Effects.NONE }
        set(value) {
            field = value
            changed()
        }

    /**
     * A shader the whole screen goes through last (grayscale, a night light, a CRT look), or
     * null. Backends draw it with their [Rotator]; while it is on, nothing is shown directly.
     */
    var screenShader: ScreenShader? = null
        set(value) {
            if (field === value) return
            field = value
            renderLog.info { "Screen shader: ${value ?: "none"}" }
            views.forEach { it.damageEverything() } // (nothing on the desktop changed, yet all of it looks different)
            changed()
        }

    /** How each panel behaves beyond its looks ([LayerBehavior]: dimming what is behind, hidden from capture). */
    var layerBehaviorFor: (LayerSurface) -> LayerBehavior = { LayerBehavior.USUAL }
        set(value) {
            field = value
            changed()
        }

    /**
     * How many times a second borders and shadows that move by themselves (a turning gradient,
     * a shader using time) are drawn anew, at most (0, the default: every frame of the screen).
     * 30 halves the work of an animated focus border on a 60 Hz screen and looks the same.
     */
    var movingPaintsFps = 0
        set(value) {
            require(value in 0..240) { "movingPaintsFps is 0 (every frame) to 240, not $value" }
            if (field == value) return
            field = value
            renderLog.info { if (value == 0) "Moving borders and shadows: every frame" else "Moving borders and shadows: at most $value frames a second" }
        }

    // --- smooth movement -------------------------------------------------------------------

    /**
     * How long windows take to glide to a new place (a layout change, maximize, a
     * neighbour closing), in milliseconds. 0 = they jump.
     */
    var animationMillis = 220L
        set(value) {
            require(value in 0..10_000) { "animationMillis is 0 to 10000, not $value" }
            field = value
            animator.animationSettings = null
        }

    /**
     * All animations: on or off ([Animations.enabled], off by default), each by name (see
     * [AnimationTree.CAULIS]), how windows appear and go away and how workspaces change; how
     * long they take is [animationMillis] unless [Animations] says otherwise.
     */
    var animations: Animations = Animations()
        set(value) {
            field = value
            animator.animationSettings = null
            changed()
        }

    /**
     * What the animation [name] (of [AnimationTree.CAULIS]) comes to now: on or off, how long,
     * along which curve, which look. ([animationMillis] = 0 turns all of them off, as before.)
     */
    @JvmOverloads
    fun animation(name: String, usualMillis: Long? = null, usualCurve: Curve? = null): ResolvedAnimation = animator.animation(name, usualMillis, usualCurve)

    /** Where [window] appears on screen right now, also zoomed out in the overview (null = not drawn yet). */
    fun screenRect(window: Window): Rect? = animator.screenRects[window]

    /**
     * Where [window] is drawn right now — during a glide, somewhere between its old and new
     * place (null if it has not been drawn yet).
     */
    fun shownRect(window: Window): Rect? = animator.shownRects[window]

    /** True if [window] is in the picture on screen now (not hidden, not on a workspace not shown). */
    fun drawnNow(window: Window): Boolean = window.surface in sceneBuilder.visible

    /** True if [window] floats (placed by hand) rather than being tiled by the layout. */
    fun isFloating(window: Window): Boolean = window.tiled.not()

    /** Takes [window] out of the layout (floating) or puts it back (tiled). */
    fun setFloating(window: Window, floating: Boolean) = arranger.setFloating(window, floating)

    // --- where new windows go ----------------------------------------------------------------

    /**
     * Decides where each new window goes and how it starts — workspace, monitor, floating,
     * size, place, maximized, fullscreen ([WindowPlacement]) — before its first frame.
     * Null: as usual. (caulis-wm's `rule { }` sets this.)
     */
    var placementFor: (Window) -> WindowPlacement? = { null }

    /**
     * How each window behaves beyond its place and looks ([WindowBehavior]: size limits,
     * animations, keeping the screen on, hidden from screen sharing, the keyboard, requests
     * not done). Asked whenever it matters, so it may follow the window's title.
     * (caulis-wm's `rule { }` sets this.)
     */
    var behaviorFor: (Window) -> WindowBehavior = { WindowBehavior.USUAL }

    /** What [behaviorFor] says of [window] now (as usual if it fails). */
    fun behaviorOf(window: Window): WindowBehavior = try {
        behaviorFor(window)
    } catch (e: Throwable) {
        e.rethrowIfFatal()
        log.error("Your window behaviour rule failed for $window (ignored): $e", e)
        WindowBehavior.USUAL
    }

    // --- pinned windows: floating, on whichever workspace their monitor shows --------------------

    /** True if [window] is pinned: it floats and stays on screen when its monitor shows another workspace. */
    fun isPinned(window: Window): Boolean = window in arranger.pinned

    /** Pins [window] (it floats, and goes along to every workspace its monitor shows) or lets it go. */
    fun setPinned(window: Window, on: Boolean) = arranger.setPinned(window, on)

    /**
     * Applies [placement] to [window], which is open already (e.g. a rule that matches it
     * only after it changed its title): to its workspace or monitor, floating, its size,
     * maximized, fullscreen.
     */
    fun applyPlacement(window: Window, placement: WindowPlacement) = arranger.applyPlacement(window, placement)

    // --- windows appear and disappear ----------------------------------------------------

    // The layout decides the size before the first frame, so a new window never shows up
    // in a wrong size first (R1). Maximized/fullscreen apps get the whole desktop.
    override fun initialSize(window: Window): Pair<Int, Int> = windowRoles.initialSize(window)

    override fun windowMapped(window: Window) = windowRoles.windowMapped(window)

    override fun windowUnmapped(window: Window) = windowRoles.windowUnmapped(window)

    // Also for a window that never appeared: its placement (asked before its first frame) and
    // its place in a remembering layout (given to learn its size) go.
    override fun windowDestroyed(window: Window) = windowRoles.windowDestroyed(window)

    override fun windowCommitted(window: Window) = windowRoles.windowCommitted(window)

    override fun bounds(window: Window): Pair<Int, Int> = monitorOf(window).area.let { it.width to it.height }

    // --- window states: maximized, fullscreen, minimized ----------------------------------

    /** The surface that gets key presses now: a window, a menu, a panel or a launcher. */
    val keyboardFocus: Surface? get() = seat.keyboardFocus

    /** Open menus (popups), in the order they opened. */
    val openPopups: List<Popup> get() = popupHost.popups

    /** Where [popup] is on screen. */
    fun screenRect(popup: Popup): Rect {
        val (x, y) = hits.popupOrigin(popup)
        val visible = popup.shell.visibleRect
        return Rect(x + visible.x, y + visible.y, visible.width, visible.height)
    }

    /** The window that has the keyboard, if any. */
    val focusedWindow: Window? get() = pads.padFocus?.takeIf { it.activated } ?: windows.lastOrNull()?.takeIf { it.activated }

    override fun maximizeRequested(window: Window, maximized: Boolean) = windowRoles.maximizeRequested(window, maximized)

    override fun fullscreenRequested(window: Window, fullscreen: Boolean) = windowRoles.fullscreenRequested(window, fullscreen)

    /**
     * Whether a program's own request ([WindowRequest.FULLSCREEN], [WindowRequest.RESTORE],
     * X11's [WindowRequest.ACTIVATE]) may give [window] the focus: the rule against focus
     * stealing. By default only if the focused window is this one or another of its program's
     * (a video going fullscreen, a program moving between its own windows); else the window
     * is only marked [urgent][Window.urgent]. xdg-activation with a good token does not ask
     * (the token is the proof); the window manager's own [focus] never does.
     */
    var programMayTakeFocus: (window: Window, why: WindowRequest) -> Boolean = { window, _ ->
        focusedWindow.let { it != null && (it === window || it.sameProgramAs(window)) }
    }

    /**
     * A program asked for the focus for [window] by itself ([why]): it gets it if
     * [programMayTakeFocus] says so, else the window is marked urgent. Nothing while [locked].
     */
    fun focusRequested(window: Window, why: WindowRequest) = windowRoles.focusRequested(window, why)

    // Minimizing a scratchpad window hides its scratchpad.
    override fun minimizeRequested(window: Window) = windowRoles.minimizeRequested(window)

    /** Makes [window] fill the desktop (or brings it back to its earlier place and size). */
    fun setMaximized(window: Window, maximized: Boolean) = windowRoles.setMaximized(window, maximized)

    /** Shows [window] on the whole screen, above everything (or back as before); it gets the focus. */
    fun setFullscreen(window: Window, fullscreen: Boolean) = windowRoles.applyFullscreen(window, fullscreen, takeFocus = true)

    /** Hides [window]; it comes back with [focus] or [cycleFocus] (Alt+Tab). */
    fun minimize(window: Window) = windowRoles.minimize(window)

    /**
     * Brings the window at the bottom of the stack to the front; pressed repeatedly, it
     * goes through all windows, minimized ones included (they come back).
     */
    fun cycleFocus() = focuser.cycleFocus()

    /**
     * Focuses the nearest window in a direction ([dx], [dy] = -1, 0 or 1), e.g. the column
     * to the right in a scrolling layout. Scrolling and canvas layouts then bring it into view.
     */
    fun focusDirection(dx: Int, dy: Int) = focuser.focusDirection(dx, dy)

    /** Asks the focused window to close. */
    fun closeFocused() = focuser.closeFocused()

    /** The window under the desktop point ([x], [y]), if any. */
    fun windowAt(x: Double, y: Double): Window? = hits.surfaceAt(x, y)?.root?.roleObject as? Window

    /** The windows on screen bottom to top, as they are drawn (X11 windows are stacked the same way). */
    val stacking: List<Window> get() = sceneBuilder.stackingOrder()

    // --- moving and resizing with the pointer ---------------------------------------------

    // The window moved or resized with the pointer now, if any.
    internal val grab: DesktopPointerGrab.Grab? get() = pointerGrab.current

    // How many buttons are held now.
    internal var buttonsHeld = 0
        internal set

    override fun moveRequested(window: Window, serial: Int) = windowRoles.moveRequested(window, serial)

    override fun resizeRequested(window: Window, serial: Int, edges: Int) = windowRoles.resizeRequested(window, serial, edges)

    /**
     * An X11 program asked to move its window ([edges] null) or resize it (_NET_WM_MOVERESIZE):
     * as a Wayland program's request — only while the button the user holds was pressed in a
     * window of the same client (X has no serials), and your [WindowDirector] is asked first.
     */
    @dev.caulis.log.InternalCaulisApi
    fun x11DragRequested(window: Window, edges: Int?) = windowRoles.x11DragRequested(window, edges)

    /**
     * Moves [window] with the pointer until the button is released. Only while a button is
     * held (an app cannot grab the pointer on its own). A tiled window starts floating.
     */
    fun beginMove(window: Window) = pointerGrab.beginMove(window)

    /** Resizes [window] with the pointer at [edges] (XdgToplevel.ResizeEdge) until the button is released. */
    fun beginResize(window: Window, edges: Int) = pointerGrab.beginResize(window, edges)

    /** The resize edges nearest to the desktop point (for Super+right-drag). */
    fun nearestEdges(window: Window, x: Double, y: Double): Int = windowRoles.nearestEdges(window, x, y)

    // --- popups and focus ------------------------------------------------------------------

    /** Pointer position on the output. */
    var pointerX = width / 2.0
        internal set
    var pointerY = height / 2.0
        internal set

    // A menu stays on the monitor its window (or panel) is on.
    override fun popupBounds(popup: Popup): Rect? = popupHost.popupBounds(popup)

    override fun popupMapped(popup: Popup) = popupHost.popupMapped(popup)

    override fun popupUnmapped(popup: Popup) = popupHost.popupUnmapped(popup)

    override fun popupGrab(popup: Popup, serial: Int) = popupHost.popupGrab(popup, serial)

    /** Marks [window] as wanting attention (or not); bars and the IPC socket show it, the focus clears it. */
    fun setUrgent(window: Window, urgent: Boolean) = windowRoles.setUrgent(window, urgent)

    /** The windows that want attention, oldest first. */
    val urgentWindows: List<Window> get() = allWindows.filter { it.urgent }

    /** The modal dialog [window] waits for (the newest, a dialog's own dialog first), or null. */
    fun modalDialogOf(window: Window): Window? = windowRoles.modalDialogOf(window)

    /**
     * Raises [window], marks it active and gives it the keyboard (null = nothing focused).
     * A minimized window comes back; a window on another workspace brings that workspace.
     */
    fun focus(window: Window?) = focuser.focus(window)

    // --- input ------------------------------------------------------------------------

    /**
     * How the mouse crosses between monitors of different sizes: only where they touch
     * ([MonitorEdges.TOUCHING], as Windows), or edge to whole edge ([MonitorEdges.PROPORTIONAL]).
     */
    var monitorEdges = MonitorEdges.TOUCHING

    /**
     * The mouse or touchpad moved by ([dx], [dy]) desktop units: across monitors as
     * [monitorEdges] says. (A tablet or a nested window gives places instead: [pointerMoved].)
     */
    fun pointerMovedBy(timeMillis: Int, dx: Double, dy: Double, rawDx: Double = dx, rawDy: Double = dy) = pointerInput.pointerMovedBy(timeMillis, dx, dy, rawDx, rawDy)

    /** Someone moved the pointer (for idle timers; keys and clicks are told by whoever handles them). */
    var onUserActivity: () -> Unit = {}

    /** Pointer constraints and relative motion (games); null: none. */
    var pointerConstraints: dev.caulis.core.input.PointerConstraints? = null

    // --- touch screens -----------------------------------------------------------------------------

    /** A touch screen is there (programs are told they may get touches). */
    var touchScreen: Boolean
        get() = seat.touchScreen
        set(value) {
            if (value != seat.touchScreen) inputLog.info { if (value) "A touch screen is there" else "No touch screen any more" }
            seat.touchScreen = value
        }

    /**
     * A finger [id] touches the screen at ([x], [y]) (desktop units). On a program's window
     * that listens to touches it is a touch (the window gets the focus); elsewhere — the
     * compositor's bars and menus, programs without touch, the lock screen — it is the pointer
     * pressing the left button.
     */
    fun touchDown(timeMillis: Int, id: Int, x: Double, y: Double) = touch.down(timeMillis, id, x, y)

    /** Finger [id] moved to ([x], [y]). */
    fun touchMotion(timeMillis: Int, id: Int, x: Double, y: Double) = touch.motion(timeMillis, id, x, y)

    /** Finger [id] lifted. */
    fun touchUp(timeMillis: Int, id: Int) = touch.up(timeMillis, id)

    /** The end of a group of touch events (fingers that moved together). */
    fun touchFrame() = seat.touchFrame()

    /** The touches are cancelled (the device went away): programs forget them, the pointer's button is let go. */
    fun touchCancel(timeMillis: Int) = touch.cancel(timeMillis)

    // --- drawing tablets -------------------------------------------------------------------------

    /**
     * tablet-v2, for programs that understand pens (null: none — then a pen is always the
     * pointer). Set by whoever made it, as [pointerConstraints].
     */
    var tablets: Tablets? = null

    /**
     * Where a pen that is the pointer sends its movement and its clicks: to [pointerMoved] and
     * [pointerButton] by default. A window manager puts its own way in between, so its
     * bindings and hooks see the pen as they see a mouse.
     */
    var penPointer: PenPointer = object : PenPointer {
        override fun moved(timeMillis: Int, x: Double, y: Double) = pointerMoved(timeMillis, x, y)
        override fun button(timeMillis: Int, button: Int, pressed: Boolean) = pointerButton(timeMillis, button, pressed)
    }

    /**
     * A [tool] of [tablet] came near it ([near]) at ([x], [y]) (desktop units) with its [axes],
     * or went away. Over a program that takes tablet events ([tablets]) that program gets the
     * pen — pressure, tilt and all; elsewhere (our bars, menus and title bars, an overlay, the
     * overview, a program that does not, a [Tablet] set to [dev.caulis.core.input.DeviceSettings.pointerOnly])
     * the pen is the pointer: its tip the left button, its buttons as its settings say.
     */
    fun tabletProximity(timeMillis: Int, tablet: Tablet, tool: TabletTool, x: Double, y: Double, axes: TabletAxes, near: Boolean) =
        pens.proximity(timeMillis, tablet, tool, x, y, axes, near)

    /** [tool] (near a tablet: [tabletProximity]) moved to ([x], [y]) (desktop units), and its [axes] changed. */
    fun tabletMotion(timeMillis: Int, tool: TabletTool, x: Double, y: Double, axes: TabletAxes) = pens.motion(timeMillis, tool, x, y, axes)

    /** [tool]'s tip touched the tablet ([down]) or lifted off it. */
    fun tabletTip(timeMillis: Int, tool: TabletTool, down: Boolean) = pens.tip(timeMillis, tool, down)

    /** A [button] of [tool] (a Linux code: 0x14b the pen's lower one, 0x14c the upper one) was pressed or let go. */
    fun tabletButton(timeMillis: Int, tool: TabletTool, button: Int, pressed: Boolean) = pens.button(timeMillis, tool, button, pressed)

    fun pointerMoved(timeMillis: Int, x: Double, y: Double) = pointerInput.pointerMoved(timeMillis, x, y)

    /** The window under the pointer gets the keyboard as soon as the pointer comes onto it. */
    var focusFollowsMouse = false

    /**
     * A cursor on a monitor's cursor plane moves the moment the pointer does (on its own,
     * before anything else the move causes) — not with the next frame: it stays smooth while
     * a frame or a widget is slow. Off: it moves with the frames.
     */
    var quickCursorMoves = true

    /**
     * Threads a widget may draw its pixels on (Compose widgets: composed on the compositor's
     * thread, their recorded picture drawn into pixels on one of these and shown a moment
     * later). 0: everything on the compositor's thread.
     */
    @Volatile
    var widgetThreads = 2
        set(value) {
            require(value in 0..16) { "widgetThreads is 0 to 16, not $value" }
            field = value
        }

    /**
     * When the pointer last moved (System.nanoTime; 0: not yet): work that may wait (drawing
     * ahead, tidying up) can wait until the user rests, so the pointer never stutters for it.
     * (May be read from any thread.)
     */
    @Volatile
    var lastPointerMoveNanos = 0L
        internal set

    /**
     * A mouse button changed. With [passToApps] false it only counts as held (for a move or
     * resize the window manager starts itself, e.g. Super+drag) and no program sees it.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean, passToApps: Boolean = true) = pointerInput.pointerButton(timeMillis, button, pressed, passToApps)

    /**
     * The window that gets the pointer now: the one under it, or — while buttons are held —
     * the one they were pressed in (null: none, a panel, or our own title bar).
     */
    val pointerWindow: Window? get() = seat.pointerFocus?.let(capturing::ownerOf) as? Window

    /**
     * Scrolling as 0.1.1 had it: [value] units along one axis, [value120] the wheel's steps in
     * 1/120 (a full notch = 120; 0: fingers).
     */
    @Deprecated("Give a Scroll (where it came from, notches in 1/120)", ReplaceWith("pointerAxis(timeMillis, Scroll(...))"))
    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) = pointerInput.pointerAxis(timeMillis, vertical, value, value120)

    /** Scrolling (the wheel or fingers, see [Scroll]): an overlay, a widget or the program under the pointer gets it. */
    fun pointerAxis(timeMillis: Int, scroll: Scroll) = pointerInput.pointerAxis(timeMillis, scroll)

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) = keys.key(timeMillis, evdevCode, pressed)

    /** What is under a point: the surface, the point inside it, and the window/popup surface it belongs to. */
    class Hit @JvmOverloads constructor(
        val surface: Surface,
        val localX: Double,
        val localY: Double,
        val root: Surface,
        /** How much bigger than its own size the surface is drawn (a director's stretched window), across and down. */
        val scaleX: Double = 1.0,
        val scaleY: Double = 1.0,
    )

    // What the pointer at ([x], [y]) is over: a program's surface or (with [widgetsToo]) one of
    // our widgets — topmost first, in the order they are drawn.
    internal sealed interface Pointed {
        class AtSurface(val hit: Hit) : Pointed
        class AtWidget(val widget: Widget, val area: Rect, val level: DrawingLevel) : Pointed
    }

    // --- drawing ----------------------------------------------------------------------

    /**
     * One refresh of a screen: draws a frame with [present] if something changed on [view]
     * (or if [force] is set, e.g. while the monitor is being set up), and tells programs
     * that it is time for their next frame. Returns true if a frame was drawn.
     *
     * [present] is the backend's: `monitor::present` or `window::present`.
     */
    fun refresh(
        view: DesktopView,
        present: ((GpuTarget, Int) -> Unit) -> Unit,
        force: Boolean = false,
        /**
         * The backend's way to show a program's buffer directly (e.g. `monitor::presentDirect`),
         * or null. Used for a fullscreen window alone on screen; returns false if not possible.
         */
        presentDirect: ((DmabufBuffer) -> Boolean)? = null,
    ): Boolean = frames.refresh(view, present, force, presentDirect)

    /**
     * The frame last drawn for [view] reached the screen at [nanos] (monotonic clock).
     * Programs that asked are told (presentation-time).
     */
    fun presented(view: DesktopView, nanos: Long, refreshNanos: Long, sequence: Long, flags: Int) = frames.presented(view, nanos, refreshNanos, sequence, flags)

    /**
     * True if [view] needs a new frame: compares what would be drawn now with what it
     * showed last time (windows that moved, opened, closed, the cursor, ...).
     */
    fun needsRedraw(view: DesktopView): Boolean = frames.needsRedraw(view)

    // The cursor on a cursor plane moved with the pointer at once — before what the move does
    // otherwise (what is under it, hovering, the next frame): a busy frame or a slow widget
    // does not hold the cursor up. Only its place, the same picture, worked out as
    // [placeHardwareCursor] does it; anything else (another shape, onto another monitor)
    // waits for the next frame, which places it as usual.
    /**
     * The cursor plane moved straight from the input thread (with InputDevices.ownThread), as
     * the pointer moves, even while this thread is busy — only its place, the same picture.
     * Off: only from this thread ([quickCursorMoves]). (Caulis turns it off while the
     * configuration's onPointerMotion handlers keep the pointer where it is — a gesture — and
     * on again when they let it move.)
     */
    @Volatile
    var earlyCursorMoves = true

    /**
     * Told of the pointer's movement on the input thread before this thread handles it (see
     * [dev.caulis.core.input.EarlyPointer]): moves the cursor plane at once, where the pointer
     * will be — worked out as [pointerMovedBy] does it (monitors touching), from where it was
     * when this thread last caught up. Give it to InputDevices.earlyPointer.
     */
    val earlyPointer: dev.caulis.core.input.EarlyPointer = planes.Early()

    /**
     * Draws the changed parts into [target], whose picture is [age] frames old (1 = the
     * previous frame; 0 = unknown, then everything is drawn).
     */
    fun render(view: DesktopView, target: GpuTarget, age: Int) = frames.render(view, target, age)

    /**
     * Every frame drawn whole (not only what changed, the background under opaque windows too):
     * slower, for finding out whether something flickers because of the partial redraws
     * (CAULIS_FULL_REDRAW=1 sets it too).
     */
    var fullRedraw = System.getenv("CAULIS_FULL_REDRAW") == "1"
        set(value) {
            field = value
            renderLog.info { if (value) "Every frame drawn whole" else "Only what changed drawn" }
            changed()
        }

    // --- the cursor ------------------------------------------------------------------------

    /**
     * The cursor theme and size, e.g. `CursorTheme.load("Adwaita", 32)`. By default
     * a theme that came with a library, else `XCURSOR_THEME`, else
     * Caulis's own sharp cursors.
     */
    var cursorTheme: CursorTheme = CursorTheme.load()
        set(value) {
            field = value
            for (texture in cursors.cursorTextures.values) texture.close()
            cursors.cursorTextures.clear()
            cursors.cursorChanged()
        }

    /** Hide the cursor while typing (it comes back when the mouse moves). */
    var hideCursorWhileTyping = false

    /** Hide the cursor when the mouse has not moved for this long, in milliseconds (0 = never). */
    var hideCursorAfterMillis = 0L
        set(value) {
            field = value
            cursors.cursorActive()
        }

    /**
     * The cursor shown now, by name ("default", "text", "grabbing", ...), or null while the
     * program under the pointer shows a picture of its own (or none).
     */
    val cursorShape: String? get() = cursors.cursorShapeNow()

    // --- programs nobody sees -------------------------------------------------------------

    /**
     * Keeps [surface] and its subsurfaces running at full speed even while nobody sees it,
     * for example while it is shared in a screen recording. Close the returned handle when
     * that ends. Without it, hidden programs only get a slow heartbeat (see [frameShown]).
     */
    fun keepUpdating(surface: Surface): AutoCloseable = frames.keepUpdating(surface)

    /**
     * Tells every program on screen (and those kept updating) that its last frame was
     * shown, so it can draw the next one. Called at every refresh, also when nothing had
     * to be redrawn. Hidden programs get a slow heartbeat instead: they do not freeze, but
     * they do not burn the computer's power on pictures nobody sees either. A hidden window
     * just asked for a new size is the exception: it draws that at once (a window manager
     * waits for it before showing the window, as newm does).
     */
    fun frameShown(timeMillis: Int) = frames.frameShown(null, timeMillis)

}
