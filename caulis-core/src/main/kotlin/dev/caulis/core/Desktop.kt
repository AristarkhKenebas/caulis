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
import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.render.Color
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Layout
import dev.caulis.tiling.TilingCommand
import java.util.TreeMap
import kotlin.math.roundToInt

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
    private val pens = DesktopPens(this, seat)
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
        if (renderLog.level >= 2) busy.moving(WALLPAPER_SHADERS)
        version++
        onChange()
    }

    // The backgrounds of the monitors (declared first: drawing asks for them).
    private val backdrops = Backdrops(renderer, compositor.display.loop, changed = {
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
        private set

    // Stands in until the backend adds the first real monitor (and in tests): the size given.
    private var placeholder: Monitor? = null

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
    fun setBackgroundView(monitor: Monitor, view: BackgroundView?) {
        if (backdrops.views[monitor.name] == view) return
        if (view == null) backdrops.views -= monitor.name else backdrops.views[monitor.name] = view
        changed()
    }

    /** Gives [monitor] the scale [value] (the picture melts into the new one). */
    fun setScale(monitor: Monitor, value: Double) {
        require(value in 0.5..4.0) { "a scale must be between 0.5 and 4, not $value" }
        if (value == monitor.scale) return
        layoutLog.info { "Monitor ${monitor.name}: scale ${monitor.scale} → $value" }
        scaleFade.fadeFromNow(monitor) // the old picture melts into the new one instead of jumping
        monitor.scale = value
        monitor.output?.rescale(value)
        if (monitor === focusedMonitor) compositor.outputScale = value
        placeMonitors() // its size in units changed: the ones beside it move
        for (each in monitorList) monitorChanged(each)
        cursors.cursorChanged()
    }

    /**
     * A monitor came: it shows a workspace of its own from now on. [x], [y] (desktop units)
     * place it; without them it goes to the right of the others. [scale] is its own, or the
     * desktop's ([Desktop.scale]).
     */
    fun addMonitor(name: String, output: Output?, pixelWidth: Int, pixelHeight: Int, scale: Double = defaultScale, x: Int? = null, y: Int? = null): Monitor {
        val monitor = Monitor(name, output, pixelWidth, pixelHeight, scale)
        if (x != null && y != null) monitor.fixedPosition = x to y
        val standIn = placeholder
        if (standIn != null) {
            // The first real monitor takes over from the stand-in: its workspace and views.
            placeholder = null
            monitorList -= standIn
            monitor.workspace = standIn.workspace
            for (view in views + capturing.captureViews) if (view.monitor === standIn) view.monitor = monitor
            for (workspace in workspaces.values) if (workspace.monitor === standIn) workspace.monitor = monitor
            if (workspaceMode == WorkspaceMode.SPANNING || workspaceMode == WorkspaceMode.SEPARATE) {
                // Workspaces of their own on this monitor from now on (the stand-in's windows go along).
                monitorList += monitor
                focusedMonitor = monitor
                mergeWorkspaces(of = standIn, into = monitor)
                monitorList -= monitor
                monitor.workspace = workspace(monitor.workspace.number, monitor)
            }
        } else {
            monitor.workspace = workspaceForNewMonitor(monitor)
        }
        monitor.workspace.monitor = monitor
        monitorList += monitor
        if (standIn != null) focusedMonitor = monitor
        // The primary monitor gets the focus when it comes before anything is open.
        if (monitor.name == primaryMonitorName && allWindows.isEmpty()) focusedMonitor = monitor
        output?.rescale(scale)
        arrangePrimary()
        ensurePersistent()
        if (workspaceMode == WorkspaceMode.PINNED) for (each in monitorList) if (each.workspace.number > 0) pinnedHomes.putIfAbsent(each.workspace.number, each.name)
        layoutLog.info { "Monitor ${monitor.name}: ${pixelWidth}x$pixelHeight, scale $scale, workspace ${monitor.workspace.number}" }
        placeMonitors()
        for (each in monitorList) monitorChanged(each)
        return monitor
    }

    /** A monitor went away: its workspaces stay (Super+N brings them back on another monitor). */
    fun removeMonitor(monitor: Monitor) {
        if (monitor !in monitorList) return
        scaleFade.dropFade(monitor)
        backdrops.forget(monitor.name)
        if (monitorList.size == 1) {
            // The last one: it stays as the stand-in, so windows keep a place until a monitor comes.
            placeholder = monitor
            return
        }
        monitorList -= monitor
        val home = if (focusedMonitor === monitor) monitorList.first() else focusedMonitor
        layoutLog.info { "Monitor ${monitor.name} is gone; its windows stay on their workspaces (now on ${home.name})" }
        for (view in views + capturing.captureViews) if (view.monitor === monitor) view.monitor = home
        // Scratchpads shown on it come over to [home]; a special workspace only if none is shown
        // there (one a monitor: else two dimmings and two sets of windows on top of each other).
        for (pad in pads.shownPads.toList()) if (pad.monitor === monitor && !pad.leaving) {
            if (pad.isSpecial && pads.shownPads.any { it !== pad && it.isSpecial && !it.leaving && it.monitor === home }) {
                layoutLog.info { "Special workspace ${pad.name} hidden: its monitor is gone and ${home.name} shows another one" }
                pads.hideScratchpad(pad, focusBack = false)
            } else {
                pad.monitor = home
            }
        }
        when (workspaceMode) {
            WorkspaceMode.PER_MONITOR, WorkspaceMode.PINNED -> for (workspace in workspaces.values.toList()) if (workspace.monitor === monitor) moveWorkspace(workspace, home)
            WorkspaceMode.SPANNING, WorkspaceMode.SEPARATE -> mergeWorkspaces(of = monitor, into = home)
            WorkspaceMode.PRIMARY_ONLY -> {
                val shown = monitor.workspace
                val primary = primaryMonitor
                if (shown.number == 0) {
                    // A screen of its own: its windows go to what the primary monitor shows.
                    mergeInto(shown, primary.workspace)
                } else {
                    // The primary monitor went: the next one shows its workspace (its own screen's windows join).
                    val own = primary.workspace
                    primary.workspace = shown
                    moveWorkspace(shown, primary)
                    if (own.number == 0) mergeInto(own, shown)
                    arranger.arrange(shown)
                }
            }
        }
        // Panels on it close (programs like Waybar open new ones for the monitors there are).
        for (layer in layerHost.layerSurfaces.filter { monitorOf(it) === monitor }.toList()) {
            layer.close()
            layerHost.layerMonitors -= layer
        }
        if (focusedMonitor === monitor) {
            focusedMonitor = home
            compositor.outputScale = home.scale
            focus(home.workspace.windows.lastOrNull())
        }
        placeMonitors()
        for (each in monitorList) monitorChanged(each)
    }

    /**
     * Turns [monitor] ([Transform.ROTATE_90] for a screen standing on its side): its size and
     * what programs are told follow. The backend draws it turned (if it can, see [MonitorControl.canTransform]).
     */
    fun setTransform(monitor: Monitor, transform: Transform) {
        if (transform == monitor.transform) return
        val swap = transform.sideways != monitor.transform.sideways
        monitor.transform = transform
        monitor.output?.setTransform(transform)
        layoutLog.info { "Monitor ${monitor.name} turned: $transform" }
        if (swap) resizeMonitor(monitor, monitor.pixelHeight, monitor.pixelWidth) else for (each in monitorList) monitorChanged(each)
        changed()
    }

    /** Switches [monitor]'s screen off (false) or on again (DPMS); false if the backend cannot. */
    fun setPower(monitor: Monitor, on: Boolean): Boolean {
        if (monitor.powered == on) return true
        val done = monitor.control?.setPower(on) ?: false
        if (done) {
            monitor.powered = on
            layoutLog.info { "Monitor ${monitor.name} ${if (on) "on" else "off"}" }
            if (on) for (view in views) if (view.monitor === monitor) view.damageEverything()
            changed()
            events.powerListeners.toList().forEach { it(monitor) }
        }
        return done
    }

    /** Runs [listener] when a monitor's screen is switched off or on. */
    fun onPowerChanged(listener: (Monitor) -> Unit): AutoCloseable = events.onPowerChanged(listener)

    /** Puts [monitor] at [x], [y] on the desktop (units); null: to the right of the others. */
    fun moveMonitor(monitor: Monitor, x: Int?, y: Int?) {
        val place = if (x != null && y != null) x to y else null
        if (place == monitor.fixedPosition) return // (windows are not laid out again for nothing)
        monitor.fixedPosition = place
        placeMonitors()
        for (each in monitorList) monitorChanged(each)
    }

    /** [monitor] is now [pixelWidth] x [pixelHeight] pixels (a new mode, the nested window resized). */
    fun resizeMonitor(monitor: Monitor, pixelWidth: Int, pixelHeight: Int) {
        if (pixelWidth == monitor.pixelWidth && pixelHeight == monitor.pixelHeight) return
        monitor.pixelWidth = pixelWidth
        monitor.pixelHeight = pixelHeight
        monitor.output?.resize(pixelWidth, pixelHeight)
        placeMonitors()
        for (each in monitorList) monitorChanged(each)
    }

    // Monitors with a position of their own keep it; the others follow in a row to the right.
    private fun placeMonitors() {
        var nextX = monitorList.filter { it.fixedPosition != null }.maxOfOrNull { it.fixedPosition!!.first + it.units(it.pixelWidth) } ?: 0
        for (monitor in monitorList) {
            val (x, y) = monitor.fixedPosition ?: (nextX to 0).also { nextX += monitor.units(monitor.pixelWidth) }
            monitor.area = Rect(x, y, monitor.units(monitor.pixelWidth), monitor.units(monitor.pixelHeight))
            monitor.output?.position(x, y)
        }
    }

    // A monitor's size, place or scale changed: its panels, windows and views follow.
    private fun monitorChanged(monitor: Monitor) {
        layerHost.arrangeLayers(monitor, refit = false)
        refitWindows(monitor)
        for (view in views + capturing.captureViews) if (view.monitor === monitor) view.damageEverything()
    }

    // Every surface shown learns the monitors it is on and draws at the largest of their scales.
    private fun placeSurfaces(items: List<DrawItem>) {
        if (monitorList.size == 1 && placeholder != null) return // no real monitor yet: the default holds
        for (item in items) {
            if (item !is DrawItem.Picture) continue
            val on = monitorList.filter { it.area.intersect(item.rect) != null }
            if (on.isEmpty()) continue
            item.surface.placeOn(on.mapNotNull { it.output }, on.maxOf { it.scale })
        }
    }

    // All monitors together: the box around them.
    internal fun desktopBounds(): Rect = monitorList.map { it.area }.reduce(Rect::union)

    // The monitor a lock surface covers (its output's, else the first).
    internal fun lockMonitor(surface: LockSurface): Monitor =
        surface.output?.let { output -> monitorList.firstOrNull { it.output === output } } ?: monitorList.first()

    /** The size in pixels of the monitor with the focus. */
    val pixelWidth: Int get() = focusedMonitor.pixelWidth
    val pixelHeight: Int get() = focusedMonitor.pixelHeight

    /** The first monitor is now [pixelWidth] x [pixelHeight] pixels (with one monitor: the screen). */
    fun resizeScreen(pixelWidth: Int, pixelHeight: Int) = resizeMonitor(monitorList.first(), pixelWidth, pixelHeight)

    /** A rectangle in desktop units as pixels of the monitor it is on (edges rounded the same way everywhere). */
    fun toPixels(rect: Rect): Rect = monitorAt(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0).toPixels(rect)

    /** The monitor at a desktop point, or the nearest one. */
    fun monitorAt(x: Double, y: Double): Monitor =
        monitorList.firstOrNull { it.contains(x, y) } ?: monitorList.minBy { distance(it.area, x, y) }

    private fun distance(area: Rect, x: Double, y: Double): Double {
        val dx = maxOf(area.x - x, 0.0, x - (area.right - 1))
        val dy = maxOf(area.y - y, 0.0, y - (area.bottom - 1))
        return dx * dx + dy * dy
    }

    /** The monitor showing [output], if any. */
    fun monitorOf(output: Output): Monitor? = monitorList.firstOrNull { it.output === output }

    /**
     * The monitor a window is on: the one its workspace is shown on (or was last) — or, for
     * a window your [director] places, the one it is drawn on most (its workspace is only
     * where it opened: a menu, its scale and its size limits are of where you see it).
     */
    fun monitorOf(window: Window): Monitor {
        if (director != null) animator.screenRects[window]?.takeIf { directed(window) }?.let { rect -> return monitorAt(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0) }
        return workspaceOf(window)?.monitor ?: focusedMonitor
    }

    /** Size of the monitor with the focus, in desktop units. */
    val width: Int get() = focusedMonitor.area.width
    val height: Int get() = focusedMonitor.area.height

    // A monitor changed size: maximized and fullscreen windows on it follow, layouts re-arrange.
    internal fun refitWindows(monitor: Monitor) {
        lockHost.lock?.surfaces?.filter { lockMonitor(it) === monitor }?.forEach { it.configure(monitor.area.width, monitor.area.height) }
        for (pad in pads.shownPads) if (pad.monitor === monitor) pads.placeScratchpad(pad, appearing = emptySet())
        for (workspace in workspaces.values) {
            if ((workspace.monitor ?: focusedMonitor) !== monitor) continue
            for (window in workspace.windows + workspace.minimized) {
                if (!window.configured) continue
                if (window.fullscreen) {
                    window.configure(monitor.area.width, monitor.area.height)
                    animator.moveSmoothly(window, monitor.area.x, monitor.area.y)
                } else if (window.maximized) {
                    val area = withoutTitle(window, monitor.usableArea)
                    window.configure(area.width, area.height)
                    animator.moveSmoothly(window, area.x, area.y)
                }
            }
            arranger.arrange(workspace)
        }
        changed()
    }

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
            surface.onCommit { surfaceCommitted(surface) }
            surface.resource.onDestroy { keptUpdating.remove(surface) }
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
            for (workspace in workspaces.values) arranger.arrange(workspace)
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
        get() = workspaces.values.flatMap { it.windows + it.minimized } + pads.allPads.flatMap { it.windows }

    /** True if [window] is minimized (on any workspace). */
    fun isMinimized(window: Window): Boolean = workspaces.values.any { window in it.minimized }

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
    private val BUSY_REPORT_NANOS = 10_000_000_000L
    private val WALLPAPER_SHADERS = listOf("wallpaper shader")
    private val overlayLog = Logger("overlay")

    // --- workspaces -----------------------------------------------------------------------

    /**
     * The layout new workspaces start with. Setting it changes every workspace at once;
     * windows move to their new places right away (see [setLayout] for one workspace).
     */
    var layout: Layout = Floating
        set(value) {
            field = value
            for (workspace in workspaces.values) if (workspace.number > 0 && ruleOf(workspace.number)?.layout == null) setLayout(workspace, value)
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
            arrangePrimary()
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
            applyWorkspaceRules()
        }

    private fun ruleOf(number: Int): WorkspaceRule? = workspaceRules.lastOrNull { it.number == number }

    /** The name of workspace [number] from its rule, or null. */
    fun workspaceName(number: Int): String? = ruleOf(number)?.name

    /** The workspace shown before the one with the focus now (for "back and forth"), or null. */
    var previousWorkspaceNumber: Int? = null
        private set

    // By number; with workspaces of each monitor's own, by number and monitor.
    internal data class WorkspaceKey(val number: Int, val monitor: String?)

    private fun keyFor(number: Int, monitor: Monitor): WorkspaceKey = when {
        number == 0 -> WorkspaceKey(0, monitor.name)
        workspaceMode == WorkspaceMode.SPANNING || workspaceMode == WorkspaceMode.SEPARATE -> WorkspaceKey(number, monitor.name)
        else -> WorkspaceKey(number, null)
    }

    internal val workspaces = java.util.TreeMap<WorkspaceKey, Workspace>(compareBy<WorkspaceKey> { it.number }.thenBy { it.monitor ?: "" })

    /** The workspace on the monitor with the focus. */
    val activeWorkspace: Workspace get() = focusedMonitor.workspace

    /** The number of the workspace [window] is on (null: in a scratchpad, or not open). */
    fun workspaceNumberOf(window: Window): Int? = if (scratchpadOf(window) != null) null else workspaceOf(window)?.number

    init {
        // Until the backend adds monitors: one of the size given (tests, the very first frame).
        val standIn = Monitor("default", null, screenWidth, screenHeight, 1.0)
        val first = Workspace(1, layout).also { it.monitor = standIn; it.leftOut = ::directed }
        workspaces[WorkspaceKey(1, null)] = first
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
    val allWorkspaces: Collection<Workspace> get() = workspaces.values

    // The workspace with [number] (of [monitor] when monitors have their own), created when first needed.
    internal fun workspace(number: Int, monitor: Monitor = focusedMonitor): Workspace {
        val key = keyFor(number, monitor)
        return workspaces.getOrPut(key) {
            val rule = if (number > 0) ruleOf(number) else null
            Workspace(number, rule?.layout ?: layout, key.monitor).also {
                it.leftOut = ::directed
                it.monitor = monitor
                it.name = rule?.name
            }
        }
    }

    // No real monitor yet: only the stand-in.
    private fun onlyStandIn() = placeholder != null && monitorList.singleOrNull() === placeholder

    // Pinned workspaces: the monitor each was first shown on (kept while it is gone empty).
    private val pinnedHomes = HashMap<Int, String>()

    // Where workspace [number] belongs: its rule's monitor (if plugged in); pinned, also where it was first shown.
    internal fun homeOf(number: Int): Monitor? {
        ruleOf(number)?.monitor?.let { name -> monitorList.firstOrNull { it.name == name }?.let { return it } }
        if (workspaceMode == WorkspaceMode.PINNED) {
            pinnedHomes[number]?.let { name -> monitorList.firstOrNull { it.name == name }?.let { return it } }
        }
        return null
    }

    // A new monitor shows: its own workspace from before (plugged in again), or the first free number.
    private fun workspaceForNewMonitor(monitor: Monitor): Workspace {
        when (workspaceMode) {
            WorkspaceMode.SPANNING -> return workspace(focusedMonitor.workspace.number.coerceAtLeast(1), monitor)
            WorkspaceMode.SEPARATE -> return workspaces.values.firstOrNull { it.homeMonitor == monitor.name && it.lastMonitorName == monitor.name } ?: workspace(1, monitor)
            // (A screen of its own; if it is the primary one, arrangePrimary gives it the workspaces.)
            WorkspaceMode.PRIMARY_ONLY -> return workspace(0, monitor)
            WorkspaceMode.PER_MONITOR, WorkspaceMode.PINNED -> {}
        }
        val shown = monitorList.map { it.workspace }.toSet()
        // Workspaces whose home it is, then those it showed before it was unplugged.
        workspaceRules.firstOrNull { it.monitor == monitor.name && shown.none { s -> s.number == it.number } }?.let { return workspace(it.number, monitor) }
        workspaces.values.firstOrNull { it !in shown && !it.isEmpty && it.lastMonitorName == monitor.name }?.let { return it }
        val number = generateSequence(1) { it + 1 }.first { n ->
            n > workspaceCount || (shown.none { it.number == n } && workspaces[WorkspaceKey(n, null)]?.isEmpty != false && ruleOf(n)?.monitor.let { it == null || it == monitor.name })
        }
        return workspace(number, monitor)
    }

    // Workspaces of a monitor that went away (each monitor had its own): their windows join the same workspace on [into].
    private fun mergeWorkspaces(of: Monitor, into: Monitor) {
        for (workspace in workspaces.values.filter { it.homeMonitor == of.name || (it.homeMonitor == null && of === placeholder) }.toList()) {
            mergeInto(workspace, workspace(workspace.number, into))
        }
    }

    // The windows of [from] join [to]; [from] is gone.
    private fun mergeInto(from: Workspace, to: Workspace) {
        if (from === to) return
        for (window in from.windows.toList() + from.minimized.toList()) {
            val wasMinimized = window in from.minimized
            val wasFloating = window in from.floating
            from.remove(window)
            from.model.forget(window)
            if (wasMinimized) to.minimizedList += window else to.add(window)
            if (wasFloating) to.floating += window
        }
        workspaces.values.remove(from)
        arranger.arrange(to)
    }

    // PRIMARY_ONLY: the primary monitor shows the numbered workspaces, every other one a screen of its own.
    private fun arrangePrimary() {
        if (workspaceMode != WorkspaceMode.PRIMARY_ONLY || monitorList.isEmpty() || onlyStandIn()) return
        val primary = primaryMonitor
        if (primary.workspace.number == 0) {
            // The numbered workspace shown elsewhere (that monitor was primary) comes here; this one's windows join it.
            val own = primary.workspace
            val numbered = monitorList.map { it.workspace }.firstOrNull { it.number > 0 }
                ?: workspaces.values.firstOrNull { it.number > 0 && it.homeMonitor == null }
                ?: workspace(1, primary)
            monitorList.firstOrNull { it.workspace === numbered }?.let { other ->
                // (The focus goes along with the workspaces.)
                if (focusedMonitor === other) setFocusedMonitor(primary)
                other.workspace = workspace(0, other)
                moveWorkspace(other.workspace, other)
                arranger.arrange(other.workspace)
            }
            primary.workspace = numbered
            moveWorkspace(numbered, primary)
            numbered.lastMonitorName = primary.name
            mergeInto(own, numbered)
            arranger.arrange(numbered)
        }
        for (monitor in monitorList) {
            if (monitor !== primary && monitor.workspace.number > 0) {
                monitor.workspace = workspace(0, monitor)
                moveWorkspace(monitor.workspace, monitor)
                arranger.arrange(monitor.workspace)
            }
        }
        changed()
    }

    // Workspaces that always exist are there; names and layouts of rules apply to those there are.
    private fun applyWorkspaceRules() {
        for (workspace in workspaces.values.toList()) {
            if (workspace.number == 0) continue
            val rule = ruleOf(workspace.number)
            workspace.name = rule?.name
            val wanted = rule?.layout ?: layout
            // (A layout of the same kind keeps its arrangement.)
            if (wanted !== workspace.layout && (wanted.javaClass != workspace.layout.javaClass || wanted.name != workspace.layout.name)) setLayout(workspace, wanted)
        }
        ensurePersistent()
    }

    private fun ensurePersistent() {
        if (onlyStandIn()) return
        for (rule in workspaceRules.filter { it.persistent && it.number <= workspaceCount }) {
            when (workspaceMode) {
                WorkspaceMode.SPANNING, WorkspaceMode.SEPARATE -> for (monitor in monitorList) workspace(rule.number, monitor)
                WorkspaceMode.PRIMARY_ONLY -> workspace(rule.number, primaryMonitor)
                else -> workspace(rule.number, homeOf(rule.number) ?: primaryMonitor)
            }
        }
    }

    /**
     * Numbers of the workspaces there are (shown, with windows, or persistent) that Super+N
     * reaches from the monitor with the focus, in order.
     */
    fun workspaceNumbers(): List<Int> {
        val own = workspaceMode == WorkspaceMode.SPANNING || workspaceMode == WorkspaceMode.SEPARATE
        return workspaces.values.filter { it.number > 0 && (!own || it.homeMonitor == focusedMonitor.name) }.map { it.number }.distinct().sorted()
    }

    /** The workspace [steps] after (or before, negative) the active one among those there are, going round. */
    fun workspaceAfter(steps: Int): Int {
        val numbers = (workspaceNumbers() + activeWorkspace.number).filter { it > 0 }.distinct().sorted()
        if (numbers.isEmpty()) return 1
        val at = numbers.indexOf(activeWorkspace.number).coerceAtLeast(0)
        return numbers[Math.floorMod(at + steps, numbers.size)]
    }

    /** The lowest workspace number with no windows (null: all [workspaceCount] have some). */
    fun firstEmptyWorkspace(): Int? = (1..workspaceCount).firstOrNull { n ->
        val key = keyFor(n, if (workspaceMode == WorkspaceMode.PRIMARY_ONLY) primaryMonitor else focusedMonitor)
        workspaces[key]?.isEmpty != false
    }

    /** Windows on the active workspace, bottom to top. */
    val windows: List<Window> get() = activeWorkspace.windows

    /** Minimized windows of the active workspace. */
    val minimized: List<Window> get() = activeWorkspace.minimized

    /**
     * Shows workspace [number] (created if needed) on the monitor with the focus; its top
     * window gets the keyboard. If another monitor shows it, the focus goes there instead.
     * With spanning workspaces, every monitor switches.
     */
    fun switchTo(number: Int) {
        if (number !in 1..workspaceCount) {
            layoutLog.info { "There is no workspace $number (workspaceCount is $workspaceCount)" }
            return
        }
        val before = activeWorkspace.number
        pointerGrab.end()
        when (workspaceMode) {
            WorkspaceMode.SPANNING -> {
                if (number == before) return
                for (monitor in monitorList) show(workspace(number, monitor), on = monitor)
            }
            WorkspaceMode.SEPARATE -> {
                if (number == before) return
                show(workspace(number, focusedMonitor), on = focusedMonitor)
            }
            WorkspaceMode.PRIMARY_ONLY -> {
                val primary = primaryMonitor
                if (primary.workspace.number == number) {
                    if (focusedMonitor !== primary) focusMonitor(primary)
                    return
                }
                setFocusedMonitor(primary)
                show(workspace(number, primary), on = primary)
            }
            WorkspaceMode.PER_MONITOR, WorkspaceMode.PINNED -> {
                val home = homeOf(number)
                val target = workspace(number, home ?: focusedMonitor)
                val shownOn = monitorList.firstOrNull { it.workspace === target }
                if (shownOn != null) {
                    if (shownOn !== focusedMonitor) {
                        previousWorkspaceNumber = before
                        focusMonitor(shownOn)
                    }
                    return
                }
                val on = home ?: focusedMonitor
                setFocusedMonitor(on)
                show(target, on = on)
            }
        }
        if (before > 0 && before != activeWorkspace.number) previousWorkspaceNumber = before
        focus(arranger.topWindow(activeWorkspace))
        layoutLog.info { "Workspace ${activeWorkspace.number} (${activeWorkspace.layout.name})${if (monitorList.size > 1) " on ${focusedMonitor.name}" else ""}" }
        notifyEach(events.workspaceListeners, activeWorkspace, "workspace")
    }

    // [workspace] goes onto [monitor]: its floating windows keep their place relative to the
    // monitor's corner (and stay on it), maximized and fullscreen ones fit it; tiled ones are
    // arranged by whoever shows it.
    private fun moveWorkspace(workspace: Workspace, monitor: Monitor) {
        val from = workspace.monitor
        workspace.monitor = monitor
        if (from == null || from === monitor) return
        val dx = monitor.area.x - from.area.x
        val dy = monitor.area.y - from.area.y
        for (window in workspace.windows + workspace.minimized) {
            when {
                window.fullscreen || window.maximized -> if (window.configured) windowRoles.applyState(window)
                !window.tiled -> {
                    window.x = (window.x + dx).coerceIn(monitor.area.x, (monitor.area.right - 50).coerceAtLeast(monitor.area.x))
                    window.y = (window.y + dy).coerceIn(monitor.area.y, (monitor.area.bottom - 50).coerceAtLeast(monitor.area.y))
                    arranger.floatingRects[window]?.let { arranger.floatingRects[window] = Rect(window.x, window.y, it.width, it.height) }
                }
            }
        }
    }

    // An empty workspace nobody sees goes away (unless it always exists), as one left empty does.
    internal fun dropIfEmpty(workspace: Workspace) {
        if (workspace.isEmpty && workspace.number > 0 && ruleOf(workspace.number)?.persistent != true && monitorList.none { it.workspace === workspace }) {
            workspaces.values.remove(workspace)
        }
    }

    // [workspace] is shown on [on] now (the one shown there before is hidden, gone if empty).
    internal fun show(workspace: Workspace, on: Monitor) {
        val previous = on.workspace
        if (previous === workspace) return
        if (animator.pick("workspaces", animations.workspaceSwitch).millis > 0 && !locked && !inOverview) {
            val direction = if (workspace.number >= previous.number) 1 else -1
            animator.slides[on] = DesktopAnimations.Slide(previous, workspace, direction, System.nanoTime())
        }
        // Pinned windows go along (they float, keeping their place on the monitor).
        for (window in previous.windows.filter { it in arranger.pinned }) {
            previous.remove(window)
            previous.model.forget(window)
            workspace.add(window)
            workspace.floating += window
        }
        on.workspace = workspace
        moveWorkspace(workspace, on)
        popupHost.dismissHiddenGrabs("their workspace is not shown any more")
        workspace.lastMonitorName = on.name
        if (workspaceMode == WorkspaceMode.PINNED && workspace.number > 0 && !onlyStandIn()) pinnedHomes.putIfAbsent(workspace.number, on.name)
        // Empty workspaces disappear once you leave them (never one on screen), unless they always exist.
        dropIfEmpty(previous)
        arranger.arrange(workspace)
        changed()
    }

    /** Gives the focus to [monitor] (its top window gets the keyboard). */
    fun focusMonitor(monitor: Monitor) {
        if (monitor !in monitorList) return
        setFocusedMonitor(monitor)
        focus(arranger.topWindow(monitor.workspace))
    }

    /** The monitor next to the focused one in a direction ([dx], [dy] = -1, 0 or 1), if any. */
    fun monitorBeside(dx: Int, dy: Int, from: Monitor = focusedMonitor): Monitor? {
        val cx = from.area.x + from.area.width / 2.0
        val cy = from.area.y + from.area.height / 2.0
        return monitorList.filter { it !== from }.filter { other ->
            val ox = other.area.x + other.area.width / 2.0
            val oy = other.area.y + other.area.height / 2.0
            (dx > 0 && ox > cx) || (dx < 0 && ox < cx) || (dy > 0 && oy > cy) || (dy < 0 && oy < cy)
        }.minByOrNull { other ->
            val ox = other.area.x + other.area.width / 2.0 - cx
            val oy = other.area.y + other.area.height / 2.0 - cy
            ox * ox + oy * oy
        }
    }

    /** Moves [window] to the workspace shown on [monitor] (it keeps its place there if floating). */
    fun moveToMonitor(window: Window, monitor: Monitor) {
        val from = monitorOf(window)
        if (from === monitor) return
        // A floating window keeps its place relative to the monitor.
        if (!window.tiled) {
            window.x = monitor.area.x + (window.x - from.area.x).coerceIn(0, (monitor.area.width - 50).coerceAtLeast(0))
            window.y = monitor.area.y + (window.y - from.area.y).coerceIn(0, (monitor.area.height - 50).coerceAtLeast(0))
        }
        moveToWorkspace(window, monitor.workspace)
        if (window.fullscreen || window.maximized) windowRoles.applyState(window)
        focusMonitor(monitor)
        focus(window)
    }

    internal fun setFocusedMonitor(monitor: Monitor) {
        if (focusedMonitor === monitor) return
        focusedMonitor = monitor
        compositor.outputScale = monitor.scale
        focusLog.trace { "Monitor ${monitor.name}" }
    }

    /**
     * Moves [window] to workspace [number] (if floating, it keeps its place — on that
     * workspace's monitor when it has one of its own).
     */
    fun moveToWorkspace(window: Window, number: Int) {
        if (number !in 1..workspaceCount) return
        if (workspaceOf(window) == null) return // (a scratchpad's: no workspace is made for it)
        val monitor = when (workspaceMode) {
            WorkspaceMode.PRIMARY_ONLY -> primaryMonitor
            WorkspaceMode.SPANNING, WorkspaceMode.SEPARATE -> monitorOf(window)
            WorkspaceMode.PER_MONITOR, WorkspaceMode.PINNED -> homeOf(number) ?: monitorOf(window)
        }
        moveToWorkspace(window, workspace(number, monitor))
    }

    internal fun moveToWorkspace(window: Window, to: Workspace) {
        val from = workspaceOf(window) ?: return
        if (from === to) return
        layoutLog.info { "$window moves from workspace ${from.number} to ${to.number}" }
        val wasFloating = window in from.floating
        // A floating window going to another monitor keeps its place relative to that one.
        val fromMonitor = from.monitor
        val toMonitor = to.monitor
        if (!window.tiled && fromMonitor != null && toMonitor != null && fromMonitor !== toMonitor) {
            window.x = toMonitor.area.x + (window.x - fromMonitor.area.x).coerceIn(0, (toMonitor.area.width - 50).coerceAtLeast(0))
            window.y = toMonitor.area.y + (window.y - fromMonitor.area.y).coerceIn(0, (toMonitor.area.height - 50).coerceAtLeast(0))
            arranger.floatingRects[window]?.let { arranger.floatingRects[window] = Rect(window.x, window.y, it.width, it.height) }
        }
        popupHost.dismissPopupsOf(window, "it moved to another workspace")
        from.remove(window)
        from.model.forget(window) // it lives on the other workspace now
        arranger.pending.remove(window)
        to.add(window)
        if (wasFloating) to.floating += window
        window.setActivated(false)
        arranger.arrange(from)
        arranger.arrange(to)
        if (from === activeWorkspace) focus(arranger.topWindow(from))
        dropIfEmpty(from)
        changed()
    }

    /** Uses [layout] on [workspace]; windows move to their new places right away. */
    fun setLayout(workspace: Workspace, layout: Layout) {
        layoutLog.info { "Workspace ${workspace.number}: layout ${workspace.layout.name} → ${layout.name}" }
        val wasTiled = workspace.tiledWindows().toSet()
        workspace.layout = layout
        // Windows leaving the layout get their own floating place back.
        for (window in wasTiled - workspace.tiledWindows().toSet()) arranger.untile(window)
        arranger.arrange(workspace)
        changed()
    }

    internal fun workspaceOf(window: Window): Workspace? =
        workspaces.values.firstOrNull { window in it.windows || window in it.minimized }

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

    internal val unmanaged = LinkedHashMap<Surface, Pair<Int, Int>>()

    /**
     * Shows [surface] at [x], [y] (desktop units) above the windows, as it places itself —
     * an X11 menu or tooltip ("override-redirect" window). Again to move it.
     */
    fun showUnmanaged(surface: Surface, x: Int, y: Int) {
        if (!surface.resource.isAlive) return
        if (unmanaged.remove(surface) == null) surface.resource.onDestroy { hideUnmanaged(surface) }
        unmanaged[surface] = x to y // last shown on top
        changed()
    }

    fun hideUnmanaged(surface: Surface) {
        if (unmanaged.remove(surface) == null) return
        if (seat.keyboardFocus === surface) focusUnmanaged(null)
        changed()
    }

    /**
     * Gives the keyboard to [surface], shown with [showUnmanaged] — an X11 launcher, game or
     * menu that grabbed the keyboard; null gives it back to the focused window.
     */
    fun focusUnmanaged(surface: Surface?) {
        if (surface != null && surface !in unmanaged) return
        focusLog.trace { "Keyboard to ${surface?.roleObject ?: "the focused window"} (places itself)" }
        widgetHost.keyboardTo(surface ?: layerHost.exclusiveLayer()?.surface ?: focusedWindow?.surface)
    }

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
            for (window in allWindows) decorationOfChanged(window)
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

    private val osd = Osd(renderer).also { it.fade = { animation("fadeOsd") } }

    /** What the on-screen display says now ("Volume 45 %"), or null when it is not shown. */
    val onScreenDisplay: String? get() = osd.look?.title

    /**
     * Shows what a key changed on the on-screen display (a pill at the bottom of the focused
     * monitor): [title] ("Volume 45 %", "Touchpad off") and, with a [level] 0..1, a bar —
     * grey when [off] (muted). A new one replaces it at once; it goes by itself.
     */
    @JvmOverloads
    fun showLevel(title: String, level: Double? = null, off: Boolean = false) {
        val own = osdHandler
        if (own != null && userCode("Your on-screen display", false) { own(title, level, off) }) return
        osd.show(Osd.Look(title, level, off))
        changed()
    }

    /**
     * An on-screen display of your own: told what [showLevel] would show ("Volume 45 %", the
     * level 0..1 or null, muted); true takes it (the desktop's own pill is not shown).
     */
    var osdHandler: ((title: String, level: Double?, off: Boolean) -> Boolean)? = null

    /**
     * The on-screen display with a short list: [title] and [choices], the one at [chosen]
     * marked (a key pressed again moves the mark; see the display modes of a laptop's key).
     */
    fun showChoices(title: String, choices: List<String>, chosen: Int) {
        osd.show(Osd.Look(title, null, false, choices, chosen))
        changed()
    }

    /** The [Overlay] open now (it has the keyboard and the pointer), or null. */
    var overlay: Overlay? = null
        private set

    /** Opens [overlay] above everything (one closes the one open before). */
    fun openOverlay(overlay: Overlay) {
        if (locked) {
            overlayLog.info { "Not opened while the session is locked: $overlay" }
            return
        }
        closeOverlay()
        this.overlay = overlay
        pointerGrab.end()
        overlayLog.info { "Open: $overlay" }
        seat.pointerMotion(0, null, 0.0, 0.0) // the program under the pointer loses it
        overlay.pointerMoved(pointerX, pointerY)
        changed()
        cursors.cursorChanged()
    }

    /** Closes the [overlay] open now (if any); programs get the keyboard and pointer again. */
    fun closeOverlay() {
        val open = overlay ?: return
        overlay = null
        brokenOverlay = null
        overlayLog.info { "Closed: $open" }
        changed()
        try {
            open.closed()
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            overlayLog.error("$open failed while closing", e)
        }
        movePointer(0, pointerX, pointerY) // the program under the pointer gets it back
        cursors.cursorChanged()
    }

    /** The [overlay] looks different now: drawn again. */
    fun redrawOverlay() {
        if (overlay != null) changed()
    }

    // An overlay that failed to draw (closed before the next frame).
    private var brokenOverlay: Overlay? = null

    // Runs [action] on the overlay; one that fails is closed (never the compositor with it).
    internal inline fun withOverlay(action: (Overlay) -> Unit): Boolean {
        val open = overlay ?: return false
        try {
            action(open)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            overlayLog.error("$open failed; closing it", e)
            closeOverlay()
        }
        return true
    }

    /** Closes [notice] (it fades away). */
    fun dismiss(notice: Notice) = noticeBoard.dismiss(notice)

    // A click on a notice of ours closes it, or copies it on its copy button (nothing below sees
    // the press or the release). True if it was one.
    private fun noticeClick(button: Int, pressed: Boolean): Boolean {
        if (!pressed) {
            if (button != noticeButton) return false
            noticeButton = null
            return true
        }
        if (widgetHost.pressedWidget != null) return false // (a drag in a widget goes on)
        noticeBoard.actionAt(pointerX, pointerY)?.let { (notice, action) ->
            noticeButton = button
            when (action) {
                NoticeAction.COPY -> copyText?.let { copy ->
                    userCode("copyText", Unit) { copy(notice.fullText) }
                    noticeBoard.copied(notice)
                }
                NoticeAction.CLOSE -> dismiss(notice)
            }
            return true
        }
        // A warning or an error with buttons: a click elsewhere on it does nothing (it is not lost by chance).
        noticeBoard.at(pointerX, pointerY)?.let { notice ->
            noticeButton = button
            if (notice.level == NoticeLevel.INFO || noticeBoard.style.painter !is CardNoticePainter) dismiss(notice)
            return true
        }
        return false
    }

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

    // [rect] without the strip banners take at the edge of [monitor].
    internal fun withoutBanners(rect: Rect, monitor: Monitor, room: Int, bottom: Boolean): Rect {
        if (room <= 0) return rect
        return if (bottom) {
            Rect(rect.x, rect.y, rect.width, (minOf(rect.bottom, monitor.area.bottom - room) - rect.y).coerceAtLeast(1))
        } else {
            val top = maxOf(rect.y, monitor.area.y + room)
            Rect(rect.x, top, rect.width, (rect.bottom - top).coerceAtLeast(1))
        }
    }

    /** Who draws [window]'s title bar now. */
    fun titleBarsOf(window: Window): TitleBars = titleBarsFor?.let { rule -> userCode("Your titleBarsFor", null) { rule(window) } } ?: titleBars

    /** How high the compositor's title bar over [window] is, in desktop units (0: it has none). */
    fun titleHeight(window: Window): Int {
        if (window.fullscreen) return 0
        val mode = titleBarsOf(window)
        return when {
            mode == TitleBars.NONE -> 0
            window.needsTitleBar -> titleBarStyle.height
            window.serverDecorated && mode == TitleBars.COMPOSITOR -> titleBarStyle.height
            else -> 0
        }
    }

    // [area] without the title bar at its top (where the window's own picture goes).
    internal fun withoutTitle(window: Window, area: Rect): Rect {
        val bar = titleHeight(window)
        return if (bar == 0) area else Rect(area.x, area.y + bar, area.width, (area.height - bar).coerceAtLeast(1))
    }

    // Set by ServerDecorations: tells the programs again when the settings change.
    internal var decorationsChanged: () -> Unit = {}

    /** Asks [titleBarsFor] again for every window (its answer may have changed, e.g. with a title). */
    fun titleBarsChanged() = decorationsChanged()

    // A window's title bar came or went: its place changes.
    internal fun decorationOfChanged(window: Window) {
        val workspace = workspaceOf(window) ?: return
        if (window.maximized && !window.fullscreen) windowRoles.applyState(window) else arranger.arrange(workspace)
        changed()
    }

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
    fun drawnNow(window: Window): Boolean = window.surface in visible

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
    val stacking: List<Window> get() = stackingOrder()

    // Windows bottom to top as drawn, on every monitor: tiled ones, floating ones above
    // them, fullscreen on top.
    internal fun stackingOrder(): List<Window> = shownWorkspaces().flatMap { workspace ->
        val (fullscreen, others) = workspace.windows.partition { it.fullscreen }
        others.filter { it.tiled } + others.filter { !it.tiled } + fullscreen
    }

    // The workspaces on screen, one per monitor.
    private fun shownWorkspaces(): List<Workspace> = monitorList.map { it.workspace }.distinct()

    // --- moving and resizing with the pointer ---------------------------------------------

    // The window moved or resized with the pointer now, if any.
    internal val grab: DesktopPointerGrab.Grab? get() = pointerGrab.current

    // How many buttons are held now.
    internal var buttonsHeld = 0
        private set

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
        private set
    var pointerY = height / 2.0
        private set

    // Surfaces visible right now; they get "frame done" at every refresh.
    private val visible = LinkedHashSet<Surface>()

    // The monitor each visible picture is shown on most (only with more than one monitor).
    private val surfaceHomes = HashMap<Surface, Monitor>()

    // True if [monitor] is the one [surface] is shown on most (or nobody knows: then any is).
    private fun shownMostlyOn(surface: Surface, monitor: Monitor): Boolean {
        val home = surfaceHomes[surface] ?: return true
        return home === monitor || home !in monitorList
    }

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
    fun pointerMovedBy(timeMillis: Int, dx: Double, dy: Double, rawDx: Double = dx, rawDy: Double = dy) {
        // Games and 3D programs hear the movement itself; a lock holds the pointer, a confinement keeps it in.
        pointerConstraints?.let { pc ->
            pc.relativeMotion(timeMillis, dx, dy, rawDx, rawDy)
            when (val c = constraintNow()) {
                null -> {}
                else -> if (c.locks) {
                    onUserActivity()
                    seat.pointerFrame()
                    return
                } else {
                    val (cx, cy) = confined(c, pointerX + dx, pointerY + dy)
                    return movePointer(timeMillis, cx, cy)
                }
            }
        }
        val from = monitorAt(pointerX, pointerY)
        val x = pointerX + dx
        val y = pointerY + dy
        if (from.contains(x, y)) return movePointer(timeMillis, x, y)
        when (monitorEdges) {
            MonitorEdges.TOUCHING -> {
                // Onto the monitor there, if there is one; else it stays at this one's edge.
                val onto = monitorList.firstOrNull { it.contains(x, y) }
                if (onto != null) {
                    movePointer(timeMillis, x, y)
                } else {
                    // Along the edge it can still go (sliding along it, not stuck).
                    val alongX = x.coerceIn(from.area.x.toDouble(), from.area.right - 1.0)
                    val alongY = y.coerceIn(from.area.y.toDouble(), from.area.bottom - 1.0)
                    val sideways = monitorList.firstOrNull { it !== from && it.contains(x, alongY) }?.let { x to alongY }
                        ?: monitorList.firstOrNull { it !== from && it.contains(alongX, y) }?.let { alongX to y }
                    val (nx, ny) = sideways ?: (alongX to alongY)
                    movePointer(timeMillis, nx, ny)
                }
            }
            MonitorEdges.PROPORTIONAL -> {
                val area = from.area
                // Which edge it went past (the one it went further past, at a corner).
                val overRight = x - (area.right - 1)
                val overLeft = area.x - x
                val overDown = y - (area.bottom - 1)
                val overUp = area.y - y
                val horizontal = maxOf(overRight, overLeft) >= maxOf(overDown, overUp)
                val next = if (horizontal) monitorPast(from, if (overRight > 0) 1 else -1, 0) else monitorPast(from, 0, if (overDown > 0) 1 else -1)
                if (next == null) {
                    movePointer(timeMillis, x.coerceIn(area.x.toDouble(), area.right - 1.0), y.coerceIn(area.y.toDouble(), area.bottom - 1.0))
                    return
                }
                val to = next.area
                if (horizontal) {
                    // The same share of the height; just inside the new monitor's near edge.
                    val share = ((y - area.y) / area.height).coerceIn(0.0, 1.0)
                    val nx = if (overRight > 0) to.x + overRight.coerceAtMost(to.width - 1.0) else to.right - 1 - overLeft.coerceAtMost(to.width - 1.0)
                    movePointer(timeMillis, nx, to.y + share * (to.height - 1))
                } else {
                    val share = ((x - area.x) / area.width).coerceIn(0.0, 1.0)
                    val ny = if (overDown > 0) to.y + overDown.coerceAtMost(to.height - 1.0) else to.bottom - 1 - overUp.coerceAtMost(to.height - 1.0)
                    movePointer(timeMillis, to.x + share * (to.width - 1), ny)
                }
            }
        }
    }

    // The monitor past [from]'s edge in a direction ([dx] or [dy] = ±1): wholly on that side,
    // the nearest (then the one most beside it).
    private fun monitorPast(from: Monitor, dx: Int, dy: Int): Monitor? {
        val a = from.area
        return monitorList.filter { it !== from }.filter { o ->
            val b = o.area
            (dx > 0 && b.x >= a.right - 1) || (dx < 0 && b.right <= a.x + 1) || (dy > 0 && b.y >= a.bottom - 1) || (dy < 0 && b.bottom <= a.y + 1)
        }.minWithOrNull(compareBy<Monitor>({ o ->
            val b = o.area
            when {
                dx > 0 -> b.x - a.right
                dx < 0 -> a.x - b.right
                dy > 0 -> b.y - a.bottom
                else -> a.y - b.bottom
            }
        }, { o ->
            val b = o.area
            // How far apart they are across: overlapping ones first.
            if (dx != 0) Math.abs((b.y + b.height / 2) - (a.y + a.height / 2)) else Math.abs((b.x + b.width / 2) - (a.x + a.width / 2))
        }))
    }

    /** Someone moved the pointer (for idle timers; keys and clicks are told by whoever handles them). */
    var onUserActivity: () -> Unit = {}

    /** Pointer constraints and relative motion (games); null: none. */
    var pointerConstraints: dev.caulis.core.input.PointerConstraints? = null

    // The lock in force at the last motion (to put the pointer at its hint when it ends).
    private var lastLock: dev.caulis.core.input.PointerConstraints.Constraint? = null

    // The constraint working now (none during a grab, the overview or a lock screen).
    internal fun constraintNow(): dev.caulis.core.input.PointerConstraints.Constraint? {
        val now = if (grab == null && !inOverview && !locked) pointerConstraints?.current() else null
        val ended = lastLock
        if (ended != null && ended !== now) {
            // A lock let go: the pointer goes where the program said it should be.
            ended.hint?.let { (hx, hy) -> events.surfaceOrigin(ended.surface)?.let { (ox, oy) -> pointerX = ox + hx; pointerY = oy + hy } }
        }
        lastLock = now?.takeIf { it.locks }
        return now
    }

    // [x], [y] kept inside the confinement (the part of the region the pointer is in).
    private fun confined(c: dev.caulis.core.input.PointerConstraints.Constraint, x: Double, y: Double): Pair<Double, Double> {
        val (ox, oy) = events.surfaceOrigin(c.surface) ?: return x to y
        val rects = c.region?.solidRects()?.takeIf { it.isNotEmpty() } ?: listOf(Rect(0, 0, c.surface.width, c.surface.height))
        val px = pointerX - ox
        val py = pointerY - oy
        val rect = rects.firstOrNull { px >= it.x && py >= it.y && px < it.right && py < it.bottom } ?: return x to y
        return (x - ox).coerceIn(rect.x.toDouble(), rect.right - 1.0) + ox to (y - oy).coerceIn(rect.y.toDouble(), rect.bottom - 1.0) + oy
    }

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

    // The program's surface at ([x], [y]) that gets the pen as a pen, or null: there the pen is
    // the pointer (our own things, programs without tablet events, and while the pointer is busy).
    internal fun tabletTarget(tablet: Tablet, x: Double, y: Double): Hit? {
        val tablets = tablets ?: return null
        if (tablet.settings.pointerOnly == true) return null
        if ((overlay != null && !locked) || inOverview || grab != null || popupHost.grabs.isNotEmpty() || buttonsHeld > 0) return null
        if (!locked && noticeBoard.at(x, y) != null) return null
        val hit = (hits.pointedAt(x, y, widgetsToo = true) as? Pointed.AtSurface)?.hit ?: return null
        // (Our title bars and window edges are above what is under them.)
        if (!locked && ownTitleBars.at(x, y) != null) return null
        if (!hit.surface.resource.isAlive || !seat.allowed(hit.surface) || !tablets.takes(hit.surface)) return null
        return hit
    }

    // The pen took over from the pointer: what the pointer was over hears that it left.
    internal fun pointerAway(timeMillis: Int) {
        widgetHost.hoverWidget(timeMillis, null, moved = false)
        if (ownTitleBars.hover != null) {
            ownTitleBars.hover = null
            changed()
        }
        cursors.decorationCursor = null
        if (seat.pointerFocus != null) seat.pointerMotion(timeMillis, null, 0.0, 0.0)
    }

    // The cursor is where the pen is (over a program that takes the pen as a pen).
    internal fun showPenAt(x: Double, y: Double) {
        cursors.cursorActive()
        val on = monitorAt(x, y)
        pointerX = x.coerceIn(on.area.x.toDouble(), on.area.right - 1.0)
        pointerY = y.coerceIn(on.area.y.toDouble(), on.area.bottom - 1.0)
        onChange()
    }

    // The pen's tip touched a program: our widgets it is not on hear of it (an open menu
    // closes, a text field gives the keyboard back). True if one took it (the program does not get it).
    internal fun penPressedOutside(x: Double, y: Double): Boolean {
        if (keyboardWidget != null) focusWidget(null)
        var taken = false
        for (other in widgetHost.widgets.toList().asReversed()) {
            if (userCode("Your widget (pressedOutside)", false) { other.pressedOutside(BTN_LEFT, x, y, false) }) taken = true
        }
        return taken
    }

    fun pointerMoved(timeMillis: Int, x: Double, y: Double) {
        // From an absolute position (nested, tablets): the movement counts for constraints too.
        pointerConstraints?.let { pc ->
            if (x != pointerX || y != pointerY) pc.relativeMotion(timeMillis, x - pointerX, y - pointerY, x - pointerX, y - pointerY)
            constraintNow()?.let { c ->
                if (c.locks) {
                    onUserActivity()
                    seat.pointerFrame()
                    return
                }
                val (cx, cy) = confined(c, x, y)
                return movePointer(timeMillis, cx, cy)
            }
        }
        movePointer(timeMillis, x, y)
    }

    private fun movePointer(timeMillis: Int, x: Double, y: Double) {
        onUserActivity()
        inputLog.trace { "Pointer at ${String.format("%.1f, %.1f", x, y)}" }
        cursors.cursorActive()
        // On a monitor: past an edge with no monitor beyond, it stops there.
        val on = monitorAt(x, y)
        pointerX = x.coerceIn(on.area.x.toDouble(), on.area.right - 1.0)
        pointerY = y.coerceIn(on.area.y.toDouble(), on.area.bottom - 1.0)
        lastPointerMoveNanos = System.nanoTime()
        wallpaperShaders.pointerMoved()
        // (Not while the input thread already showed movements after this one: it would go back.)
        if (quickCursorMoves && planes.earlyInFlight.get() <= 1) planes.quickCursor()
        planes.publishEarly()
        if (!locked) noticeBoard.pointerAt(pointerX, pointerY)
        if (!locked && withOverlay { it.pointerMoved(pointerX, pointerY) }) {
            onChange()
            return
        }
        grab?.let { current ->
            pointerGrab.pointerMoved(current)
            onChange()
            return
        }
        if (inOverview) {
            onChange() // only our cursor moves; programs do not see it
            return
        }
        // Buttons held since a press in a program: it keeps the pointer, wherever it goes.
        pressedIn?.let { pressed ->
            if (buttonsHeld > 0 && !locked && pressed.surface.resource.isAlive && seat.drag == null) {
                seat.pointerMotion(timeMillis, pressed.surface, (pointerX - pressed.x) / pressed.scaleX, (pointerY - pressed.y) / pressed.scaleY)
                onChange()
                return
            }
            pressedIn = null
        }
        // A button pressed in a widget: it keeps the pointer until let go.
        widgetHost.pressedWidget?.let { pressed ->
            if (buttonsHeld > 0) {
                userCode("Your widget (pointerMoved)", Unit) { pressed.widget.pointerMoved(pressed.monitor, pointerX - pressed.area.x, pointerY - pressed.area.y) }
                onChange()
                return
            }
            widgetHost.pressedWidget = null
        }
        // Over one of our widgets: it has the pointer (the program below loses it). Title bars
        // are above the widgets below the windows.
        val under = hits.pointedAt(pointerX, pointerY, widgetsToo = true)
        val overWidget = under as? Pointed.AtWidget
        // (Behind a lock no title bar lights up and no edge shows a resize cursor.)
        val decoration = if (locked || (overWidget != null && overWidget.level != DrawingLevel.BELOW_WINDOWS)) null else ownTitleBars.at(pointerX, pointerY)
        if (widgetHost.hoverWidget(timeMillis, overWidget.takeIf { decoration == null }, moved = true)) {
            onChange()
            return
        }
        // Over one of our title bars: the program does not see the pointer, the bar may light up.
        val hover = (decoration as? DesktopTitleBars.Decoration.Bar)?.let { it.window to it.button }
        cursors.decorationCursor = (decoration as? DesktopTitleBars.Decoration.Edge)?.let { cursors.resizeCursor(it.edges) }
        if (hover != ownTitleBars.hover) {
            ownTitleBars.hover = hover
            changed()
        }
        // The keyboard follows the pointer: onto another window, it gets the focus (once, so
        // keys can still move the focus while the pointer rests).
        // (Not while one of our widgets has the keyboard — a launcher, a menu: the pointer
        // passing over a window took the keys from it.)
        if (focusFollowsMouse && !locked && keyboardWidget == null) {
            val under = windowAt(pointerX, pointerY) ?: (decoration as? DesktopTitleBars.Decoration.Bar)?.window ?: (decoration as? DesktopTitleBars.Decoration.Edge)?.window
            if (under !== hovered) {
                hovered = under
                if (under != null && under !== focusedWindow && layerHost.exclusiveLayer() == null && !behindSpecial(under)) focus(under)
            }
        }
        if (decoration != null) {
            seat.pointerMotion(timeMillis, null, 0.0, 0.0)
            onChange()
            return
        }
        val hit = (under as? Pointed.AtSurface)?.hit
        seat.pointerMotion(timeMillis, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
        onChange() // only the cursor moved: the rest of the scene stays as it is
    }

    // [window] is behind a special workspace shown on the pointer's monitor that a click
    // outside hides (SpecialWorkspaces.clickOutsideHides): the pointer passing over it there
    // does not take the focus away from the special workspace (a key meant for its window —
    // moving it out — went to the window behind). Clicking there hides it, as before.
    private fun behindSpecial(window: Window): Boolean {
        if (scratchpadOf(window) != null || !specialWorkspaces.clickOutsideHides) return false
        return shownSpecialOn(monitorAt(pointerX, pointerY)) != null
    }

    // The version of the scene the pointer's program was last checked against.
    private var pointerCheckedVersion = -1L

    // Windows moved under a pointer standing still (another workspace shown, a window
    // opened, closed or moved): the program now under it gets it — with its cursor — and the
    // one that went away stops getting it (a game on a hidden workspace saw every move).
    private fun pointerOntoWhatIsThere() {
        if (pointerCheckedVersion == version) return
        pointerCheckedVersion = version
        if (overlay != null || grab != null || inOverview || buttonsHeld > 0) return
        if (pens.onPrograms) return // (the pen has it: the program under it gets the pen)
        val time = (System.nanoTime() / 1_000_000).toInt()
        val under = hits.pointedAt(pointerX, pointerY, widgetsToo = true)
        val overWidget = under as? Pointed.AtWidget
        val onBar = (overWidget == null || overWidget.level == DrawingLevel.BELOW_WINDOWS) && ownTitleBars.at(pointerX, pointerY) != null
        if (widgetHost.hoverWidget(time, overWidget.takeIf { !onBar }, moved = false) || onBar) return
        val hit = (under as? Pointed.AtSurface)?.hit
        if (hit?.surface === seat.pointerFocus) return
        inputLog.trace { "The pointer is over ${hit?.root?.roleObject ?: "nothing"} now (the windows moved)" }
        seat.pointerMotion(time, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
    }

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
        private set

    // The window the pointer was over last (for focusFollowsMouse).
    internal var hovered: Window? = null

    // The button that closed a notice: its release is ours too.
    private var noticeButton: Int? = null

    // The button whose press only closed a menu (pressedOutside): its release is ours too.
    private var outsideButton: Int? = null

    /**
     * A mouse button changed. With [passToApps] false it only counts as held (for a move or
     * resize the window manager starts itself, e.g. Super+drag) and no program sees it.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean, passToApps: Boolean = true) {
        inputLog.trace { "Button $button ${if (pressed) "pressed" else "released"} at ${pointerX.toInt()},${pointerY.toInt()}${if (passToApps) "" else " (for the window manager)"}" }
        buttonsHeld = (buttonsHeld + if (pressed) 1 else -1).coerceAtLeast(0)
        // (Wallpaper shaders may ripple from it: not on a lock screen.)
        if (pressed && !locked) wallpaperShaders.pressed(pointerX, pointerY, button)
        // A first button pressed: no release is owed to a notice or a menu any more (one taken
        // elsewhere — by the lock, by an overlay — left these set).
        if (pressed && buttonsHeld == 1) {
            noticeButton = null
            outsideButton = null
        }
        // The last button let go: the program the press was in lets go of the pointer.
        val releasedFrom = if (!pressed && buttonsHeld == 0) pressedIn.also { pressedIn = null } else null
        cursors.cursorActive()
        if (!passToApps) return
        // A drag and drop going on: letting go drops it (wherever the pointer is).
        if (seat.drag != null) {
            seat.pointerButton(timeMillis, button, pressed)
            if (seat.drag == null) movePointer(timeMillis, pointerX, pointerY) // the one under the pointer gets it now
            return
        }
        if (withOverlay { it.pointerButton(button, pressed) }) return
        // Notices are above everything (a bar too): a click on one is theirs (none on a lock screen).
        if (!locked && noticeClick(button, pressed)) return
        // A release of a press that only closed a menu: nobody's.
        if (!pressed && button == outsideButton) {
            outsideButton = null
            return
        }
        // A widget's (the one pressed in keeps it until the last button is let go).
        // (While locked only the lock screen's widgets can be hovered or pressed.)
        val widgetTarget = if (inOverview) null else widgetHost.pressedWidget?.widget ?: widgetHost.hoveredWidget
        // A press anywhere else: the widgets it is not on hear of it (an open menu closes).
        if (pressed && buttonsHeld == 1 && !inOverview && widgetHost.widgets.isNotEmpty()) {
            var taken = false
            for (other in widgetHost.widgets.toList().asReversed()) {
                if (other === widgetTarget || !lockHost.widgetUsable(other)) continue
                if (userCode("Your widget (pressedOutside)", false) { other.pressedOutside(button, pointerX, pointerY, widgetTarget != null) }) taken = true
            }
            if (taken && widgetTarget == null) {
                inputLog.trace { "Button $button: a press outside a menu, which closed (not passed on)" }
                outsideButton = button
                return
            }
        }
        if (widgetTarget != null) {
            if (pressed && buttonsHeld == 1) {
                widgetHost.hoveredArea?.takeIf { widgetTarget === widgetHost.hoveredWidget }?.let { area -> widgetHost.pressedWidget = DesktopWidgets.PressedWidget(widgetTarget, area, monitorAt(pointerX, pointerY)) }
            }
            if (pressed && userCode("Your widget (takesKeyboard)", false) { widgetTarget.takesKeyboard }) focusWidget(widgetTarget)
            userCode("Your widget (pointerButton)", Unit) { widgetTarget.pointerButton(button, pressed) }
            if (!pressed && buttonsHeld == 0 && widgetHost.pressedWidget != null) {
                widgetHost.pressedWidget = null
                movePointer(timeMillis, pointerX, pointerY)
            }
            return
        }
        // A click anywhere else takes the keyboard back from a widget.
        if (pressed && keyboardWidget != null) focusWidget(null)
        if (locked) {
            // Only the lock screen can be under the pointer: a click on one of its surfaces
            // (the locker's on another monitor, or one our own lock screen allows) gives that
            // one the keyboard — taking it from our own lock screen's widget too.
            if (pressed) hits.surfaceAt(pointerX, pointerY)?.takeIf { lockHost.lockAllows(it.root) }?.let { hit ->
                val window = hit.root.roleObject as? Window
                if (window != null) focus(window) else if (seat.keyboardFocus !== hit.root) widgetHost.keyboardTo(hit.root)
            }
            seat.pointerButton(timeMillis, button, pressed)
            return
        }
        if (inOverview) {
            if (pressed) overview.overviewClick()
            return
        }
        if (grab != null) {
            // The button that started a move or resize ends it; the app does not see it.
            if (!pressed && buttonsHeld == 0) {
                pointerGrab.end()
                movePointer(timeMillis, pointerX, pointerY) // the app under the pointer gets it back
            }
            return
        }
        // Title bars and edges of our own (no program sees these clicks).
        if (popupHost.grabs.isEmpty() && ownTitleBars.button(button, pressed)) return
        if (pressed) {
            val hit = hits.surfaceAt(pointerX, pointerY)
            // A click outside the open menus closes them (and is not passed to the window below).
            if (popupHost.grabs.isNotEmpty() && (hit == null || popupHost.popups.none { it.surface === hit.root })) {
                popupHost.grabs.toList().asReversed().forEach { it.dismiss() }
                popupHost.grabs.clear()
                return
            }
            // A click on the dimmed area around an overlay scratchpad (or a special workspace) hides it.
            val overlay = pads.shownPads.lastOrNull { it.style is Scratchpad.Overlay && !it.leaving && (!it.isSpecial || specialWorkspaces.clickOutsideHides) }
            if (overlay != null && (hit == null || (hit.root.roleObject as? Window)?.let(::scratchpadOf) !== overlay)) {
                pads.hideScratchpad(overlay)
                return
            }
            // A click on a panel that wants the keyboard on demand gives it the keyboard.
            (hit?.root?.roleObject as? LayerSurface)?.let { layer ->
                if (layer.current.keyboard != KEYBOARD_NONE && layerHost.exclusiveLayer() == null) {
                    layerHost.layerFocus = layer
                    widgetHost.keyboardTo(layer.surface)
                }
            }
            val window = hit?.root?.roleObject as? Window
            if (window != null && (window !== focusedWindow || layerHost.layerFocus != null)) {
                focus(window)
            } else if (window == null) {
                // A click on another monitor's free space: new windows open there now.
                val clicked = monitorAt(pointerX, pointerY)
                if (clicked !== focusedMonitor && hit?.root?.roleObject !is LayerSurface) focusMonitor(clicked)
            }
            // The first button: the pointer stays with this program until the last one is let
            // go, outside its window too (selecting text past the edge, dragging a slider).
            if (buttonsHeld == 1 && hit != null && hit.surface === seat.pointerFocus) {
                pressedIn = PressedIn(hit.surface, pointerX - hit.localX * hit.scaleX, pointerY - hit.localY * hit.scaleY, hit.scaleX, hit.scaleY)
            }
        }
        seat.pointerButton(timeMillis, button, pressed)
        if (releasedFrom != null) movePointer(timeMillis, pointerX, pointerY) // the one under the pointer gets it now
    }

    /**
     * The window that gets the pointer now: the one under it, or — while buttons are held —
     * the one they were pressed in (null: none, a panel, or our own title bar).
     */
    val pointerWindow: Window? get() = seat.pointerFocus?.let(capturing::ownerOf) as? Window

    // The surface the held buttons were pressed in, and where its top left corner was (desktop
    // units): pointer moves go to it until they are let go (Wayland's implicit grab).
    internal var pressedIn: PressedIn? = null

    internal class PressedIn(val surface: Surface, val x: Double, val y: Double, val scaleX: Double, val scaleY: Double)

    /**
     * Scrolling as 0.1.1 had it: [value] units along one axis, [value120] the wheel's steps in
     * 1/120 (a full notch = 120; 0: fingers).
     */
    @Deprecated("Give a Scroll (where it came from, notches in 1/120)", ReplaceWith("pointerAxis(timeMillis, Scroll(...))"))
    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) {
        val source = if (value120 != 0) dev.caulis.core.input.Scroll.Source.WHEEL else dev.caulis.core.input.Scroll.Source.FINGER
        pointerAxis(
            timeMillis,
            if (vertical) dev.caulis.core.input.Scroll(source, vertical = value, vertical120 = value120)
            else dev.caulis.core.input.Scroll(source, horizontal = value, horizontal120 = value120),
        )
    }

    /** Scrolling (the wheel or fingers, see [Scroll]): an overlay, a widget or the program under the pointer gets it. */
    fun pointerAxis(timeMillis: Int, scroll: Scroll) {
        inputLog.trace { "$scroll" }
        cursors.cursorActive()
        if (!locked && overlay != null) {
            // An overlay hears each direction in steps (a notch is 1).
            for (vertical in listOf(true, false)) {
                val value = if (vertical) scroll.vertical else scroll.horizontal
                val value120 = if (vertical) scroll.vertical120 else scroll.horizontal120
                if (value != 0.0 || value120 != 0) withOverlay { it.scroll(vertical, if (value120 != 0) value120 / 120.0 else value / 15.0) }
            }
            return
        }
        widgetHost.hoveredWidget?.takeIf { !inOverview }?.let { widget ->
            if (scroll.horizontal != 0.0 || scroll.vertical != 0.0) userCode("Your widget (scroll)", Unit) { widget.scroll(scroll.horizontal, scroll.vertical) }
            return
        }
        seat.pointerAxis(timeMillis, scroll)
    }

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

    // A program drew something: mark those parts as changed where the surface is shown.
    private fun surfaceCommitted(surface: Surface) {
        val changes = surface.takeChanges()
        if (changes.isNotEmpty()) {
            for (view in views + capturing.captureViews) {
                // Everywhere it is drawn, at the size it is drawn (smaller in the overview,
                // stretched while it glides): rounded outwards, so no changed pixel is missed.
                for (item in view.shown) {
                    if (item !is DrawItem.Picture || item.surface !== surface) continue
                    val shownAt = item.rect
                    val sx = if (surface.width > 0) shownAt.width.toDouble() / surface.width else 1.0
                    val sy = if (surface.height > 0) shownAt.height.toDouble() / surface.height else 1.0
                    for (change in changes) {
                        val left = Math.floor(change.x * sx).toInt()
                        val top = Math.floor(change.y * sy).toInt()
                        val right = Math.ceil(change.right * sx).toInt()
                        val bottom = Math.ceil(change.bottom * sy).toInt()
                        view.damage(Rect(shownAt.x + left, shownAt.y + top, right - left, bottom - top))
                    }
                }
            }
        }
        for (listener in capturing.commitListeners.toList()) listener(surface)
        changed()
        scheduleHiddenRefresh()
    }

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
    ): Boolean {
        if (brokenOverlay != null && brokenOverlay === overlay) closeOverlay()
        // Each asked every time (they move their animations on, and let go of what faded away:
        // none may be skipped because another already said yes).
        val now = System.nanoTime()
        val noticesMoving = noticeBoard.animating() or osd.animating(now)
        val fadingNow = scaleFade.fading()
        val animatingNow = animator.animating()
        val backdropsChanging = backdrops.changing()
        val directorAnimating = director?.let { d -> userCode("Your WindowDirector (animating)", false) { d.animating(now) } } == true
        var directorMoving = directorAnimating
        var movingThing: Any? = null
        for (list in widgetHost.drawings.values) for (d in list) if (userCode("Your drawing (animating)", false) { d.animating(now) }) { directorMoving = true; movingThing = d }
        for (w in widgetHost.widgets.toList()) if (userCode("Your widget (animating)", false) { w.animating(now) }) { directorMoving = true; movingThing = w }
        val paintsNow = looks.paintsMoving && looks.paintsDue(now)
        if (animator.glides.isNotEmpty() || pads.shownPads.any { it.changedAt != null } || overview.cameraMoving(System.nanoTime()) || fadingNow || noticesMoving || animatingNow || backdropsChanging || paintsNow || looks.focusChanging || directorMoving) {
            // Windows are gliding (or the overview zooms): the scene changes every frame until done.
            if (renderLog.level >= 2) {
                val why = listOfNotNull("glides".takeIf { animator.glides.isNotEmpty() }, "camera".takeIf { overview.cameraMoving(System.nanoTime()) }, "fading".takeIf { fadingNow },
                    "notices".takeIf { noticesMoving }, "animating".takeIf { animatingNow }, "backdrops".takeIf { backdropsChanging }, "paints".takeIf { paintsNow },
                    "focus".takeIf { looks.focusChanging }, "director".takeIf { directorAnimating }, movingThing?.let { "drawing or widget $it" })
                renderLog.trace { "Moving: ${why.joinToString()}" }
                busy.moving(why)
            }
            version++
            onChange()
        }
        // (What is under the pointer may fail on a window going away: the frame is drawn all the same.)
        try {
            pointerOntoWhatIsThere()
        } catch (e: Exception) {
            log.error("Finding what is under the pointer failed (the frame is drawn anyway)", e)
        }
        planes.placeHardwareCursor(view)
        // A moving screen shader: every frame anew (and the next one asked for).
        val shaderMoves = screenShader?.moving == true
        if (shaderMoves) onChange()
        val draw = force || needsRedraw(view) || shaderMoves
        val direct = if (draw && !force && presentDirect != null) directCandidate(view) else null
        if (direct != null && presentDirect!!(direct)) {
            // Nothing drawn: the program's own picture is on screen.
            view.shownWithoutDrawing()
            takeFeedbacks(view)
        } else if (draw) {
            var drawn = false
            present { target, age ->
                drawn = true
                try {
                    render(view, target, age)
                } catch (e: Throwable) {
                    // Half a frame: the next one is drawn whole, so nothing stale stays on screen.
                    view.damageEverything()
                    throw e
                }
            }
            // Nothing reached the screen (it is off, or another terminal is shown): what changed
            // stays to be drawn, and the backend's slow heartbeat still gives programs their frames.
            if (!drawn) renderLog.trace { "${view.monitor.name}: nothing drawn (the screen is dark)" }
        } else {
            // Nothing new to show: whatever programs committed is on screen already.
            takeFeedbacks(view)
            presented(view, System.nanoTime(), refreshNanos = 0, sequence = 0, flags = 0)
        }
        frameShown(view, (System.nanoTime() / 1_000_000).toInt())
        for (listener in capturing.frameListeners.toList()) listener()
        if (draw && renderLog.level >= 2) busy.drawn(view.monitor.name)
        return draw
    }

    // What kept the screens being drawn: said every few seconds at the render log's info level
    // (CAULIS_LOG=render=2) — the frames drawn per monitor, and what was moving, most often first.
    // (To find out why a compositor that should be idle keeps a processor busy.)
    private val busy = object {
        private var since = System.nanoTime()
        private val frames = LinkedHashMap<String, Int>()
        private val reasons = HashMap<String, Int>()

        fun moving(why: List<String>) {
            for (reason in why) reasons.merge(reason, 1, Int::plus)
        }

        fun drawn(monitor: String) {
            frames.merge(monitor, 1, Int::plus)
            val now = System.nanoTime()
            if (now - since < BUSY_REPORT_NANOS) return
            val seconds = (now - since) / 1e9
            renderLog.info {
                "Frames in the last %.0f s: %s; moving: %s".format(seconds, frames.entries.joinToString { "${it.key} ${it.value}" },
                    reasons.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} (${it.value})" }.ifEmpty { "nothing (programs' or the pointer's changes)" })
            }
            since = now
            frames.clear()
            reasons.clear()
        }
    }

    /**
     * The frame last drawn for [view] reached the screen at [nanos] (monotonic clock).
     * Programs that asked are told (presentation-time).
     */
    fun presented(view: DesktopView, nanos: Long, refreshNanos: Long, sequence: Long, flags: Int) {
        Presentation.presented(view.inFlight, nanos, refreshNanos, sequence, flags)
        view.inFlight.clear()
        if (lockHost.lockPending && view.showsLock) {
            lockHost.lockShownOn += view
            lockHost.confirmLockIfShown()
        }
    }

    /**
     * True if [view] needs a new frame: compares what would be drawn now with what it
     * showed last time (windows that moved, opened, closed, the cursor, ...).
     */
    fun needsRedraw(view: DesktopView): Boolean {
        compare(view, itemsFor(view))
        return view.hasChanges
    }

    // What [view]'s frames show: all, or all but the cursor while it is on the cursor plane.
    private fun itemsFor(view: DesktopView): List<DrawItem> {
        val all = items()
        if (!view.cursorOnPlane) return all
        if (itemsWithoutCursorOf !== all) {
            itemsWithoutCursor = all.subList(0, itemsCursorStart.coerceAtMost(all.size))
            itemsWithoutCursorOf = all
        }
        return itemsWithoutCursor
    }

    private var itemsWithoutCursor: List<DrawItem> = emptyList()
    private var itemsWithoutCursorOf: List<DrawItem>? = null

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
    fun render(view: DesktopView, target: GpuTarget, age: Int) {
        val items = itemsFor(view)
        compare(view, items)
        takeFeedbacks(view)
        val monitor = view.monitor
        val bounds = monitor.area
        view.showsLock = lockHost.lockHidesDesktop
        var parts = view.takeRepaintParts(if (fullRedraw) 0 else age, bounds) ?: return
        backgroundAsked = 0
        backgroundDrawn = 0
        // Blur reads around its area: a change near it (within its reach, not only under it)
        // changes it too — its whole reach redrawn first, in one part (else a bar's blur stayed
        // stale while a program drew next to it, and flashed when it caught up).
        if (items.any { it is DrawItem.Blur && parts.any { part -> it.reachRect().intersect(part) != null } }) {
            parts = withBlurParts(items.filterIsInstance<DrawItem.Blur>().map { it.reachRect() }, parts, bounds)
        }
        // In pixels, one more around each: a fractional scale must not leave a half-drawn edge.
        // Where those overlap, each pixel belongs to one part only (nothing is drawn twice).
        val whole = Rect(0, 0, target.width, target.height)
        val clips = ArrayList<Pair<Rect, Rect>>() // pixels, and the part in units it shows
        for (part in parts) {
            val px = monitor.toPixels(part)
            var pieces = listOfNotNull(Rect(px.x - 1, px.y - 1, px.width + 2, px.height + 2).intersect(whole))
            for ((taken, _) in clips) pieces = pieces.flatMap { it.minus(taken) }
            pieces.forEach { clips += it to part }
        }
        if (clips.isEmpty()) return
        renderLog.trace { "Frame of ${monitor.name}: redraw ${parts.joinToString()} (${clips.size} parts in pixels) of ${target.width}x${target.height} (buffer age $age), ${items.size} things to draw" }
        for ((i, clip) in clips.withIndex()) {
            val (pixels, part) = clip
            // (One unit more around the part: what touches its pixels.)
            val near = Rect(part.x - 1, part.y - 1, part.width + 2, part.height + 2)
            if (i == 0) {
                renderer.begin(target, backgroundColor.red, backgroundColor.green, backgroundColor.blue, clip = pixels)
            } else {
                renderer.setClip(pixels)
                renderer.drawColor(pixels.x, pixels.y, pixels.width, pixels.height, backgroundColor.red, backgroundColor.green, backgroundColor.blue)
            }
            draw(items, near, monitor::toPixels, monitor.scale, monitor, pixels)
        }
        renderer.end()
        renderLog.trace { "Frame of ${monitor.name}: background $backgroundDrawn of $backgroundAsked pixels drawn (the rest under opaque windows)" }
    }

    // In this frame: the background's pixels in the parts redrawn, and those really drawn.
    private var backgroundAsked = 0L
    private var backgroundDrawn = 0L

    // Draws background [item] (at [index] of [items]) within [repaint]: only where nothing opaque
    // is above it (else every window drawing would draw the wallpaper under itself first). Each
    // piece one pixel bigger: a fractional scale must not leave an edge undrawn; the window above
    // is drawn over it. [clip]: the part's pixels. Too many pieces (or fullRedraw): as a whole.
    private fun drawBackground(item: DrawItem.Custom, items: List<DrawItem>, index: Int, repaint: Rect, clip: Rect, r: Rect, toPixels: (Rect) -> Rect) {
        val asked = clip.intersect(r)
        backgroundAsked += asked?.area() ?: 0L
        val whole = item.rect.intersect(repaint)
        val pieces = if (whole == null || fullRedraw) null else uncovered(listOf(whole), items, index, MAX_BACKGROUND_PIECES)
        if (pieces == null || pieces == listOf(whole)) {
            item.paint(r)
            backgroundDrawn += asked?.area() ?: 0L
            return
        }
        for (piece in pieces) {
            val px = toPixels(piece)
            val scissor = Rect(px.x - 1, px.y - 1, px.width + 2, px.height + 2).intersect(clip) ?: continue
            renderer.setClip(scissor)
            item.paint(r)
            backgroundDrawn += scissor.intersect(r)?.area() ?: 0L
        }
        renderer.setClip(clip)
    }

    // Draws [items] (only those touching [repaint]) into the frame begun; [p] turns desktop
    // units into the target's pixels, at [scale].
    // [clip]: the pixels the frame is cut to now (a part of the monitor), when known.
    internal fun draw(items: List<DrawItem>, repaint: Rect?, p: (Rect) -> Rect, scale: Double, monitor: Monitor? = null, clip: Rect? = null) {
        val s = scale.toFloat()
        for (index in items.indices) {
            val item = items[index]
            if (repaint != null && item.rect.intersect(repaint) == null) continue
            if (monitor != null && item.only != null && item.only !== monitor) continue // (a sliding workspace of another monitor)
            val r = p(item.rect)
            when (item) {
                is DrawItem.Picture -> renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, item.opaque, item.alpha, item.clip?.let(p), item.radius * s, item.source, item.transform)
                is DrawItem.Color -> renderer.drawColor(r.x, r.y, r.width, r.height, item.red, item.green, item.blue, item.alpha)
                is DrawItem.Shadow -> {
                    val drawn = renderer.drawShadow(p(item.box), item.radius * s, item.softness * s, item.paint, p(item.cutOut), item.cutOutRadius * s, item.alpha, item.time)
                    looks.paintWorks(item.paint, drawn)
                    if (!drawn) renderer.drawShadow(p(item.box), item.radius * s, item.softness * s, dev.caulis.render.Paint.solid(dev.caulis.render.Color(0f, 0f, 0f, 0.45f * item.alpha)), p(item.cutOut), item.cutOutRadius * s)
                }
                is DrawItem.Border -> {
                    val drawn = renderer.drawRoundedRect(r, item.radius * s, item.paint, item.width * s, item.alpha, item.time, item.state)
                    looks.paintWorks(item.paint, drawn)
                    if (!drawn) renderer.drawRoundedRect(r, item.radius * s, item.paint.firstColor().let { it.copy(alpha = it.alpha * item.alpha) }, item.width * s)
                }
                is DrawItem.Blur -> renderer.drawBlur(r, item.radius * s, Math.round(item.size * s).coerceAtLeast(1), item.passes, item.tone)
                is DrawItem.Image -> renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, opaque = false, alpha = item.alpha, clip = item.clip?.let(p), cornerRadius = item.radius * s)
                is DrawItem.Custom -> if (item.background && repaint != null && clip != null) drawBackground(item, items, index, repaint, clip, r, p) else item.paint(r)
                is DrawItem.Tint -> renderer.drawRoundedRect(r, item.radius * s, dev.caulis.render.Color(item.red, item.green, item.blue, item.alpha))
                is DrawItem.Effect -> {
                    val code = item.shader.code
                    val drawn = renderer.drawTextureEffect(item.texture, r.x, r.y, r.width, r.height, item.alpha, code, item.from, item.to, item.progress)
                    looks.shaderWorks(item.shader.name, code, item.shader.fileProblem ?: if (drawn) null else renderer.effectProblem(code) ?: "it does not compile")
                    if (!drawn) {
                        val f = p(item.fallback)
                        renderer.drawTexture(item.texture, f.x, f.y, f.width, f.height, opaque = false, alpha = item.alpha)
                    }
                }
            }
        }
    }

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

    /**
     * The buffer of a fullscreen window that is the only thing on screen (no menus, no
     * cursor drawn by us, nothing above it), if it is a GPU buffer exactly as big as the
     * screen. Such a picture can go to the display as it is.
     */
    private fun directCandidate(view: DesktopView): DmabufBuffer? {
        if (screenShader != null) return null // (every frame goes through it)
        val monitor = view.monitor
        // (A cursor on the cursor plane is no reason to draw: it is shown over the program's picture.)
        val all = itemsFor(view).filter { it.rect.intersect(monitor.area) != null }
        val only = all.singleOrNull() as? DrawItem.Picture ?: return null
        val window = only.surface.roleObject as? Window ?: return null
        if (!window.fullscreen || only.rect != monitor.area) return null
        val buffer = only.surface.current.buffer?.dataAs<DmabufBuffer>() ?: return null
        // Turned as the monitor is (a program drawing for a screen on its side does so), and
        // exactly the screen's own pixels: shown as it is.
        if (only.surface.current.transform != monitor.transform.ordinal) return null
        val (width, height) = if (monitor.transform.sideways) monitor.pixelHeight to monitor.pixelWidth else monitor.pixelWidth to monitor.pixelHeight
        return buffer.takeIf { it.width == width && it.height == height }
    }

    // The visible programs' "tell me when it is shown" requests now belong to this frame.
    private fun takeFeedbacks(view: DesktopView) {
        for (surface in visible) {
            if (!shownMostlyOn(surface, view.monitor)) continue
            val feedbacks = surface.current.feedbackList
            if (feedbacks.isEmpty()) continue
            view.inFlight += feedbacks
            feedbacks.clear()
        }
    }

    // Marks everything that appeared, disappeared, moved or changed order since the view's last frame.
    internal fun compare(view: DesktopView, items: List<DrawItem>) {
        if (items === view.shown) return // nothing changed since the last comparison
        val before = view.shown.withIndex().associateBy({ it.value.key }, { it })
        val now = items.withIndex().associateBy({ it.value.key }, { it })
        // (What changed, at the finest level: to find what keeps a screen being drawn.)
        val why = if (renderLog.level >= 3) ArrayList<String>() else null
        for ((key, old) in before) {
            val new = now[key]
            if (new == null) {
                old.value.areas.forEach(view::damage)
                why?.add("gone $key")
            } else if (new.index != old.index || new.value.differsFrom(old.value)) {
                // (The background: only where it shows — not under the windows over it.)
                uncoveredAreas(old.value, view.shown, old.index).forEach(view::damage)
                uncoveredAreas(new.value, items, new.index).forEach(view::damage)
                why?.add("changed $key ${new.value.rect}")
            }
        }
        for ((key, new) in now) if (key !in before) {
            new.value.areas.forEach(view::damage)
            why?.add("new $key")
        }
        if (!why.isNullOrEmpty()) renderLog.trace { "${view.monitor.name} changed: ${why.take(6).joinToString("; ")}" }
        view.shown = items
    }

    // The scene (everything except the cursor), rebuilt only after a change.
    private var scene: List<DrawItem> = emptyList()
    private val sceneVisible = ArrayList<Surface>()
    private var sceneVersion = -1L

    // The scene plus the cursor, rebuilt when the scene changed or the pointer moved.
    internal var items: List<DrawItem> = emptyList()
    private var itemsVersion = -1L
    private var itemsPointerX = Double.NaN
    private var itemsPointerY = Double.NaN
    private var itemsCursorVersion = -1L
    internal var itemsCursorStart = 0

    /** Everything to draw now, bottom to top, the cursor last. */
    internal fun items(): List<DrawItem> {
        if (itemsVersion == version && itemsPointerX == pointerX && itemsPointerY == pointerY && itemsCursorVersion == cursors.cursorVersion) return items
        val all = ArrayList<DrawItem>(scene().size + 2)
        all += scene
        visible.clear()
        visible += sceneVisible
        // The frame before a scale change, fading out over the new one (the cursor stays above).
        for ((monitor, fade) in scaleFade.fades) all += DrawItem.Image("$FADE_KEY ${monitor.name}", fade.texture, monitor.area, alpha = scaleFade.fadeAlphas[monitor] ?: 1f)
        val cursorStart = all.size
        itemsCursorStart = cursorStart
        cursors.addCursor(all)
        for (i in cursorStart until all.size) (all[i] as? DrawItem.Picture)?.let { visible += it.surface }
        surfaceHomes.clear()
        if (monitorList.size > 1) {
            // Where each picture is shown most (frame callbacks and presentation times come from there).
            val shownArea = HashMap<Surface, HashMap<Monitor, Long>>()
            for (item in all) {
                if (item !is DrawItem.Picture) continue
                for (monitor in monitorList) {
                    if (item.only != null && item.only !== monitor) continue
                    val part = item.rect.intersect(monitor.area) ?: continue
                    shownArea.getOrPut(item.surface) { HashMap() }.merge(monitor, part.width.toLong() * part.height, Long::plus)
                }
            }
            for ((surface, areas) in shownArea) surfaceHomes[surface] = areas.maxBy { it.value }.key
        }
        items = all
        itemsVersion = version
        itemsPointerX = pointerX
        itemsPointerY = pointerY
        itemsCursorVersion = cursors.cursorVersion
        return all
    }

    /**
     * Windows and popups to draw, bottom to top. Things nobody can see are left out:
     * outside the desktop, or completely behind something opaque.
     */
    private fun scene(): List<DrawItem> {
        if (sceneVersion == version) return scene
        looks.paintsMoving = false
        looks.focusChanging = false
        val all = ArrayList<DrawItem>()
        if (lockHost.lockHidesDesktop) {
            // Nothing of the desktop: a plain colour and the locker's pictures, on every monitor
            // (and our widgets usable on the lock screen, above them).
            val color = if (!lockHost.sessionLocked || lockHost.lock?.alive == true) lockColor else abandonedLockColor
            for (monitor in monitorList) {
                all += DrawItem.Color("$LOCK_BACKGROUND ${monitor.name}", monitor.area, color.red, color.green, color.blue, covers = listOf(monitor.area))
            }
            lockBackdrop?.takeIf { !lockHost.sessionLocked }?.let { addLockBackdrop(all, it) }
            for (lockSurface in lockHost.lockSurfaces()) lockMonitor(lockSurface).area.let { addTree(all, lockSurface.surface, it.x, it.y) }
            for (level in DrawingLevel.entries) widgetHost.addWidgets(all, level, onLockScreen = true)
            sceneVisible.clear()
            for (item in all) if (item is DrawItem.Picture) sceneVisible += item.surface
            scene = all
            sceneVersion = version
            return all
        }
        val now = System.nanoTime()
        val camera = overview.cameraAt(now)
        val placed = placeDirected(now)
        val (fullscreen, normal) = stackingOrder().filter { !directed(it) }.partition { it.fullscreen }
        // Our background on each monitor, below a wallpaper program's (which wins).
        val allMonitors = desktopBounds()
        wallpaperShaders.sample(pointerX, pointerY, now)
        for (monitor in monitorList) all += backdrops.items(monitor, monitor.workspace.number, pointerX, pointerY, now, allMonitors, largestScale)
        widgetHost.addDrawings(all, DrawingLevel.WALLPAPER)
        addLayers(all, LayerShell.Layer.BACKGROUND)
        addLayers(all, LayerShell.Layer.BOTTOM)
        widgetHost.addDrawings(all, DrawingLevel.BELOW_WINDOWS)
        // A monitor changing its workspace: how the old and the new one are drawn now. The new
        // one's windows (and the old one's) show only on that monitor while they move.
        class Change(val monitor: Monitor, val frame: WorkspaceFrame)
        val changes = HashMap<Workspace, Change>()
        for ((monitor, slide) in animator.slides.toList()) {
            val picked = animator.pick("workspaces", animations.workspaceSwitch)
            val animation = picked.look
            val t = animator.animationProgress(slide.start, picked.millis, now)
            if (t == null || inOverview || monitor !in monitorList || monitor.workspace !== slide.to) {
                animator.slides -= monitor
                continue
            }
            val frame = try {
                animation.frame(picked.curve.safeAt(t), slide.direction, monitor.area)
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.error("Your workspace animation failed (workspaces change at once): $e", e)
                animator.slides -= monitor
                continue
            }
            changes[slide.to] = Change(monitor, frame)
            // The workspace before, on its way out (unless another monitor shows it now).
            if (monitorList.none { it.workspace === slide.from }) {
                val start = all.size
                val outgoing = DesktopOverview.Camera(1.0, frame.outgoingDx, frame.outgoingDy)
                for (window in stacking(slide.from)) addWindow(all, window, now, camera = outgoing)
                fade(all, start, frame.outgoingAlpha, monitor)
            }
        }
        fun changeOf(window: Window) = changes.entries.firstOrNull { (workspace, _) -> window in workspace.windows || window in workspace.minimized }?.value
        // The overview's camera is on the monitor with the focus only; a changing workspace moves.
        fun cameraFor(window: Window): DesktopOverview.Camera {
            if (camera !== DesktopOverview.Camera.NONE) return if (window in activeWorkspace.windows || window in activeWorkspace.minimized) camera else DesktopOverview.Camera.NONE
            val change = changeOf(window) ?: return DesktopOverview.Camera.NONE
            return DesktopOverview.Camera(1.0, change.frame.incomingDx, change.frame.incomingDy)
        }
        fun addWindowNow(window: Window) {
            val change = changeOf(window)
            val start = all.size
            addWindow(all, window, now, camera = cameraFor(window))
            if (change != null) fade(all, start, change.frame.incomingAlpha, change.monitor)
        }
        for (window in normal) addWindowNow(window)
        // The director's windows, where it put them, lowest first.
        for ((window, place) in placed) addDirected(all, window, place)
        // Windows going away: their last look, as the close animation says.
        animator.addFlights(all, now, DesktopAnimations.FlightKind.CLOSE)
        widgetHost.addDrawings(all, DrawingLevel.ABOVE_WINDOWS)
        addLayers(all, LayerShell.Layer.TOP)
        // Windows on their way into the dock and out of it (above it, so they are seen going into their icon).
        animator.addFlights(all, now, DesktopAnimations.FlightKind.MINIMIZE)
        animator.addFlights(all, now, DesktopAnimations.FlightKind.RESTORE)
        // Fullscreen windows cover panels and bars (not notifications on the overlay layer).
        for (window in fullscreen) addWindowNow(window)
        // Shown scratchpads above every workspace, the last shown on top.
        for (pad in pads.shownPads) {
            val overlay = pad.style as? Scratchpad.Overlay
            val amount = if (pad.isSpecial) pads.padAmount(pad, now) else 1f
            if (pad.isSpecial) {
                // A special workspace: what is behind it blurred and dimmed, as its settings say (the dim fading with it).
                val settings = specialWorkspaces
                if (!pad.leaving) settings.blur?.let { blur -> all += DrawItem.Blur(EffectKey("blur special", pad), pads.padArea(pad), 0f, blur.size, blur.passes, blur.tone) }
                if (settings.dim > 0f && amount > 0f) all += DrawItem.Color("dim special ${pad.name}", pads.padArea(pad), 0f, 0f, 0f, alpha = settings.dim * amount)
            } else if (overlay != null && !pad.leaving) {
                all += DrawItem.Color("dim ${pad.name}", pads.padArea(pad), 0f, 0f, 0f, alpha = overlay.dim)
            }
            val fade = if (pad.isSpecial && specialWorkspaces.look.fade) amount else 1f
            val first = all.size
            for (window in pad.windows) addWindow(all, window, now, leaving = pad.leaving, fade = fade)
            // A special workspace's windows lie side by side: one's shadow or glow goes under the others too.
            if (pad.isSpecial) shadowsFirst(all, first)
        }
        addLayers(all, LayerShell.Layer.OVERLAY)
        for (popup in popupHost.popups) {
            if (camera !== DesktopOverview.Camera.NONE) break // menus are not part of the overview
            if (!popupHost.popupShown(popup) || !lockHost.drawnBehindLock(popup.surface)) continue
            val (x, y) = hits.popupOrigin(popup)
            addTree(all, popup.surface, x, y)
        }
        if (camera === DesktopOverview.Camera.NONE) for ((surface, place) in unmanaged) if (lockHost.drawnBehindLock(surface)) addTree(all, surface, place.first, place.second)
        widgetHost.addDrawings(all, DrawingLevel.ON_TOP)
        // Our own notices above everything (not on a locked screen: what they say is private).
        if (!locked) for (monitor in monitorList) all += noticeBoard.items(monitor, monitorList, noticeFallback(monitor))
        if (!locked) overlay?.takeIf { it !== brokenOverlay }?.let { open ->
            for (monitor in monitorList) {
                val canvas = OverlayCanvas(monitor, "overlay ${monitor.name}")
                try {
                    open.draw(monitor, canvas)
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    overlayLog.error("$open failed to draw; closing it", e)
                    brokenOverlay = open
                    onChange()
                    break
                }
                if (brokenOverlay !== open) all += canvas.items
            }
        }
        if (!locked) all += osd.items(focusedMonitor, System.nanoTime())

        // Leave out what nobody can see; only the rest counts as visible.
        val kept = cullHidden(all, desktopBounds())
        // A moving wallpaper shader goes on only where it is seen (not under a wallpaper program, a fullscreen window).
        backdrops.sceneBuilt()
        wallpaperShaders.sceneBuilt(kept)
        sceneVisible.clear()
        for (item in kept) if (item is DrawItem.Picture) sceneVisible += item.surface
        placeSurfaces(kept)
        scene = kept
        sceneVersion = version
        return kept
    }

    // The items from [start] on show only on [monitor], at [alpha] (a sliding or fading workspace).
    private fun fade(all: MutableList<DrawItem>, start: Int, alpha: Float, monitor: Monitor) {
        for (i in start until all.size) {
            val item = all[i]
            item.only = monitor
            if (alpha < 1f) all[i] = item.fadedBy(alpha)
        }
    }

    // A workspace's windows bottom to top, as drawn.
    private fun stacking(workspace: Workspace): List<Window> {
        val (fullscreen, others) = workspace.windows.partition { it.fullscreen }
        return others.filter { it.tiled } + others.filter { !it.tiled } + fullscreen
    }

    // The wallpaper behind the lock screen: nothing else of the desktop (see [lockBackdrop]).
    private fun addLockBackdrop(all: MutableList<DrawItem>, backdrop: LockBackdrop) {
        val now = System.nanoTime()
        if (backdrop.wallpaper) {
            wallpaperShaders.sample(pointerX, pointerY, now)
            val allMonitors = desktopBounds()
            for (monitor in monitorList) all += backdrops.items(monitor, monitor.workspace.number, pointerX, pointerY, now, allMonitors, largestScale)
            widgetHost.addDrawings(all, DrawingLevel.WALLPAPER, withWidgets = false)
        }
        for (monitor in monitorList) {
            backdrop.blur?.let { b -> all += DrawItem.Blur(EffectKey("blur lock", monitor), monitor.area, 0f, b.size, b.passes, b.tone) }
            if (backdrop.dim > 0f) all += DrawItem.Color("dim lock ${monitor.name}", monitor.area, 0f, 0f, 0f, alpha = backdrop.dim)
        }
    }

    private fun addLayers(all: MutableList<DrawItem>, layer: LayerShell.Layer) {
        for (layerSurface in layerHost.layerSurfaces) {
            if (!layerSurface.mapped || layerSurface.current.layer != layer) continue
            val surface = layerSurface.surface
            if (!lockHost.drawnBehindLock(surface)) continue
            val rect = Rect(layerSurface.x, layerSurface.y, surface.width, surface.height)
            // Everything behind it darker (a launcher in front of the desktop).
            val dim = layerHost.layerBehaviorOf(layerSurface).dimAround
            if (dim > 0f) all += DrawItem.Tint(EffectKey("dim around", layerSurface), monitorOf(layerSurface).area, 0f, 0f, 0f, 0f, dim)
            val look = looks.addEffects(all, layerSurface, surface, Rect(0, 0, surface.width, surface.height), rect, 1.0, userCode("Your layerEffects", Effects.NONE) { layerEffects(layerSurface) }, focused = true)
            addTree(all, surface, layerSurface.x, layerSurface.y, look = look)
        }
    }

    // A window where it is (or on its way, see glides); a leaving one only while it moves.
    internal fun addWindow(all: MutableList<DrawItem>, window: Window, now: Long, leaving: Boolean = false, camera: DesktopOverview.Camera = DesktopOverview.Camera.NONE, plain: Boolean = false, fade: Float = 1f) {
        // Coming back out of the dock: its picture flies (see flights) until it is there.
        if (!plain && animator.flights.any { it.window === window && it.kind == DesktopAnimations.FlightKind.RESTORE }) {
            animator.shownRects[window] = windowRoles.currentRect(window)
            return
        }
        val visibleRect = window.visibleRect
        val target = windowRoles.currentRect(window)
        val glide = if (plain) null else animator.glides[window]
        val shown = glide?.let { animator.glidePosition(it, target, now) }
        if (glide != null && shown == null) animator.glides -= window
        if (leaving && shown == null) return // gone out of sight
        var world = shown ?: target
        // Tiled windows follow the fingers while the view is dragged.
        if ((overview.dragX != 0.0 || overview.dragY != 0.0) && window.tiled && window in activeWorkspace.windows) {
            world = world.translate(overview.dragX.roundToInt(), overview.dragY.roundToInt())
        }
        animator.shownRects[window] = world
        // Appearing: as the open animation says (only its picture: the window itself is in place).
        var alpha = fade
        val appearSince = if (plain) null else animator.appearing[window]
        if (appearSince != null) {
            val picked = animator.pick("windowsIn", animations.windowOpen)
            val animation = picked.look
            val t = animator.animationProgress(appearSince, picked.millis, now)
            val frame = if (t == null) null else try {
                animation.frame(Motion(window, world, world, picked.curve.safeAt(t), t, monitorOf(window).usableArea))
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.error("Your window animation failed (it is drawn without): $e", e)
                null
            }
            if (frame == null) {
                animator.appearing -= window
                animator.appearingPictures.remove(window)?.close()
            } else if (frame.shader != null) {
                // Through a shader: the whole window as one picture, made anew each frame.
                val picture = animator.pictureOf(window)
                (if (picture != null) animator.appearingPictures.put(window, picture.first) else animator.appearingPictures.remove(window))?.close()
                if (picture != null) {
                    // The picture has the window's shadow around it: every rectangle grows the same.
                    val (_, texture, box) = picture
                    fun around(r: Rect) = Rect(r.x + box.x - world.x, r.y + box.y - world.y, r.width + box.width - world.width, r.height + box.height - world.height)
                    all += animator.frameItem(EffectKey("appearing", window), texture, WindowFrame(around(frame.rect), frame.alpha, frame.shader, around(frame.shaderFrom), around(frame.shaderTo), frame.progress))
                    return
                }
            } else {
                world = frame.rect
                alpha = frame.alpha * fade
            }
        }
        val rect = if (camera === DesktopOverview.Camera.NONE) world else camera.apply(world)
        animator.screenRects[window] = rect
        // While gliding (or zoomed out), the picture is stretched to the in-between size.
        val scaleX = if (target.width > 0) rect.width.toDouble() / target.width else 1.0
        val scaleY = if (target.height > 0) rect.height.toDouble() / target.height else 1.0
        val fx = looks.faded(if (window.fullscreen) Effects.NONE else userCode("Your windowEffects", effects) { windowEffects(window) }, alpha)
        // The compositor's title bar over it, if it has one: effects go around both.
        val bar = titleHeight(window).let { if (it > 0) Rect(rect.x, rect.y - (it * scaleY).roundToInt(), rect.width, (it * scaleY).roundToInt()) else null }
        val frame = bar?.union(rect) ?: rect
        // (A window of a scratchpad going keeps the look of the focus it had: border and glow fade with it.)
        val looksFocused = window === focusedWindow || (leaving && scratchpadOf(window)?.focusedAsItWent?.get() === window)
        val look = looks.addEffects(all, window, window.surface, visibleRect, frame, minOf(scaleX, scaleY), fx, focused = looksFocused)
        if (bar != null) all += DrawItem.Image(EffectKey("title", window), ownTitleBars.texture(window, bar), bar, look.clip, look.radius, alpha = alpha)
        addTree(all, window.surface, rect.x - (visibleRect.x * scaleX).roundToInt(), rect.y - (visibleRect.y * scaleY).roundToInt(), scaleX, scaleY, look)
        if (look.dim > 0f) all += DrawItem.Tint(EffectKey("dim", window), frame, look.radius, 0f, 0f, 0f, look.dim * alpha)
    }

    // Asks the director where each of its windows is now; kept for the pointer (lowest first).
    private fun placeDirected(now: Long): List<Pair<Window, WindowPlace>> {
        val d = director
        if (d == null) {
            places = emptyMap()
            return emptyList()
        }
        val placed = ArrayList<Pair<Window, WindowPlace>>()
        for (window in allWindows) {
            if (!window.mapped || !directed(window)) continue
            val place = try {
                d.place(window, now)
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.error("Your WindowDirector failed to place $window (it stays where it was)", e)
                val visible = window.visibleRect
                WindowPlace(window.x.toDouble(), window.y.toDouble(), visible.width.toDouble(), visible.height.toDouble())
            } ?: continue
            placed += window to place
        }
        placed.sortBy { it.second.z } // (stable: equal ones keep the desktop's order)
        places = placed.toMap(LinkedHashMap())
        return placed
    }

    // A director's window at [place]: its picture stretched into it, with its effects.
    private fun addDirected(all: MutableList<DrawItem>, window: Window, place: WindowPlace) {
        if (place.alpha <= 0f || place.width <= 0 || place.height <= 0) return
        val rect = place.rect
        // Its menus open from there, and it counts as on the monitor it is drawn on.
        window.x = rect.x
        window.y = rect.y
        animator.shownRects[window] = rect
        animator.screenRects[window] = rect
        val visible = window.visibleRect
        val scaleX = if (visible.width > 0) place.width / visible.width else 1.0
        val scaleY = if (visible.height > 0) place.height / visible.height else 1.0
        val scale = minOf(scaleX, scaleY)
        var fx = looks.faded(if (window.fullscreen) Effects.NONE else userCode("Your windowEffects", effects) { windowEffects(window) }, place.alpha)
        place.cornerRadius?.let { r -> fx = fx.copy(cornerRadius = if (scale > 0) Math.round(r / scale).toInt() else 0) }
        val look = looks.addEffects(all, window, window.surface, visible, rect, scale, fx, focused = window === focusedWindow)
        val clip = when {
            place.clip == null -> look.clip
            look.clip == null -> place.clip
            else -> look.clip.intersect(place.clip) ?: return
        }
        val clipped = DesktopLooks.Look(clip, look.radius, look.alpha, look.dim)
        widgetHost.addDecorations(all, window, place, above = false)
        addTree(all, window.surface, rect.x - (visible.x * scaleX).roundToInt(), rect.y - (visible.y * scaleY).roundToInt(), scaleX, scaleY, clipped)
        if (look.dim > 0f) all += DrawItem.Tint(EffectKey("dim", window), rect, look.radius, 0f, 0f, 0f, look.dim * place.alpha)
        widgetHost.addDecorations(all, window, place, above = true)
    }

    internal fun addTree(
        items: MutableList<DrawItem>, surface: Surface, x: Int, y: Int,
        scaleX: Double = 1.0, scaleY: Double = 1.0, look: DesktopLooks.Look = DesktopLooks.Look.PLAIN,
    ) {
        val scaled = scaleX != 1.0 || scaleY != 1.0
        for (child in surface.stack) {
            if (child === surface) {
                val texture = surface.texture ?: continue
                val rect = Rect(x, y, (surface.width * scaleX).roundToInt(), (surface.height * scaleY).roundToInt())
                // A stretched or faded picture hides nothing for sure (its edges move every frame).
                val covers = if (scaled || look.alpha < 1f) emptyList() else coversOf(surface.opaqueRects().map { it.translate(x, y) }, look)
                items += DrawItem.Picture(surface, texture, rect, surface.opaque, covers, look.alpha, look.clip, look.radius, surface.textureSource, surface.current.transform)
            } else {
                val sub = child.subsurface ?: continue
                addTree(items, child, x + (sub.x * scaleX).roundToInt(), y + (sub.y * scaleY).roundToInt(), scaleX, scaleY, look)
            }
        }
    }

    // What an opaque picture still hides when clipped with round corners: everything but the corners.
    private fun coversOf(opaque: List<Rect>, look: DesktopLooks.Look): List<Rect> {
        val clip = look.clip ?: return opaque
        val r = Math.ceil(look.radius.toDouble()).toInt()
        val wide = Rect(clip.x, clip.y + r, clip.width, clip.height - 2 * r)
        val tall = Rect(clip.x + r, clip.y, clip.width - 2 * r, clip.height)
        return opaque.flatMap { listOfNotNull(it.intersect(wide), it.intersect(tall)) }
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

    // Surfaces kept updating although they may be hidden (e.g. shared in a screen recording).
    private val keptUpdating = HashMap<Surface, Int>()

    /**
     * Keeps [surface] and its subsurfaces running at full speed even while nobody sees it,
     * for example while it is shared in a screen recording. Close the returned handle when
     * that ends. Without it, hidden programs only get a slow heartbeat (see [frameShown]).
     */
    fun keepUpdating(surface: Surface): AutoCloseable {
        keptUpdating[surface] = (keptUpdating[surface] ?: 0) + 1
        var open = true
        return AutoCloseable {
            if (!open) return@AutoCloseable
            open = false
            val count = (keptUpdating[surface] ?: 1) - 1
            if (count <= 0) keptUpdating.remove(surface) else keptUpdating[surface] = count
        }
    }

    internal fun isKeptUpdating(surface: Surface): Boolean {
        var current: Surface? = surface
        while (current != null) {
            if (current in keptUpdating) return true
            current = current.subsurface?.parent
        }
        return false
    }

    /**
     * Tells every program on screen (and those kept updating) that its last frame was
     * shown, so it can draw the next one. Called at every refresh, also when nothing had
     * to be redrawn. Hidden programs get a slow heartbeat instead: they do not freeze, but
     * they do not burn the computer's power on pictures nobody sees either. A hidden window
     * just asked for a new size is the exception: it draws that at once (a window manager
     * waits for it before showing the window, as newm does).
     */
    fun frameShown(timeMillis: Int) = frameShown(null, timeMillis)

    // The same after a frame of [view]: programs on screen hear of it from the monitor that
    // shows most of them (else a window on a 60 Hz monitor drew at the 144 Hz one's pace).
    private fun frameShown(view: DesktopView?, timeMillis: Int) {
        for (surface in visible) if (surface.resource.isAlive && (view == null || shownMostlyOn(surface, view.monitor))) surface.sendFrameDone(timeMillis)
        for (surface in compositor.surfaces) {
            if (surface !in visible && isKeptUpdating(surface)) surface.sendFrameDone(timeMillis)
        }
        for (window in allWindows) {
            if (!window.awaitsSize) continue
            for (surface in window.surface.stack) if (surface !in visible && surface.resource.isAlive) surface.sendFrameDone(timeMillis)
        }
        scheduleHiddenRefresh()
    }

    private var hiddenRefresh: EventLoop.Timer? = null

    // Hidden programs waiting for their next frame get it once per HIDDEN_REFRESH_MILLIS.
    private fun scheduleHiddenRefresh() {
        if (hiddenRefresh != null || hiddenWaiting().isEmpty()) return
        hiddenRefresh = compositor.display.loop.after(HIDDEN_REFRESH_MILLIS) {
            hiddenRefresh = null
            val time = (System.nanoTime() / 1_000_000).toInt()
            for (surface in hiddenWaiting()) {
                surface.sendFrameDone(time)
                // Its pictures were never shown, and will not be until it is visible again.
                for (feedback in surface.current.presentationFeedbacks) feedback.discard()
                surface.current.feedbackList.clear()
            }
            scheduleHiddenRefresh()
        }
    }

    private fun hiddenWaiting(): List<Surface> = compositor.surfaces.filter {
        it.current.frameCallbacks.isNotEmpty() && it !in visible && !isKeptUpdating(it)
    }

}
