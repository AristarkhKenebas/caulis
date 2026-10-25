package dev.caulis.core

import dev.caulis.log.Logger
import dev.caulis.core.cursor.CursorTheme
import dev.caulis.core.input.Seat
import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.render.Color
import dev.caulis.render.GpuTarget
import dev.caulis.render.GpuTexture
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
    private val seat: Seat,
    private val compositor: Compositor,
    screenWidth: Int,
    screenHeight: Int,
) : XdgShell.Listener, LayerShell.Listener, SessionLockManager.Listener, AutoCloseable {

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
            if (window != null) lastLookOf(window)?.let { look -> lastLooks.put(window, look)?.target?.close() }
        }
    }

    /**
     * Lets go of what the desktop keeps on the GPU (title bars, pictures of animations and
     * fades, backgrounds) and of its listeners. Call it when the compositor ends.
     */
    override fun close() {
        shaderSaved?.let { WatchedText.anyChange -= it }
        shaderSaved = null
        titleTextures.values.forEach { it.second.close() }
        titleTextures.clear()
        fades.keys.toList().forEach(::dropFade)
        flights.forEach { it.target.close() }
        flights.clear()
        lastLooks.values.forEach { it.target.close() }
        lastLooks.clear()
        appearingPictures.values.forEach { it.close() }
        appearingPictures.clear()
        backdrops.close()
        osd.close()
    }

    // --- monitors, size and scale ---------------------------------------------------------

    private val monitorList = ArrayList<Monitor>()

    // The backgrounds of the monitors (declared first: drawing asks for them).
    private val backdrops = Backdrops(renderer, compositor.display.loop, changed = {
        version++
        onChange()
    }, shaderWorks = { name, code, problem -> shaderWorks(name, code, problem) })

    // The compositor's own notices (declared first: arranging a monitor asks for their room).
    private val noticeBoard = NoticeBoard(
        renderer, compositor.display.loop,
        changed = {
            version++
            onChange()
        },
        roomChanged = {
            for (monitor in monitorList.toList()) arrangeLayers(monitor)
            version++
            onChange()
        },
    )

    /** The monitors showing the desktop, in the order they came (the first is the main one). */
    val monitors: List<Monitor> get() = monitorList

    /** The monitor with the focus: new windows open there, Super+N changes its workspace. */
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
        fadeFromNow(monitor) // the old picture melts into the new one instead of jumping
        monitor.scale = value
        monitor.output?.rescale(value)
        if (monitor === focusedMonitor) compositor.outputScale = value
        placeMonitors() // its size in units changed: the ones beside it move
        for (each in monitorList) monitorChanged(each)
        cursorChanged()
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
            for (view in views + captureViews) if (view.monitor === standIn) view.monitor = monitor
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
        if (standIn != null || !::focusedMonitor.isInitialized || focusedMonitor === standIn) focusedMonitor = monitor
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
        dropFade(monitor)
        backdrops.forget(monitor.name)
        if (monitorList.size == 1) {
            // The last one: it stays as the stand-in, so windows keep a place until a monitor comes.
            placeholder = monitor
            return
        }
        monitorList -= monitor
        val home = if (focusedMonitor === monitor) monitorList.first() else focusedMonitor
        layoutLog.info { "Monitor ${monitor.name} is gone; its windows stay on their workspaces (now on ${home.name})" }
        for (view in views + captureViews) if (view.monitor === monitor) view.monitor = home
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
                    arrange(shown)
                }
            }
        }
        // Panels on it close (programs like Waybar open new ones for the monitors there are).
        for (layer in layerSurfaces.filter { monitorOf(it) === monitor }.toList()) {
            layer.close()
            layerMonitors -= layer
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
            powerListeners.toList().forEach { it(monitor) }
        }
        return done
    }

    private val powerListeners = ArrayList<(Monitor) -> Unit>()

    /** Runs [listener] when a monitor's screen is switched off or on. */
    fun onPowerChanged(listener: (Monitor) -> Unit): AutoCloseable {
        powerListeners += listener
        return AutoCloseable { powerListeners -= listener }
    }

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
        arrangeLayers(monitor, refit = false)
        refitWindows(monitor)
        for (view in views + captureViews) if (view.monitor === monitor) view.damageEverything()
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
    private fun desktopBounds(): Rect = monitorList.map { it.area }.reduce(Rect::union)

    // The monitor a lock surface covers (its output's, else the first).
    private fun lockMonitor(surface: LockSurface): Monitor =
        surface.output?.let { output -> monitorList.firstOrNull { it.output === output } } ?: monitorList.first()

    // --- a scale change: the last frame melts into the new layout -------------------------

    // The picture each monitor showed before its scale changed, and when it began to fade.
    // A monitor's picture from before a scale change: its texture, since when, and what to
    // close when it is over (the picture it was drawn into).
    private class Fade(val texture: GpuTexture, val since: Long, val owner: AutoCloseable)

    private val fades = HashMap<Monitor, Fade>()

    private fun dropFade(monitor: Monitor) {
        fades.remove(monitor)?.owner?.close()
        fadeAlphas -= monitor
    }
    private val fadeAlphas = HashMap<Monitor, Float>()

    // Takes the picture on [monitor] now (as a screenshot does); it fades out over the new
    // scale, while programs draw themselves anew underneath. Without animations: no fade.
    private fun fadeFromNow(monitor: Monitor) {
        if (animationMillis <= 0 || views.none { it.monitor === monitor }) return
        val width = monitor.pixelWidth
        val height = monitor.pixelHeight
        if (width <= 0 || height <= 0) return
        dropFade(monitor)
        try {
            // Drawn as on screen, without our cursor (it would fade out as a ghost) and without
            // telling programs anything (their frames are on the real screen).
            val target = renderer.createTarget(width, height)
            try {
                renderer.begin(target, BACKGROUND[0], BACKGROUND[1], BACKGROUND[2])
                draw(captureItems(cursor = false, hide = false), monitor.area, monitor::toPixels, monitor.scale, monitor)
                renderer.end()
            } catch (e: Throwable) {
                target.close()
                throw e
            }
            val texture = target.texture
            if (texture != null) {
                fades[monitor] = Fade(texture, System.nanoTime(), target) // read where it is: no copy
            } else {
                target.use {
                    java.lang.foreign.Arena.ofConfined().use { arena ->
                        val pixels = arena.allocate(width.toLong() * height * 4)
                        target.readPixels(pixels, width * 4)
                        val copy = renderer.createTexture(width, height)
                        copy.upload(pixels, 0, height)
                        fades[monitor] = Fade(copy, System.nanoTime(), copy)
                    }
                }
            }
            fadeAlphas[monitor] = 1f
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            renderLog.warn("The picture before the scale change could not be kept (it changes without fading)", e)
        }
        version++
        onChange()
    }

    // True while some monitor fades: each frame is more of the new picture.
    private fun fading(): Boolean {
        val now = System.nanoTime()
        for ((monitor, fade) in fades.toList()) {
            val t = (now - fade.since) / 1_000_000.0 / animations.scaleChangeMillis.coerceAtLeast(1)
            if (t >= 1.0) {
                dropFade(monitor)
                version++
            } else {
                fadeAlphas[monitor] = (1 - t * t * (3 - 2 * t)).toFloat() // smooth at both ends
            }
        }
        return fades.isNotEmpty()
    }

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

    /** The monitor a window is on: the one its workspace is shown on (or was last). */
    fun monitorOf(window: Window): Monitor = workspaceOf(window)?.monitor ?: focusedMonitor

    /** Size of the monitor with the focus, in desktop units. */
    val width: Int get() = focusedMonitor.area.width
    val height: Int get() = focusedMonitor.area.height

    // A monitor changed size: maximized and fullscreen windows on it follow, layouts re-arrange.
    private fun refitWindows(monitor: Monitor) {
        lock?.surfaces?.filter { lockMonitor(it) === monitor }?.forEach { it.configure(monitor.area.width, monitor.area.height) }
        for (pad in shownPads) if (pad.monitor === monitor) placeScratchpad(pad, appearing = emptySet())
        for (workspace in workspaces.values) {
            if ((workspace.monitor ?: focusedMonitor) !== monitor) continue
            for (window in workspace.windows + workspace.minimized) {
                if (!window.configured) continue
                if (window.fullscreen) {
                    window.configure(monitor.area.width, monitor.area.height)
                    moveSmoothly(window, monitor.area.x, monitor.area.y)
                } else if (window.maximized) {
                    val area = withoutTitle(window, monitor.usableArea)
                    window.configure(area.width, area.height)
                    moveSmoothly(window, area.x, area.y)
                }
            }
            arrange(workspace)
        }
        changed()
    }

    /**
     * Called whenever something may have to be drawn again (a program drew, a window moved,
     * the pointer moved). The backend then asks for a frame on every screen.
     */
    var onChange: () -> Unit = {}

    private val views = ArrayList<DesktopView>()

    /** A view of [monitor] (the first one if not said), for drawing it on its screen. */
    fun createView(monitor: Monitor = monitorList.first()): DesktopView = DesktopView(monitor).also { views += it }

    fun removeView(view: DesktopView) {
        views -= view
        for (feedback in view.inFlight) feedback.discard()
        view.inFlight.clear()
        lockShownOn -= view
        confirmLockIfShown() // one screen less to wait for
    }

    init {
        // Every commit may change what is on screen.
        compositor.onNewSurface { surface ->
            surface.onCommit { surfaceCommitted(surface) }
            surface.resource.onDestroy { keptUpdating.remove(surface) }
        }
        seat.onCursorChange = ::changed
    }

    // Grows with every change; the list of things to draw is rebuilt only when it moved on.
    private var version = 0L

    private fun changed() {
        version++
        onChange()
        windowListChanged()
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
            for (workspace in workspaces.values) arrange(workspace)
            changed()
        }

    // True if the director places [window] itself.
    private fun directed(window: Window): Boolean {
        val d = director ?: return false
        return userCode("Your WindowDirector (manages)", false) { d.manages(window) }
    }

    private val drawings = java.util.EnumMap<DrawingLevel, MutableList<Drawing>>(DrawingLevel::class.java)

    /**
     * Draws [drawing] at [level] from now on (with the others there, in the order added);
     * close the result to take it away. It takes no input: clicks go through to what is below.
     */
    fun addDrawing(level: DrawingLevel, drawing: Drawing): AutoCloseable {
        drawings.getOrPut(level) { ArrayList() } += drawing
        changed()
        return AutoCloseable {
            drawings[level]?.remove(drawing)
            changed()
        }
    }

    // The drawings of [level], on every monitor.
    private fun addDrawings(all: MutableList<DrawItem>, level: DrawingLevel) {
        val list = drawings[level].orEmpty()
        if (list.isEmpty()) return addWidgets(all, level)
        val seconds = ((System.nanoTime() - paintStart) / 1e9).toFloat()
        for (monitor in monitorList) {
            for (drawing in list.toList()) {
                val canvas = OverlayCanvas(monitor, listOf("drawing", drawing, monitor.name), seconds)
                try {
                    drawing.draw(monitor, canvas)
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    log.error("Your drawing $drawing failed (it is left out)", e)
                    continue
                }
                if (canvas.movingPaint) paintsMoving = true
                all += canvas.items
            }
        }
        addWidgets(all, level)
    }

    private val widgets = ArrayList<Widget>()
    private var hoveredWidget: Widget? = null
    private var pressedWidget: PressedWidget? = null

    private class PressedWidget(val widget: Widget, val area: Rect, val monitor: Monitor)

    /** The [Widget] that has the keyboard now (see [focusWidget]), or null: a program has it. */
    var keyboardWidget: Widget? = null
        private set

    /**
     * Shows [widget] from now on (the last added is on top); close the result to take it
     * away. Its reserved room is kept free at once.
     */
    fun addWidget(widget: Widget): AutoCloseable {
        widgets += widget
        updateWidgets()
        return AutoCloseable {
            if (!widgets.remove(widget)) return@AutoCloseable
            if (hoveredWidget === widget) hoveredWidget = null
            if (pressedWidget?.widget === widget) pressedWidget = null
            if (keyboardWidget === widget) focusWidget(null)
            updateWidgets()
        }
    }

    /** The widgets changed their place or reserved room: windows and panels make way anew, and they are drawn again. */
    fun updateWidgets() {
        for (monitor in monitorList) arrangeLayers(monitor)
        changed()
    }

    /**
     * Gives the keyboard to [widget] (programs lose it meanwhile), or back to the focused window
     * (null). A click elsewhere, or another window getting the focus, ends it too.
     */
    fun focusWidget(widget: Widget?) {
        val old = keyboardWidget
        if (old === widget) return
        keyboardWidget = widget
        if (old != null) userCode("Your widget (keyboardFocus)", Unit) { old.keyboardFocus(false) }
        if (widget != null) {
            inputLog.info { "The keyboard goes to the widget $widget" }
            seat.focusKeyboard(null)
            userCode("Your widget (keyboardFocus)", Unit) { widget.keyboardFocus(true) }
        } else if (!locked) {
            seat.focusKeyboard(focusedWindow?.surface ?: layerFocus?.surface)
        }
        changed()
    }

    // The widget under ([x], [y]) at one of [levels], topmost first, and its area there.
    private fun widgetAt(x: Double, y: Double, levels: Set<DrawingLevel>): Pair<Widget, Rect>? {
        if (widgets.isEmpty() || locked) return null
        val monitor = monitorAt(x, y)
        for (widget in widgets.asReversed()) {
            if (widget.level !in levels) continue
            val area = userCode("Your widget (area)", null) { widget.area(monitor) } ?: continue
            if (x >= area.x && y >= area.y && x < area.right && y < area.bottom) return widget to area
        }
        return null
    }

    // The widgets of [level], on every monitor.
    private fun addWidgets(all: MutableList<DrawItem>, level: DrawingLevel) {
        if (widgets.isEmpty()) return
        val seconds = ((System.nanoTime() - paintStart) / 1e9).toFloat()
        for (monitor in monitorList) {
            for (widget in widgets.toList()) {
                if (widget.level != level) continue
                val area = userCode("Your widget (area)", null) { widget.area(monitor) } ?: continue
                val canvas = OverlayCanvas(monitor, listOf("widget", widget, monitor.name), seconds)
                try {
                    widget.draw(monitor, canvas, area)
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    log.error("Your widget $widget failed to draw (left out)", e)
                    continue
                }
                if (canvas.movingPaint) paintsMoving = true
                all += canvas.items
            }
        }
    }

    // The director's pictures with [window], below it or above it.
    private fun addDecorations(all: MutableList<DrawItem>, window: Window, place: WindowPlace, above: Boolean) {
        val d = director ?: return
        val canvas = OverlayCanvas(null, listOf("decorations", window, above), ((System.nanoTime() - paintStart) / 1e9).toFloat())
        try {
            d.decorate(window, place, canvas, above)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.error("Your WindowDirector failed to decorate $window (left out)", e)
            return
        }
        if (canvas.movingPaint) paintsMoving = true
        all += canvas.items
    }

    // Where the director showed each of its windows in the frame last built (what is on screen
    // now: drawn there, and found there by the pointer).
    private var places: Map<Window, WindowPlace> = emptyMap()

    override fun windowInfoChanged(window: Window) {
        windowsLog.trace { "Now called $window" }
        windowListChanged()
        if (window.mapped) notify(windowInfoListeners, window, "window-info-changed")
    }

    // --- a picture of the whole state, for finding problems ------------------------------------

    /**
     * Everything the window manager knows right now, as text: the screen, the focus, every
     * workspace with its windows (where, tiled or floating, minimized, ...), scratchpads,
     * panels, the lock. Log it when something looks wrong (Super+F12 in caulis-wm).
     */
    fun describe(): String = buildString {
        appendLine("Desktop ${width}x$height, room for windows $usableArea${if (locked) ", LOCKED" else ""}${if (inOverview) ", overview open" else ""}")
        appendLine("  focus: ${focusedWindow ?: "nothing"}; keyboard: ${seat.keyboardFocus?.roleObject ?: seat.keyboardFocus ?: "nobody"}; pointer at ${pointerX.toInt()},${pointerY.toInt()} over ${seat.pointerFocus?.roleObject ?: "nothing"}")
        appendLine("  cursor: ${cursorTheme.name} ${cursorTheme.size} px, showing ${cursorShape ?: "the program's own"}${if (cursorHidden) " (hidden)" else ""}")
        for (workspace in workspaces.values.sortedBy { it.number }) {
            appendLine("  workspace ${workspace.number}${if (workspace === activeWorkspace) " (on screen)" else ""}, layout ${workspace.layout.name}:")
            for (window in workspace.windows) appendLine("    ${describe(window, workspace)}")
            for (window in workspace.minimized) appendLine("    ${describe(window, workspace)} [minimized]")
        }
        for (pad in scratchpads.values) {
            appendLine("  scratchpad ${pad.name} (${if (pad.shown) "shown" else "hidden"}): ${pad.windows.joinToString { it.toString() }.ifEmpty { "no window" }}")
        }
        for (layer in layerSurfaces) {
            appendLine("  panel \"${layer.namespace}\" (${layer.surface.resource.client.name}) on ${layer.current.layer} at ${layer.x},${layer.y} ${layer.surface.width}x${layer.surface.height}${if (layer === layerFocus) ", has the keyboard" else ""}")
        }
        if (glides.isNotEmpty()) appendLine("  moving now: ${glides.keys.joinToString { it.title.take(24) }}")
        for (notice in notices) appendLine("  on screen: $notice")
    }

    private fun describe(window: Window, workspace: Workspace): String = buildString {
        append(window).append(" from ").append(window.surface.resource.client.name)
        append(" at ").append(screenRects[window] ?: currentRect(window))
        append(if (window in workspace.floating || !window.tiled) ", floating" else ", tiled")
        if (window.maximized) append(", maximized")
        if (window.fullscreen) append(", fullscreen")
        if (window.parent != null) append(", dialog of ").append(window.parent)
        if (window === focusedWindow) append(", focused")
    }

    // --- pictures for screenshots and screen sharing ----------------------------------------------

    // Views of screen recordings: what each has seen, so it gets only what changed since.
    private val captureViews = ArrayList<DesktopView>()
    internal val commitListeners = ArrayList<(Surface) -> Unit>()
    internal val frameListeners = ArrayList<() -> Unit>()

    /** A record of what a recording of [monitor] last got (see [captureChanges]). Remove it with [removeCaptureView]. */
    internal fun createCaptureView(monitor: Monitor): DesktopView = DesktopView(monitor).also { captureViews += it }

    internal fun removeCaptureView(view: DesktopView) {
        captureViews -= view
    }

    /**
     * What changed on the desktop since [view] last got a picture (everything the first
     * time), or null if nothing did. Counts as given: the next call starts from now.
     */
    internal fun captureChanges(view: DesktopView, cursor: Boolean): Rect? {
        compare(view, captureItems(cursor))
        return view.takeRepaint(1, view.monitor.area)
    }

    // Everything on screen, with or without our cursor; windows hidden from capture are
    // black boxes (their popups too).
    private fun captureItems(cursor: Boolean, hide: Boolean = true): List<DrawItem> {
        val all = items()
        val shown = if (cursor) all else all.subList(0, itemsCursorStart.coerceAtMost(all.size))
        if (!hide) return shown
        if (allWindows.none { behaviorOf(it).hiddenFromCapture } && layerSurfaces.none { layerBehaviorOf(it).hiddenFromCapture }) return shown
        return shown.map { item ->
            // Whose picture it is: a window's (or panel's) own, its title bar, its picture
            // flying or appearing in an animation.
            val owner = when {
                item is DrawItem.Picture -> ownerOf(item.surface)
                item.key is EffectKey -> (item.key as EffectKey).owner
                item.key is Flight -> (item.key as Flight).window
                else -> null
            }
            val hidden = when (owner) {
                is Window -> behaviorOf(owner).hiddenFromCapture
                is LayerSurface -> layerBehaviorOf(owner).hiddenFromCapture
                else -> false
            }
            if (hidden && (item is DrawItem.Picture || item is DrawItem.Image || item is DrawItem.Effect)) {
                DrawItem.Color(EffectKey("hidden from capture", item.key), item.rect, 0f, 0f, 0f, item.covers).also { it.only = item.only }
            } else {
                item
            }
        }
    }

    // The window or panel [surface] belongs to (it, a part of it, or one of its menus), if any.
    private fun ownerOf(surface: Surface): Any? {
        var current = surface
        repeat(16) {
            while (true) current = current.subsurface?.parent ?: break
            when (val role = current.roleObject) {
                is Window, is LayerSurface -> return role
                is Popup -> current = role.parent?.surface ?: role.layerParent?.surface ?: return null
                else -> return null
            }
        }
        return null
    }

    /**
     * Draws [monitor] as it is on screen into [target] (as big as the monitor in pixels),
     * with or without the [cursor]. For screenshots and screen sharing: what is on screen
     * does not change, and programs are not told about this picture.
     */
    fun capture(target: GpuTarget, cursor: Boolean, monitor: Monitor = monitorList.first()) {
        val items = captureItems(cursor)
        renderer.begin(target, BACKGROUND[0], BACKGROUND[1], BACKGROUND[2])
        draw(items, monitor.area, monitor::toPixels, monitor.scale, monitor)
        renderer.end()
    }

    /**
     * Draws [window] alone into [target] (as big as the window), see-through around it —
     * also when it is hidden or on another workspace. For sharing one window.
     */
    fun captureWindow(window: Window, target: GpuTarget) {
        val visible = window.visibleRect
        val items = ArrayList<DrawItem>()
        addTree(items, window.surface, -visible.x, -visible.y)
        val scale = monitorOf(window).scale
        renderer.begin(target, 0f, 0f, 0f, alpha = 0f)
        draw(items, null, { rect -> pixelsOf(rect, scale) }, scale)
        renderer.end()
    }

    /** The size [captureWindow] draws [window] at, in pixels (at the scale of its monitor). */
    fun windowPixelSize(window: Window): Pair<Int, Int> {
        val visible = window.visibleRect
        val scale = monitorOf(window).scale
        return Math.round(visible.width * scale).toInt().coerceAtLeast(1) to Math.round(visible.height * scale).toInt().coerceAtLeast(1)
    }

    // A rectangle in units (from 0, 0) as pixels of [scale].
    private fun pixelsOf(rect: Rect, scale: Double): Rect {
        fun p(v: Int) = Math.round(v * scale).toInt()
        return Rect(p(rect.x), p(rect.y), p(rect.right) - p(rect.x), p(rect.bottom) - p(rect.y))
    }

    /** Asks for a refresh soon (the backend then calls [refresh]), e.g. because a screenshot waits. */
    internal fun requestFrame() = onChange()

    // --- the window list (for docks, taskbars and your own code) --------------------------------

    /** Every window: on all workspaces (minimized ones too) and in scratchpads. */
    val allWindows: List<Window>
        get() = workspaces.values.flatMap { it.windows + it.minimized } + scratchpads.values.flatMap { it.windows }

    /** True if [window] is minimized (on any workspace). */
    fun isMinimized(window: Window): Boolean = workspaces.values.any { window in it.minimized }

    private val windowListListeners = ArrayList<() -> Unit>()
    private var windowListPending = false

    /**
     * Runs [listener] when the windows or what a window list shows of them changed: a window
     * opened or closed, its title, the focus, minimized, maximized, fullscreen. Many changes
     * at once (a whole batch from the programs) are one call, at the end of the batch.
     */
    fun onWindowListChanged(listener: () -> Unit): AutoCloseable {
        windowListListeners += listener
        return AutoCloseable { windowListListeners -= listener }
    }

    private fun windowListChanged() {
        if (windowListPending || windowListListeners.isEmpty()) return
        windowListPending = true
        compositor.display.loop.post {
            windowListPending = false
            for (listener in windowListListeners.toList()) {
                try {
                    listener()
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    log.error("A window list listener failed (ignored): $e", e)
                }
            }
        }
    }

    // Where each window goes when minimized: a rectangle in a surface (a dock's icon).
    private val minimizeTargets = HashMap<Window, Pair<Surface, Rect>>()

    /**
     * Where [window] goes when it is minimized (and comes back from): [rect] inside
     * [surface], e.g. its icon in a dock. Null for none (it then just sinks down). Docks set
     * this through the wlr-foreign-toplevel protocol.
     */
    fun setMinimizeTarget(window: Window, surface: Surface?, rect: Rect?) {
        if (surface == null || rect == null || rect.isEmpty) minimizeTargets -= window else minimizeTargets[window] = surface to rect
    }

    // The window's minimized place on screen: the dock icon, or a sliver at its bottom.
    private fun minimizedRect(window: Window, from: Rect): Rect {
        minimizeTargets[window]?.let { (surface, rect) ->
            surfaceOrigin(surface)?.let { (x, y) -> return rect.translate(x, y) }
        }
        val width = (from.width / 4).coerceAtLeast(1)
        val height = (from.height / 8).coerceAtLeast(1)
        return Rect(from.x + (from.width - width) / 2, from.bottom - height, width, height)
    }

    // Where a surface's top left corner is on screen (null if it is not shown).
    private fun surfaceOrigin(surface: Surface): Pair<Int, Int>? {
        if (!surface.resource.isAlive) return null
        var current = surface
        var dx = 0
        var dy = 0
        while (true) {
            val sub = current.subsurface ?: break
            dx += sub.x
            dy += sub.y
            current = sub.parent ?: return null
        }
        return when (val role = current.roleObject) {
            is LayerSurface -> (role.x + dx) to (role.y + dy)
            is Window -> screenRects[role]?.let { (it.x - role.visibleRect.x + dx) to (it.y - role.visibleRect.y + dy) }
            else -> null
        }
    }

    // --- events for your own code ------------------------------------------------------------

    private val windowOpenedListeners = ArrayList<(Window) -> Unit>()
    private val windowClosedListeners = ArrayList<(Window) -> Unit>()
    private val focusListeners = ArrayList<(Window?) -> Unit>()
    private val workspaceListeners = ArrayList<(Workspace) -> Unit>()
    private val windowInfoListeners = ArrayList<(Window) -> Unit>()

    /** Runs [listener] when a window's title or app id changes. Close the result to stop. */
    fun onWindowInfoChanged(listener: (Window) -> Unit): AutoCloseable {
        windowInfoListeners += listener
        return AutoCloseable { windowInfoListeners -= listener }
    }

    /** Runs [listener] when a window appears (after it got its place and the focus). Close the result to stop. */
    fun onWindowOpened(listener: (Window) -> Unit): AutoCloseable {
        windowOpenedListeners += listener
        return AutoCloseable { windowOpenedListeners -= listener }
    }

    /** Runs [listener] when a window goes away. Close the result to stop. */
    fun onWindowClosed(listener: (Window) -> Unit): AutoCloseable {
        windowClosedListeners += listener
        return AutoCloseable { windowClosedListeners -= listener }
    }

    /** Runs [listener] when another window gets the keyboard (null = none). Close the result to stop. */
    fun onFocusChanged(listener: (Window?) -> Unit): AutoCloseable {
        focusListeners += listener
        return AutoCloseable { focusListeners -= listener }
    }

    /** Runs [listener] when another workspace is shown. Close the result to stop. */
    fun onWorkspaceChanged(listener: (Workspace) -> Unit): AutoCloseable {
        workspaceListeners += listener
        return AutoCloseable { workspaceListeners -= listener }
    }

    // Calls listeners one by one; a mistake in one of them is reported and the rest go on
    // (your code must never be able to bring the whole desktop down).
    // A layout's places for [windows] (fewer: the rest are not placed, as Floating does); one
    // that fails leaves every window the whole [area] meanwhile.
    private fun arrangeSafely(workspace: Workspace, windows: List<Window>, area: Rect, focused: Window?): List<Rect> =
        userCode("The layout ${workspace.layout.name}", null) { workspace.model.arrange(windows, area, focused) } ?: windows.map { area }

    // Your code (a rule, a curve, a layout, effects): what it throws is logged and [fallback]
    // used, so one mistake never stops the drawing or the desktop.
    private inline fun <T> userCode(what: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (e: Throwable) {
        e.rethrowIfFatal()
        log.error("$what failed (ignored): $e", e)
        fallback
    }

    // A curve's point, guarded (a curve of yours may throw): straight on if it does.
    private fun Curve.safeAt(t: Double): Double = userCode("Your animation curve", t) { at(t) }

    // Floats or not: your floatRule, or (if it fails) the usual answer.
    private fun floats(window: Window): Boolean =
        userCode("Your floatRule", window.parent != null || window.fixedSize || window.prefersFloating) { floatRule(window) }

    private fun <T> notify(listeners: List<(T) -> Unit>, value: T, event: String) {
        for (listener in listeners.toList()) {
            try {
                listener(value)
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.error("Your $event handler failed (ignored): $e", e)
            }
        }
    }

    // The log, by area (see dev.caulis.log.Log.AREAS): CAULIS_LOG="1,focus=3" and so on.
    private val log = Logger("config")
    private val windowsLog = Logger("windows")
    private val focusLog = Logger("focus")
    private val inputLog = Logger("input")
    private val layoutLog = Logger("layout")
    private val layersLog = Logger("layers")
    private val lockLog = Logger("lock")
    private val cursorLog = Logger("cursor")
    private val renderLog = Logger("render")
    private val overlayLog = Logger("overlay")
    private var lastFocused: Window? = null

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
    private data class WorkspaceKey(val number: Int, val monitor: String?)

    private fun keyFor(number: Int, monitor: Monitor): WorkspaceKey = when {
        number == 0 -> WorkspaceKey(0, monitor.name)
        workspaceMode == WorkspaceMode.SPANNING || workspaceMode == WorkspaceMode.SEPARATE -> WorkspaceKey(number, monitor.name)
        else -> WorkspaceKey(number, null)
    }

    private val workspaces = java.util.TreeMap<WorkspaceKey, Workspace>(compareBy<WorkspaceKey> { it.number }.thenBy { it.monitor ?: "" })

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
    private fun workspace(number: Int, monitor: Monitor = focusedMonitor): Workspace {
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
    private fun homeOf(number: Int): Monitor? {
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
        arrange(to)
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
                arrange(other.workspace)
            }
            primary.workspace = numbered
            moveWorkspace(numbered, primary)
            numbered.lastMonitorName = primary.name
            mergeInto(own, numbered)
            arrange(numbered)
        }
        for (monitor in monitorList) {
            if (monitor !== primary && monitor.workspace.number > 0) {
                monitor.workspace = workspace(0, monitor)
                moveWorkspace(monitor.workspace, monitor)
                arrange(monitor.workspace)
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
        endGrab()
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
        focus(topWindow(activeWorkspace))
        layoutLog.info { "Workspace ${activeWorkspace.number} (${activeWorkspace.layout.name})${if (monitorList.size > 1) " on ${focusedMonitor.name}" else ""}" }
        notify(workspaceListeners, activeWorkspace, "workspace")
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
                window.fullscreen || window.maximized -> if (window.configured) applyState(window)
                !window.tiled -> {
                    window.x = (window.x + dx).coerceIn(monitor.area.x, (monitor.area.right - 50).coerceAtLeast(monitor.area.x))
                    window.y = (window.y + dy).coerceIn(monitor.area.y, (monitor.area.bottom - 50).coerceAtLeast(monitor.area.y))
                    floatingRects[window]?.let { floatingRects[window] = Rect(window.x, window.y, it.width, it.height) }
                }
            }
        }
    }

    // An empty workspace nobody sees goes away (unless it always exists), as one left empty does.
    private fun dropIfEmpty(workspace: Workspace) {
        if (workspace.isEmpty && workspace.number > 0 && ruleOf(workspace.number)?.persistent != true && monitorList.none { it.workspace === workspace }) {
            workspaces.values.remove(workspace)
        }
    }

    // [workspace] is shown on [on] now (the one shown there before is hidden, gone if empty).
    private fun show(workspace: Workspace, on: Monitor) {
        val previous = on.workspace
        if (previous === workspace) return
        if (millisOf(animations.workspaceSwitch) > 0 && !locked && !inOverview) {
            val direction = if (workspace.number >= previous.number) 1 else -1
            slides[on] = Slide(previous, workspace, direction, System.nanoTime())
        }
        // Pinned windows go along (they float, keeping their place on the monitor).
        for (window in previous.windows.filter { it in pinned }) {
            previous.remove(window)
            previous.model.forget(window)
            workspace.add(window)
            workspace.floating += window
        }
        on.workspace = workspace
        moveWorkspace(workspace, on)
        workspace.lastMonitorName = on.name
        if (workspaceMode == WorkspaceMode.PINNED && workspace.number > 0 && !onlyStandIn()) pinnedHomes.putIfAbsent(workspace.number, on.name)
        // Empty workspaces disappear once you leave them (never one on screen), unless they always exist.
        dropIfEmpty(previous)
        arrange(workspace)
        changed()
    }

    /** Gives the focus to [monitor] (its top window gets the keyboard). */
    fun focusMonitor(monitor: Monitor) {
        if (monitor !in monitorList) return
        setFocusedMonitor(monitor)
        focus(topWindow(monitor.workspace))
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
        if (window.fullscreen || window.maximized) applyState(window)
        focusMonitor(monitor)
        focus(window)
    }

    private fun setFocusedMonitor(monitor: Monitor) {
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

    private fun moveToWorkspace(window: Window, to: Workspace) {
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
            floatingRects[window]?.let { floatingRects[window] = Rect(window.x, window.y, it.width, it.height) }
        }
        from.remove(window)
        from.model.forget(window) // it lives on the other workspace now
        pending.remove(window)
        to.add(window)
        if (wasFloating) to.floating += window
        window.setActivated(false)
        arrange(from)
        arrange(to)
        if (from === activeWorkspace) focus(topWindow(from))
        dropIfEmpty(from)
        changed()
    }

    /** Uses [layout] on [workspace]; windows move to their new places right away. */
    fun setLayout(workspace: Workspace, layout: Layout) {
        layoutLog.info { "Workspace ${workspace.number}: layout ${workspace.layout.name} → ${layout.name}" }
        val wasTiled = workspace.tiledWindows().toSet()
        workspace.layout = layout
        // Windows leaving the layout get their own floating place back.
        for (window in wasTiled - workspace.tiledWindows().toSet()) untile(window)
        arrange(workspace)
        changed()
    }

    private fun workspaceOf(window: Window): Workspace? =
        workspaces.values.firstOrNull { window in it.windows || window in it.minimized }

    // --- panels, docks, bars, wallpapers (layer shell) -------------------------------------------

    private val layerSurfaces = ArrayList<LayerSurface>()

    /** Panels, docks, wallpapers, ... that are set up (configured), any layer. */
    val layers: List<LayerSurface> get() = layerSurfaces

    /**
     * The part of the monitor with the focus that panels and docks leave free (their
     * "exclusive zones"): windows are tiled and maximized in here. Each monitor has its own
     * ([Monitor.usableArea]).
     */
    val usableArea: Rect get() = focusedMonitor.usableArea

    // The monitor of each panel that did not ask for one (the one with the focus when it came).
    private val layerMonitors = HashMap<LayerSurface, Monitor>()

    /** The monitor [layer] is on: the one its program asked for, else the one it came on. */
    fun monitorOf(layer: LayerSurface): Monitor =
        layer.output?.let { output -> monitorList.firstOrNull { it.output === output } }
            ?: layerMonitors.getOrPut(layer) { focusedMonitor }

    // The panel with the keyboard (a launcher, or one clicked that wants it), if any.
    private var layerFocus: LayerSurface? = null

    override fun layerCommitted(layer: LayerSurface) {
        if (layer !in layerSurfaces) {
            layerSurfaces += layer
            layersLog.info { "Panel \"${layer.namespace}\" (${layer.surface.resource.client.name}) on the ${layer.current.layer} layer of ${monitorOf(layer).name}, anchors ${layer.current.anchor}, exclusive zone ${layer.current.exclusiveZone}" }
        }
        arrangeLayers(monitorOf(layer))
        updateLayerKeyboard()
        changed()
    }

    override fun layerUnmapped(layer: LayerSurface) {
        layersLog.info { "Panel \"${layer.namespace}\" is gone" }
        val monitor = monitorOf(layer)
        layerSurfaces -= layer
        layerMonitors -= layer
        popups.filter { it.layerParent === layer }.forEach { it.dismiss() }
        if (layerFocus === layer) {
            // It had the keyboard: back to the window (or the next launcher that wants it).
            layerFocus = null
            seat.focusKeyboard(focusedWindow?.surface)
        }
        arrangeLayers(monitor)
        updateLayerKeyboard()
        changed()
    }

    // Places the layer surfaces of [monitor], the way wlroots-based compositors do: first
    // those that reserve space (each takes it from its edge), then the others in what is left.
    private fun arrangeLayers(monitor: Monitor, refit: Boolean = true) {
        // Banners of our own notices take their room first (from panels too, or only from windows).
        val banner = noticeBoard.style.placement as? NoticePlacement.Banner
        fun room(kind: NoticePlacement.Banner.Room) = if (banner == null) 0 else noticeBoard.bannerRoom(monitor, monitorList, noticeFallback(monitor), kind)
        val pushAll = room(NoticePlacement.Banner.Room.PUSH_ALL)
        val pushWindows = room(NoticePlacement.Banner.Room.PUSH_WINDOWS)
        val bottom = banner?.bottom == true
        val full = withoutBanners(monitor.area, monitor, pushAll, bottom)
        var usable = full
        val ordered = layerSurfaces.filter { monitorOf(it) === monitor }.sortedByDescending { it.current.layer } // overlay first
        for (layer in ordered.filter { it.current.exclusiveZone > 0 }) {
            placeLayer(layer, full)
            usable = reserve(usable, layer)
        }
        for (layer in ordered.filter { it.current.exclusiveZone <= 0 }) {
            placeLayer(layer, if (layer.current.exclusiveZone < 0) full else usable)
        }
        // (Below the ones that push everything: those are nearest the edge.)
        if (pushWindows > 0) usable = withoutBanners(usable, monitor, pushAll + pushWindows, bottom)
        // Widgets keeping room at an edge (a bar of our own).
        for (widget in widgets) {
            val reserve = userCode("Your widget (reserve)", null) { widget.reserve(monitor) } ?: continue
            if (reserve.size <= 0) continue
            usable = when (reserve.edge) {
                Edge.TOP -> Rect(usable.x, usable.y + reserve.size, usable.width, (usable.height - reserve.size).coerceAtLeast(1))
                Edge.BOTTOM -> Rect(usable.x, usable.y, usable.width, (usable.height - reserve.size).coerceAtLeast(1))
                Edge.LEFT -> Rect(usable.x + reserve.size, usable.y, (usable.width - reserve.size).coerceAtLeast(1), usable.height)
                Edge.RIGHT -> Rect(usable.x, usable.y, (usable.width - reserve.size).coerceAtLeast(1), usable.height)
            }
        }
        if (usable != monitor.usableArea) {
            monitor.usableArea = usable
            if (refit) refitWindows(monitor)
        }
    }

    private fun placeLayer(layer: LayerSurface, bounds: Rect) {
        val s = layer.current
        val anchor = s.anchor
        fun has(edge: Int) = anchor and edge != 0
        val width = if (s.width == 0) bounds.width - s.marginLeft - s.marginRight else s.width
        val height = if (s.height == 0) bounds.height - s.marginTop - s.marginBottom else s.height
        val x = when {
            has(ANCHOR_LEFT) && !has(ANCHOR_RIGHT) -> bounds.x + s.marginLeft
            has(ANCHOR_RIGHT) && !has(ANCHOR_LEFT) -> bounds.right - width - s.marginRight
            else -> bounds.x + s.marginLeft + (bounds.width - s.marginLeft - s.marginRight - width) / 2
        }
        val y = when {
            has(ANCHOR_TOP) && !has(ANCHOR_BOTTOM) -> bounds.y + s.marginTop
            has(ANCHOR_BOTTOM) && !has(ANCHOR_TOP) -> bounds.bottom - height - s.marginBottom
            else -> bounds.y + s.marginTop + (bounds.height - s.marginTop - s.marginBottom - height) / 2
        }
        layer.configure(width.coerceAtLeast(1), height.coerceAtLeast(1))
        layer.x = x
        layer.y = y
    }

    // The area left after [layer] took its exclusive zone from its edge.
    private fun reserve(area: Rect, layer: LayerSurface): Rect {
        val s = layer.current
        val edge = when {
            s.exclusiveEdge != 0 -> s.exclusiveEdge
            // Anchored to one edge, or to one edge and both of its neighbours (a bar).
            s.anchor in setOf(ANCHOR_TOP, ANCHOR_TOP or ANCHOR_LEFT or ANCHOR_RIGHT) -> ANCHOR_TOP
            s.anchor in setOf(ANCHOR_BOTTOM, ANCHOR_BOTTOM or ANCHOR_LEFT or ANCHOR_RIGHT) -> ANCHOR_BOTTOM
            s.anchor in setOf(ANCHOR_LEFT, ANCHOR_LEFT or ANCHOR_TOP or ANCHOR_BOTTOM) -> ANCHOR_LEFT
            s.anchor in setOf(ANCHOR_RIGHT, ANCHOR_RIGHT or ANCHOR_TOP or ANCHOR_BOTTOM) -> ANCHOR_RIGHT
            else -> return area // anchored to opposite edges or a corner: reserves nothing
        }
        return when (edge) {
            ANCHOR_TOP -> (s.exclusiveZone + s.marginTop).let { Rect(area.x, area.y + it, area.width, (area.height - it).coerceAtLeast(1)) }
            ANCHOR_BOTTOM -> (s.exclusiveZone + s.marginBottom).let { Rect(area.x, area.y, area.width, (area.height - it).coerceAtLeast(1)) }
            ANCHOR_LEFT -> (s.exclusiveZone + s.marginLeft).let { Rect(area.x + it, area.y, (area.width - it).coerceAtLeast(1), area.height) }
            else -> (s.exclusiveZone + s.marginRight).let { Rect(area.x, area.y, (area.width - it).coerceAtLeast(1), area.height) }
        }
    }

    // A launcher that wants the keyboard (top or overlay layer) gets it and keeps it while open.
    private fun exclusiveLayer(): LayerSurface? = layerSurfaces.lastOrNull {
        it.mapped && it.current.keyboard == KEYBOARD_EXCLUSIVE && it.current.layer >= LayerShell.Layer.TOP
    }

    private fun updateLayerKeyboard() {
        val exclusive = exclusiveLayer()
        when {
            exclusive != null -> if (layerFocus !== exclusive) {
                layerFocus = exclusive
                seat.focusKeyboard(exclusive.surface)
            }
            layerFocus?.let { !it.mapped || it.current.keyboard == KEYBOARD_NONE } == true -> {
                // The panel that had the keyboard is gone or does not want it: back to the window.
                layerFocus = null
                seat.focusKeyboard(focusedWindow?.surface)
            }
        }
    }

    // --- session lock --------------------------------------------------------------------------

    /**
     * True while the session is locked (by a screen locker such as swaylock): nothing of the
     * desktop is shown, and keys and the pointer go to the locker only. Unlocking is the
     * locker's job (after the user typed the password); if it crashes, the session stays
     * locked and a newly started locker takes over.
     */
    var locked = false
        private set

    /** The colour behind the locker (and all there is until it has drawn). */
    var lockColor: Color = Color(0f, 0f, 0f)

    /** The colour shown when the locker crashed: start a new one (e.g. from a text console). */
    var abandonedLockColor: Color = Color(0.35f, 0.05f, 0.05f)

    // The locker in charge (it may have crashed).
    private var lock: SessionLock? = null

    // Waiting for every screen to show the locked picture before telling the locker.
    private var lockPending = false
    private val lockShownOn = HashSet<DesktopView>()

    private val lockListeners = ArrayList<(Boolean) -> Unit>()

    /** Runs [listener] when the session gets locked (true) or unlocked (false). */
    fun onLockChanged(listener: (Boolean) -> Unit): AutoCloseable {
        lockListeners += listener
        return AutoCloseable { lockListeners -= listener }
    }

    private fun lockSurfaces(): List<LockSurface> = lock?.surfaces?.filter { it.mapped } ?: emptyList()

    // Surfaces that may get input while locked: the current locker's (with their subsurfaces).
    private fun belongsToLocker(surface: Surface): Boolean {
        var current: Surface = surface
        while (true) current = current.subsurface?.parent ?: break
        val owner = current.roleObject as? LockSurface ?: return false
        return owner.lock === lock
    }

    override fun lockRequested(lock: SessionLock): Boolean {
        if (locked && this.lock?.alive == true) {
            lockLog.info { "${lock.resource.client.name} wants to lock, but another locker is in charge: refused" }
            return false
        }
        this.lock = lock
        val wasLocked = locked
        locked = true
        // Nothing may go on behind the locker: no menus, no overview, no dragging.
        grabs.toList().asReversed().forEach { it.dismiss() }
        grabs.clear()
        endGrab()
        if (inOverview) {
            inOverview = false
            cameraFrom = Camera.NONE
            cameraTo = Camera.NONE
        }
        closeOverlay()
        seat.mayFocus = ::belongsToLocker
        lockPending = true
        lockShownOn.clear()
        lockLog.info { "Locked by ${lock.resource.client.name}${if (wasLocked) " (taking over from a crashed locker)" else ""}: nothing of the desktop is shown now" }
        confirmLockIfShown()
        changed()
        if (!wasLocked) notify(lockListeners, true, "lock")
        return true
    }

    // The locker hears "locked" once every screen shows the locked picture.
    private fun confirmLockIfShown() {
        if (!lockPending || !views.all { it in lockShownOn }) return
        lockPending = false
        lockLog.info("Every screen shows the lock: telling the locker")
        lock?.confirmLocked()
    }

    override fun lockSurfaceSize(output: Output?): Pair<Int, Int> =
        (output?.let(::monitorOf) ?: monitorList.first()).area.let { it.width to it.height }

    override fun lockSurfaceCommitted(surface: LockSurface) {
        if (surface.lock !== lock) return // an earlier locker's leftovers
        if (surface.mapped && seat.keyboardFocus == null) seat.focusKeyboard(surface.surface)
        changed()
        // The pointer may be over it now.
        val hit = surfaceAt(pointerX, pointerY)
        seat.pointerMotion((System.nanoTime() / 1_000_000).toInt(), hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
    }

    override fun unlockRequested(lock: SessionLock) {
        if (lock !== this.lock) {
            lockLog.warn("An old locker (${lock.resource.client.name}) tried to unlock; ignored")
            return
        }
        lockLog.info { "Unlocked by ${lock.resource.client.name}" }
        this.lock = null
        locked = false
        lockPending = false
        seat.mayFocus = { true }
        seat.focusKeyboard(exclusiveLayer()?.surface ?: focusedWindow?.surface)
        changed()
        pointerMoved((System.nanoTime() / 1_000_000).toInt(), pointerX, pointerY)
        notify(lockListeners, false, "unlock")
    }

    override fun lockAbandoned(lock: SessionLock) {
        if (lock !== this.lock) return
        // The locker crashed: the session stays locked (that is the point of a lock).
        lockPending = false
        lockLog.warn("The screen locker quit without unlocking; the session stays locked until a new locker is started")
        changed()
    }

    // --- scratchpads (special workspaces) -----------------------------------------------------

    private val scratchpads = LinkedHashMap<String, Scratchpad>()

    // Scratchpads on screen (or leaving it), the last shown on top.
    private val shownPads = ArrayList<Scratchpad>()

    // The scratchpad window with the keyboard, if any.
    private var padFocus: Window? = null

    /**
     * A special workspace named [name]: windows for which [match] is true (e.g. a certain
     * app id) live in it, shown and hidden with [toggleScratchpad] above any workspace.
     */
    fun addScratchpad(name: String, style: Scratchpad.Style, match: (Window) -> Boolean): Scratchpad {
        require(name !in scratchpads) { "There is already a scratchpad named \"$name\"" }
        return Scratchpad(name, style, match).also { scratchpads[name] = it }
    }

    /** The scratchpad named [name], if there is one. */
    fun scratchpad(name: String): Scratchpad? = scratchpads[name]

    /** The scratchpad [window] lives in, if any. */
    fun scratchpadOf(window: Window): Scratchpad? = scratchpads.values.firstOrNull { window in it.windows }

    // The scratchpad a new window belongs to (by the scratchpads' rules).
    private fun scratchpadFor(window: Window): Scratchpad? =
        scratchpads.values.firstOrNull { pad -> userCode("Your scratchpad ${pad.name}'s match", false) { pad.match(window) } }

    /**
     * Shows the scratchpad [name], or hides it if it is shown. Returns false if it has no
     * windows yet — then start its program; its window appears as soon as it opens.
     */
    fun toggleScratchpad(name: String): Boolean {
        val pad = scratchpads[name] ?: return false
        if (pad.windows.isEmpty()) return false
        if (pad.shown && !pad.leaving) hideScratchpad(pad) else showScratchpad(pad, appearing = pad.windows.toSet())
        return true
    }

    /** Moves [window] from its workspace into the scratchpad [name] (and shows it). */
    fun moveToScratchpad(window: Window, name: String) {
        val pad = scratchpads[name] ?: return
        val from = workspaceOf(window) ?: return
        from.remove(window)
        from.model.forget(window)
        pending.remove(window)
        arrange(from)
        pad.windows += window
        showScratchpad(pad, appearing = emptySet())
    }

    // The monitor area a scratchpad is shown in.
    private fun padArea(pad: Scratchpad): Rect = (pad.monitor?.takeIf { it in monitorList } ?: focusedMonitor).area

    private fun showScratchpad(pad: Scratchpad, appearing: Set<Window>) {
        if (!pad.shown || pad.leaving) pad.monitor = focusedMonitor
        pad.shown = true
        pad.leaving = false
        shownPads -= pad
        shownPads += pad
        placeScratchpad(pad, appearing)
        focus(pad.windows.last())
    }

    private val padTimers = HashMap<Scratchpad, EventLoop.Timer>()

    private fun hideScratchpad(pad: Scratchpad) {
        pad.leaving = true
        val now = System.nanoTime()
        for (window in pad.windows) {
            val from = shownRects[window] ?: currentRect(window)
            glides[window] = Glide(from, now, to = pad.hiddenRect(from, padArea(pad)))
        }
        // (A hide before this one, shown again in between, must not end this one early.)
        padTimers.remove(pad)?.cancel()
        padTimers[pad] = compositor.display.loop.after(animationMillis) {
            padTimers -= pad
            if (!pad.leaving) return@after // shown again meanwhile
            pad.leaving = false
            pad.shown = false
            shownPads -= pad
            for (window in pad.windows) glides -= window
            if (padFocus != null && padFocus in pad.windows) focus(windows.lastOrNull())
            changed()
        }
        if (padFocus != null && padFocus in pad.windows) focus(windows.lastOrNull())
        changed()
    }

    // Where the scratchpad's windows go: its edge, the middle, or its own layout.
    private fun padTargets(pad: Scratchpad, extra: Window? = null): Map<Window, Rect> {
        val windows = pad.windows + listOfNotNull(extra)
        if (pad.style is Scratchpad.Overlay) {
            val rects = arrangeSafely(pad.workspace, windows, padArea(pad), windows.lastOrNull())
            return windows.zip(rects).associate { (window, rect) -> window to fit(window, rect) }
        }
        val rect = pad.shownRect(padArea(pad))
        return windows.associateWith { fit(it, rect) }
    }

    // Sizes and places the scratchpad's windows; [appearing] ones grow or slide into place.
    private fun placeScratchpad(pad: Scratchpad, appearing: Set<Window>) {
        val now = System.nanoTime()
        for ((window, rect) in padTargets(pad)) {
            window.tiled = pad.style is Scratchpad.Overlay
            val visibleRect = window.visibleRect
            if (visibleRect.width != rect.width || visibleRect.height != rect.height) window.configure(rect.width, rect.height)
            window.x = rect.x
            window.y = rect.y
            if (window in appearing && animationMillis > 0) {
                glides[window] = Glide(pad.hiddenRect(rect, padArea(pad)), now)
            }
        }
        changed()
    }

    // --- placing windows: layouts and transactions ---------------------------------------

    /**
     * Decides whether a new window floats instead of being tiled. By default: dialogs and
     * windows that cannot be resized. Replace it for your own rules (e.g. by app id).
     */
    var floatRule: (Window) -> Boolean = { it.parent != null || it.fixedSize || it.prefersFloating }

    private var cascade = 0

    // The free floating place of each window (also kept while it is tiled or maximized).
    private val floatingRects = HashMap<Window, Rect>()

    // New sizes asked for, positions applied together once every window has drawn in its
    // new size (or after a short wait): so neighbours never jump one after another.
    private val pending = HashMap<Window, Rect>()
    private var pendingTimer: EventLoop.Timer? = null

    // Puts the tiled windows of [workspace] where its layout wants them. With [extra], also
    // a window about to appear (to learn its size before its first frame) — without moving anyone.
    private fun arrange(workspace: Workspace, extra: Window? = null): Map<Window, Rect> {
        val tiled = workspace.tiledWindows() + listOfNotNull(extra)
        if (tiled.isEmpty()) return emptyMap()
        val top = workspace.windows.lastOrNull { it in tiled } ?: extra
        val area = (workspace.monitor?.takeIf { it in monitorList } ?: focusedMonitor).usableArea
        val rects = arrangeSafely(workspace, tiled, area, top)
        val targets = tiled.zip(rects).associate { (window, rect) -> window to fit(window, withMargins(window, rect, area)) }
        layoutLog.trace { "Arrange workspace ${workspace.number} (${workspace.layout.name}) in $area: " + targets.entries.joinToString { (w, r) -> "${w.title.take(24)}=$r" } }
        if (extra == null) place(targets)
        return targets
    }

    /**
     * Asks the active workspace's layout to do [command] for the focused window (move it,
     * resize it, preselect, pan the canvas, ...). Returns true if the layout did something;
     * windows then glide to their new places.
     */
    fun tilingCommand(command: TilingCommand): Boolean {
        val focused = focusedWindow?.takeIf { it.tiled }
        val done = activeWorkspace.model.command(command, focused)
        layoutLog.trace { "Tiling command $command for $focused: ${if (done) "done" else "not possible here"}" }
        // (Asked even when nothing moved: "focus the master" changes no place.)
        val wanted = activeWorkspace.model.takeFocus() as? Window
        if (wanted != null && wanted in activeWorkspace.windows) focus(wanted)
        if (!done) return wanted != null
        arrange(activeWorkspace)
        changed()
        return true
    }

    /**
     * Extra space around some windows inside their places in the layout (null: none), e.g.
     * from window rules. Asked whenever windows are arranged; after a change of its answer
     * for a window, call [marginsChanged].
     */
    var marginsFor: (Window) -> Margins? = { null }

    /** [window]'s margins may be different now: its workspace is arranged again. */
    fun marginsChanged(window: Window) {
        workspaceOf(window)?.let { arrange(it); changed() }
    }

    // [place] with [window]'s margins: smaller, or (negative) bigger — but inside [area] and at least 1 x 1.
    private fun withMargins(window: Window, place: Rect, area: Rect): Rect {
        val m = try {
            marginsFor(window)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.error("Your margins rule failed for $window (ignored): $e", e)
            null
        } ?: return place
        val left = maxOf(place.x + m.left, area.x)
        val top = maxOf(place.y + m.top, area.y)
        val right = minOf(place.right - m.right, area.right)
        val bottom = minOf(place.bottom - m.bottom, area.bottom)
        return Rect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    }

    // Respects the program's own size limits: a window that cannot be as big (or small) as
    // its tile is centred in it instead of being pushed off screen (R5).
    private fun fit(window: Window, place: Rect): Rect {
        val tile = withoutTitle(window, place) // the compositor's title bar takes the top of the tile
        val w = limit(tile.width, window.minWidth, window.maxWidth)
        val h = limit(tile.height, window.minHeight, window.maxHeight)
        return Rect(tile.x + (tile.width - w) / 2, tile.y + (tile.height - h) / 2, w, h)
    }

    private fun place(targets: Map<Window, Rect>) {
        for ((window, rect) in targets) {
            val visibleRect = window.visibleRect
            val becameTiled = !window.tiled
            window.tiled = true
            if (becameTiled || visibleRect.width != rect.width || visibleRect.height != rect.height) {
                window.configure(rect.width, rect.height)
            }
            pending[window] = rect
        }
        applyWhenReady()
    }

    // Applies all pending positions if every window has its new size by now; else waits a bit.
    private fun applyWhenReady() {
        if (pending.isEmpty()) return
        val ready = pending.all { (window, rect) ->
            val visibleRect = window.visibleRect
            visibleRect.width == rect.width && visibleRect.height == rect.height
        }
        if (ready) {
            applyPending()
        } else if (pendingTimer == null) {
            pendingTimer = compositor.display.loop.after(TRANSACTION_TIMEOUT_MILLIS) { applyPending() }
        }
    }

    private fun applyPending() {
        pendingTimer?.cancel()
        pendingTimer = null
        for ((window, rect) in pending) moveSmoothly(window, rect.x, rect.y)
        pending.clear()
        changed()
    }

    // --- overview: the whole workspace at a glance ------------------------------------------

    // What the screen shows of the desktop: screen = world × scale + (dx, dy).
    private class Camera(val scale: Double, val dx: Double, val dy: Double) {
        fun apply(r: Rect) = Rect(
            (r.x * scale + dx).roundToInt(), (r.y * scale + dy).roundToInt(),
            (r.width * scale).roundToInt().coerceAtLeast(1), (r.height * scale).roundToInt().coerceAtLeast(1),
        )

        fun mix(other: Camera, t: Double) = Camera(scale + (other.scale - scale) * t, dx + (other.dx - dx) * t, dy + (other.dy - dy) * t)

        companion object {
            val NONE = Camera(1.0, 0.0, 0.0)
        }
    }

    private var cameraFrom = Camera.NONE
    private var cameraTo = Camera.NONE
    private var cameraStart = 0L

    /** True while the overview shows the whole workspace. */
    var inOverview = false
        private set

    /**
     * Zooms out until every window of the workspace fits on screen — also those beyond the
     * edges of a canvas or a strip of columns — or back in. In the overview, a click on a
     * window brings it into view and focuses it; Escape or a click elsewhere just goes back.
     * Windows keep their sizes: only their pictures get smaller.
     */
    fun setOverview(on: Boolean) {
        if (on == inOverview || (on && locked)) return
        layoutLog.info { if (on) "Overview opens" else "Overview closes" }
        endGrab()
        val now = System.nanoTime()
        cameraFrom = cameraAt(now)
        cameraTo = if (on) overviewCamera() else Camera.NONE
        cameraStart = now
        inOverview = on
        // Programs do not see the pointer while the overview is open.
        if (on) seat.pointerMotion((now / 1_000_000).toInt(), null, 0.0, 0.0)
        changed()
    }

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

    private val cameraMillis: Long get() = (if (animationMillis == 0L) 0L else animations.overviewMillis ?: (animationMillis * 3 / 2)).coerceAtLeast(1)

    private fun cameraAt(now: Long): Camera {
        val t = (now - cameraStart) / (cameraMillis * 1_000_000.0)
        if (t >= 1.0 || animationMillis == 0L || animations.overviewMillis == 0L) return cameraTo
        return cameraFrom.mix(cameraTo, animations.curve.safeAt(t))
    }

    private fun cameraMoving(now: Long) = cameraTo !== cameraFrom && (now - cameraStart) < cameraMillis * 1_000_000

    // Fits all windows of the workspace (and its monitor) into 90% of the monitor with the focus.
    private fun overviewCamera(): Camera {
        val area = focusedMonitor.area
        var box = area
        for (window in activeWorkspace.windows) box = box.union(currentRect(window))
        val scale = minOf(1.0, area.width * overviewFill / box.width, area.height * overviewFill / box.height)
        return Camera(
            scale,
            area.x + area.width / 2.0 - (box.x + box.width / 2.0) * scale,
            area.y + area.height / 2.0 - (box.y + box.height / 2.0) * scale,
        )
    }

    // The overview's camera while a pinch opens or closes it (null = no pinch going on).
    private var overviewTarget: Camera? = null

    /**
     * Opens the overview part of the way ([progress] 0 = normal view, 1 = fully zoomed out),
     * following the fingers of a pinch. Finish with [endOverviewGesture].
     */
    fun setOverviewProgress(progress: Double) {
        if (locked) return
        val target = overviewTarget ?: overviewCamera().also { overviewTarget = it }
        val camera = Camera.NONE.mix(target, progress.coerceIn(0.0, 1.0))
        cameraFrom = camera
        cameraTo = camera // no animation: it follows the fingers exactly
        changed()
    }

    /** The fingers lifted: the overview finishes opening ([open]) or closing, smoothly from where it is. */
    fun endOverviewGesture(open: Boolean) {
        val now = System.nanoTime()
        val target = overviewTarget ?: overviewCamera()
        overviewTarget = null
        cameraFrom = cameraAt(now)
        cameraTo = if (open) target else Camera.NONE
        cameraStart = now
        inOverview = open
        if (open) seat.pointerMotion((now / 1_000_000).toInt(), null, 0.0, 0.0)
        changed()
    }

    // --- following the fingers --------------------------------------------------------------

    // How far the fingers dragged the view so far (tiled windows move along).
    private var dragX = 0.0
    private var dragY = 0.0

    /**
     * Moves the tiled windows of the workspace by ([dx], [dy]) pixels, right under the
     * fingers of a swipe. Finish with [endViewDrag].
     */
    fun dragView(dx: Double, dy: Double) {
        dragX += dx
        dragY += dy
        changed()
    }

    /**
     * The fingers lifted: the layout takes the new view, rounded to what fits (whole cells
     * on a canvas), and the windows glide there from where the fingers left them — or back,
     * if the layout cannot move its view. If the focused window went out of sight, the
     * window most in view gets the focus.
     */
    fun endViewDrag() {
        val dx = dragX.roundToInt()
        val dy = dragY.roundToInt()
        dragX = 0.0
        dragY = 0.0
        val workspace = activeWorkspace
        workspace.model.command(TilingCommand.PanBy(-dx, -dy), focusedWindow?.takeIf { it.tiled })
        arrange(workspace)
        val screen = focusedMonitor.area
        fun visibleShare(window: Window): Double {
            val target = pending[window] ?: currentRect(window)
            val inView = target.intersect(screen) ?: return 0.0
            return inView.width.toDouble() * inView.height / (target.width.toDouble() * target.height)
        }
        val focused = focusedWindow
        if (focused == null || (focused.tiled && visibleShare(focused) < 0.5)) {
            workspace.windows.filter { it.tiled }.maxByOrNull(::visibleShare)?.takeIf { visibleShare(it) > 0 }?.let(::focus)
        }
        changed()
    }

    // A click in the overview: on a window = go there; elsewhere = just leave.
    private fun overviewClick() {
        val camera = cameraAt(System.nanoTime())
        val point = Rect(pointerX.toInt(), pointerY.toInt(), 1, 1)
        val chosen = stackingOrder().asReversed().firstOrNull { camera.apply(currentRect(it)).contains(point) }
        setOverview(false)
        if (chosen != null) focus(chosen)
    }

    // --- surfaces that place themselves (X11 menus and tooltips) ---------------------------

    private val unmanaged = LinkedHashMap<Surface, Pair<Int, Int>>()

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
        seat.focusKeyboard(surface ?: exclusiveLayer()?.surface ?: focusedWindow?.surface)
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
            for ((_, cached) in titleTextures) cached.second.close()
            titleTextures.clear()
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

    private val osd = Osd(renderer)

    /** What the on-screen display says now ("Volume 45 %"), or null when it is not shown. */
    val onScreenDisplay: String? get() = osd.look?.title

    /**
     * Shows what a key changed on the on-screen display (a pill at the bottom of the focused
     * monitor): [title] ("Volume 45 %", "Touchpad off") and, with a [level] 0..1, a bar —
     * grey when [off] (muted). A new one replaces it at once; it goes by itself.
     */
    @JvmOverloads
    fun showLevel(title: String, level: Double? = null, off: Boolean = false) {
        osd.show(Osd.Look(title, level, off))
        changed()
    }

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
        if (locked) return
        closeOverlay()
        this.overlay = overlay
        endGrab()
        overlayLog.info { "Open: $overlay" }
        seat.pointerMotion(0, null, 0.0, 0.0) // the program under the pointer loses it
        overlay.pointerMoved(pointerX, pointerY)
        changed()
        cursorChanged()
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
        cursorChanged()
    }

    /** The [overlay] looks different now: drawn again. */
    fun redrawOverlay() {
        if (overlay != null) changed()
    }

    // An overlay that failed to draw (closed before the next frame).
    private var brokenOverlay: Overlay? = null

    // Runs [action] on the overlay; one that fails is closed (never the compositor with it).
    private inline fun withOverlay(action: (Overlay) -> Unit): Boolean {
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

    /** The notices open now, oldest first. */
    val notices: List<Notice> get() = noticeBoard.all.filter { it.open }

    /** Runs [listener] for every new notice (also with [NoticePlacement.Hidden]). Close the result to stop. */
    fun onNotice(listener: (Notice) -> Unit): AutoCloseable {
        noticeBoard.listeners += listener
        return AutoCloseable { noticeBoard.listeners -= listener }
    }

    // Where notices go that belong to no monitor there is now.
    private fun noticeFallback(monitor: Monitor) = if (this::focusedMonitor.isInitialized) focusedMonitor else monitor

    // [rect] without the strip banners take at the edge of [monitor].
    private fun withoutBanners(rect: Rect, monitor: Monitor, room: Int, bottom: Boolean): Rect {
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
    private fun withoutTitle(window: Window, area: Rect): Rect {
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
        if (window.maximized && !window.fullscreen) applyState(window) else arrange(workspace)
        changed()
    }

    // Each window's drawn title bar and what it showed (redrawn only when that changes).
    private val titleTextures = HashMap<Window, Pair<Any, GpuTexture>>()
    private var titleHover: Pair<Window, TitleButton?>? = null
    private var titlePressed: Pair<Window, TitleButton>? = null

    private fun titleTexture(window: Window, bar: Rect): GpuTexture {
        // At the scale of the window's monitor.
        val scale = monitorOf(window).scale
        fun px(value: Int) = Math.round(value * scale).toInt()
        val pixels = pixelsOf(Rect(0, 0, bar.width, titleBarStyle.height), scale)
        val hovered = titleHover?.takeIf { it.first === window }?.second
        val pressed = titlePressed?.takeIf { it.first === window }?.second
        val key = listOf(window.title, window.appId, window === focusedWindow, window.maximized, hovered, pressed, pixels.width, pixels.height, scale)
        titleTextures[window]?.let { (shown, texture) -> if (shown == key) return texture else texture.close() }
        val look = TitleBarLook(
            pixels.width, pixels.height, scale, window.title, window.appId, window === focusedWindow, window.maximized, hovered, pressed,
            titleBarStyle.buttonPlaces(bar.width).map { (button, range) -> button to (px(range.first) until px(range.last + 1)) },
            titleBarStyle,
        )
        val image = java.awt.image.BufferedImage(pixels.width.coerceAtLeast(1), pixels.height.coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            titleBarStyle.painter.paint(g, look)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.error("Drawing a title bar failed (your TitleBarPainter?)", e)
        } finally {
            g.dispose()
        }
        val texture = renderer.createTexture(image.width, image.height)
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
            texture.upload(arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, *data), 0, image.height)
        }
        titleTextures[window] = key to texture
        return texture
    }

    // What of a compositor title bar (or a floating window's edge) is at a point.
    private sealed class Decoration(val window: Window) {
        class Bar(window: Window, val button: TitleButton?) : Decoration(window)
        class Edge(window: Window, val edges: Int) : Decoration(window)
    }

    private fun barAt(window: Window, x: Double, y: Double): Decoration? {
        val content = screenRects[window] ?: return null
        val height = titleHeight(window)
        if (height > 0 && x >= content.x && x < content.right && y >= content.y - height && y < content.y) {
            val button = titleBarStyle.buttonPlaces(content.width).firstOrNull { (_, range) -> (x - content.x).toInt() in range }?.first
            return Decoration.Bar(window, button)
        }
        // Floating windows with our title bar can be resized at their edges.
        if (height > 0 && !window.tiled && !window.maximized) {
            val frame = Rect(content.x, content.y - height, content.width, content.height + height)
            val b = resizeBorder
            if (x >= frame.x - b && x < frame.right + b && y >= frame.y - b && y < frame.bottom + b && !(x >= frame.x && x < frame.right && y >= frame.y && y < frame.bottom)) {
                var edges = 0
                if (x < frame.x) edges = edges or XdgToplevel.ResizeEdge.LEFT
                if (x >= frame.right) edges = edges or XdgToplevel.ResizeEdge.RIGHT
                if (y < frame.y) edges = edges or XdgToplevel.ResizeEdge.TOP
                if (y >= frame.bottom) edges = edges or XdgToplevel.ResizeEdge.BOTTOM
                return Decoration.Edge(window, edges)
            }
        }
        return null
    }

    private var lastBarClick = 0L

    // A press or release on a title bar: buttons, dragging, double click. True if it was ours.
    private fun barButton(button: Int, pressed: Boolean): Boolean {
        val held = titlePressed
        if (!pressed) {
            if (held == null) return false
            titlePressed = null
            // A button acts when let go over it.
            val over = decorationAt(pointerX, pointerY) as? Decoration.Bar
            if (over != null && over.window === held.first && over.button == held.second) {
                when (held.second) {
                    TitleButton.CLOSE -> held.first.close()
                    TitleButton.MAXIMIZE -> setMaximized(held.first, !held.first.maximized)
                    TitleButton.MINIMIZE -> minimize(held.first)
                }
            }
            changed()
            return true
        }
        if (button != BTN_LEFT) return decorationAt(pointerX, pointerY) != null
        when (val at = decorationAt(pointerX, pointerY) ?: return false) {
            is Decoration.Bar -> {
                focus(at.window)
                val tapped = at.button
                if (tapped != null) {
                    titlePressed = at.window to tapped
                } else {
                    val now = System.nanoTime()
                    if (now - lastBarClick < doubleClickMillis * 1_000_000) {
                        setMaximized(at.window, !at.window.maximized)
                        lastBarClick = 0
                    } else {
                        lastBarClick = now
                        beginMove(at.window)
                    }
                }
            }
            is Decoration.Edge -> {
                focus(at.window)
                beginResize(at.window, at.edges)
            }
        }
        changed()
        return true
    }

    // The topmost title bar or edge at a point, if no program's window is above it there.
    private fun decorationAt(x: Double, y: Double): Decoration? {
        if (locked || inOverview) return null
        val (fullscreen, normal) = stackingOrder().partition { it.fullscreen }
        if (fullscreen.any { windowHit(it, x, y) != null }) return null
        for (window in normal.asReversed()) {
            if (windowHit(window, x, y) != null) return null // the window's own picture is on top
            barAt(window, x, y)?.let { return it }
        }
        return null
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

    private fun layerBehaviorOf(layer: LayerSurface): LayerBehavior = try {
        layerBehaviorFor(layer)
    } catch (e: Throwable) {
        e.rethrowIfFatal()
        log.error("Your panel rule failed for ${layer.namespace} (ignored): $e", e)
        LayerBehavior.USUAL
    }

    // How one window or panel is drawn: its picture clipped and faded, effects below it.
    // [dim]: how much darker it is drawn (a window without the focus, see Effects.dimInactive).
    private class Look(val clip: Rect?, val radius: Float, val alpha: Float, val dim: Float = 0f) {
        companion object {
            val PLAIN = Look(null, 0f, 1f)
        }
    }

    // Whether each window (or panel) had the focus, and since when (for the change of its look).
    private val focusLooks = java.util.WeakHashMap<Any, Pair<Boolean, Long>>()

    // Set while building the scene: a border or shadow changes by itself (a turning gradient,
    // a shader using time) or after a focus change — frames go on.
    private var paintsMoving = false
    private var focusChanging = false
    private val paintStart = System.nanoTime()

    // The time a moving paint is drawn at (0 for one that does not move).
    private fun paintTime(paint: dev.caulis.render.Paint): Float {
        if (!paint.moving) return 0f
        paintsMoving = true
        return ((System.nanoTime() - paintStart) / 1e9).toFloat()
    }

    // Adds the effects that go below [owner]'s picture at [rect] (on screen, zoomed by [scale]),
    // and returns how the picture itself is drawn.
    private fun addEffects(all: MutableList<DrawItem>, owner: Any, surface: Surface, local: Rect, rect: Rect, scale: Double, fx: Effects, focused: Boolean): Look {
        if (fx == Effects.NONE || rect.isEmpty) return Look.PLAIN
        val radius = (fx.cornerRadius * scale).toFloat()
        val alpha = if (focused) fx.opacity else fx.inactiveOpacity
        // When the focus moves, the look before fades into the new one.
        val now = System.nanoTime()
        val change = focusLooks[owner]
        if (change == null || change.first != focused) focusLooks[owner] = focused to if (change == null) 0L else now
        val e = animationProgress(focusLooks.getValue(owner).second, fx.focusChangeMillis, now)?.let { animations.curve.safeAt(it).toFloat().coerceIn(0f, 1f) }
        if (e != null) focusChanging = true
        fun shadowOf(focus: Boolean) = (if (focus) fx.shadow else fx.inactiveShadow ?: fx.shadow)?.takeIf { it != Shadow.NONE }
        fun addShadow(shadow: Shadow, alpha: Float, key: String) {
            val spread = (shadow.spread * scale).roundToInt()
            val box = Rect(rect.x - spread, rect.y - spread, rect.width + 2 * spread, rect.height + 2 * spread)
                .translate((shadow.offsetX * scale).roundToInt(), (shadow.offsetY * scale).roundToInt())
            all += DrawItem.Shadow(EffectKey(key, owner), box, radius + spread, (shadow.softness * scale).toFloat().coerceAtLeast(1f), shadow.color, rect, radius, alpha, paintTime(shadow.color))
        }
        if (e != null) shadowOf(!focused)?.let { addShadow(it, 1 - e, "shadow before") }
        shadowOf(focused)?.let { addShadow(it, e ?: 1f, "shadow") }
        fx.blur?.let { blur ->
            // Only worth it where one can see through the window.
            val seeThrough = alpha < 1f || surface.opaqueRects().none { it.contains(local) }
            if (seeThrough) all += DrawItem.Blur(EffectKey("blur", owner), rect, radius, (blur.size * scale).roundToInt().coerceAtLeast(1), blur.passes, blur.tone)
        }
        fx.border?.let { border ->
            val width = (border.width * scale).roundToInt().coerceAtLeast(1)
            val outer = Rect(rect.x - width, rect.y - width, rect.width + 2 * width, rect.height + 2 * width)
            fun paintOf(focus: Boolean) = if (focus) border.focused else border.unfocused
            // One pixel more, under the window's edge: its soft edge then blends into the border, not the background.
            val round = if (radius > 0f) radius + width else 0f
            // The border before stays below until the new one is all there.
            if (e != null) all += DrawItem.Border(EffectKey("border before", owner), outer, round, width + 1f, paintOf(!focused), 1f, paintTime(paintOf(!focused)))
            all += DrawItem.Border(EffectKey("border", owner), outer, round, width + 1f, paintOf(focused), e ?: 1f, paintTime(paintOf(focused)))
        }
        fun dimOf(focus: Boolean) = if (focus) 0f else fx.dimInactive
        val dim = if (e != null) dimOf(!focused) + (dimOf(focused) - dimOf(!focused)) * e else dimOf(focused)
        return Look(if (radius > 0f) rect else null, radius, alpha, dim)
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
        }

    /**
     * How windows appear and go away and how workspaces change (see [Animations]); how long
     * they take is [animationMillis] unless [Animations] says otherwise.
     */
    var animations: Animations = Animations()
        set(value) {
            field = value
            changed()
        }

    // How long an animation takes and along which curve (its own, or the usual).
    private fun millisOf(animation: WindowAnimation) = if (animationMillis == 0L) 0L else animation.millis ?: curveOf(animation).naturalMillis ?: animationMillis
    private fun millisOf(animation: WorkspaceAnimation) = if (animationMillis == 0L) 0L else animation.millis ?: curveOf(animation).naturalMillis ?: animationMillis
    private fun curveOf(animation: WindowAnimation) = animation.curve ?: animations.curve
    private fun curveOf(animation: WorkspaceAnimation) = animation.curve ?: animations.curve

    // How much of an animation's time has passed (0 to 1), or null once it is over (or off).
    private fun animationProgress(start: Long, millis: Long, now: Long): Double? {
        if (millis <= 0) return null
        val t = (now - start) / (millis * 1_000_000.0)
        return if (t >= 1.0) null else t.coerceAtLeast(0.0)
    }

    // Windows appearing, since when (and their picture, when a shader draws them).
    private val appearing = HashMap<Window, Long>()
    private val appearingPictures = HashMap<Window, GpuTarget>()

    // A picture of a window on its way — going away (its last look), into its dock icon or
    // back out of it — drawn by an animation from [from] to [to].
    private class Flight(
        val target: GpuTarget, val texture: GpuTexture, val from: Rect, val to: Rect, val start: Long,
        val workspace: Workspace, val animation: WindowAnimation, val millis: Long, val curve: Curve,
        val kind: FlightKind, val window: Window?,
    )

    private enum class FlightKind { CLOSE, MINIMIZE, RESTORE }

    private val flights = ArrayList<Flight>()

    // Last looks taken just before a window's picture went (it may go away next).
    private val lastLooks = HashMap<Window, Flight>()

    // A monitor changing its workspace: the one before goes out, the new one comes in.
    private class Slide(val from: Workspace, val to: Workspace, val direction: Int, val start: Long)

    private val slides = HashMap<Monitor, Slide>()

    // True while windows appear, go away or fly, or workspaces change: each frame is different.
    private fun animating() = appearing.isNotEmpty() || flights.isNotEmpty() || slides.isNotEmpty()

    // A picture of [window] as it looks now (with its title bar, border and shadow), and where
    // it is: for animations that move the picture rather than the window. Null if it is not drawn.
    private fun pictureOf(window: Window): Triple<GpuTarget, GpuTexture, Rect>? {
        val items = ArrayList<DrawItem>()
        addWindow(items, window, System.nanoTime(), plain = true)
        val parts = items.filter { it !is DrawItem.Blur } // (blur needs what is behind: not in a picture of its own)
        if (parts.none { it is DrawItem.Picture }) return null
        val box = parts.map { (it as? DrawItem.Shadow)?.box?.union(it.rect) ?: it.rect }.reduce(Rect::union)
        val scale = monitorOf(window).scale
        fun px(units: Int) = (units * scale).roundToInt()
        return try {
            val target = renderer.createTarget(px(box.width).coerceIn(1, 8192), px(box.height).coerceIn(1, 8192))
            val texture = target.texture ?: run {
                target.close()
                return null
            }
            renderer.begin(target, 0f, 0f, 0f, alpha = 0f)
            draw(parts, null, { r -> Rect(px(r.x - box.x), px(r.y - box.y), px(r.width).coerceAtLeast(1), px(r.height).coerceAtLeast(1)) }, scale)
            renderer.end()
            Triple(target, texture, box)
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            renderLog.warn("A picture of $window for its animation could not be made (it changes without one): $e")
            null
        }
    }

    // [window] on its way as a picture (see Flight), if it is on screen and the animation is on.
    private fun flightOf(window: Window, kind: FlightKind, animation: WindowAnimation, to: Rect? = null, workspace: Workspace? = workspaceOf(window)): Flight? {
        val millis = millisOf(animation)
        if (millis <= 0 || locked || inOverview || workspace == null || monitorList.none { it.workspace === workspace } || scratchpadOf(window) != null) return null
        if (!behaviorOf(window).animated) return null
        val (target, texture, box) = pictureOf(window) ?: return null
        return Flight(target, texture, box, to ?: box, System.nanoTime(), workspace, animation, millis, curveOf(animation), kind, window)
    }

    // A window's pictures on their way are let go (it does something else now).
    private fun stopFlights(window: Window) {
        for (flight in flights.filter { it.window === window }) {
            flights -= flight
            flight.target.close()
        }
    }

    // The last look of a window that is going away (taken while its picture is still there).
    private fun lastLookOf(window: Window): Flight? {
        val workspace = workspaceOf(window) ?: return null
        if (window !in workspace.windows) return null
        return flightOf(window, FlightKind.CLOSE, animations.windowClose, workspace = workspace)
    }

    // How a flight is drawn now (null once it is over).
    private fun flightItem(flight: Flight, now: Long): DrawItem? {
        val t = animationProgress(flight.start, flight.millis, now) ?: return null
        val e = flight.curve.safeAt(t)
        // Going away and coming back out of the dock run backwards (1 → 0).
        val progress = if (flight.kind == FlightKind.MINIMIZE) e else 1 - e
        val area = monitorList.firstOrNull { it.workspace === flight.workspace }?.usableArea ?: focusedMonitor.usableArea
        val frame = try {
            flight.animation.frame(Motion(flight.window, flight.from, flight.to, progress, t, area))
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.error("Your window animation failed (it is drawn without): $e", e)
            WindowFrame(flight.to)
        }
        return frameItem(flight, flight.texture, frame)
    }

    // The item that draws [texture] as [frame] says (through its shader, if it has one).
    private fun frameItem(key: Any, texture: GpuTexture, frame: WindowFrame): DrawItem {
        val shader = frame.shader ?: return DrawItem.Image(key, texture, frame.rect, alpha = frame.alpha)
        val box = frame.rect
        fun part(r: Rect) = floatArrayOf(
            (r.x - box.x).toFloat() / box.width, (r.y - box.y).toFloat() / box.height,
            r.width.toFloat() / box.width, r.height.toFloat() / box.height,
        )
        return DrawItem.Effect(
            key, texture, box, frame.alpha, shader, part(frame.shaderFrom), part(frame.shaderTo), frame.progress.toFloat(),
            fallback = Motion.mix(frame.shaderFrom, frame.shaderTo, frame.progress),
        )
    }

    // A paint was drawn (or its shader did not compile): said like other shaders.
    private fun paintWorks(paint: dev.caulis.render.Paint, drawn: Boolean) {
        if (paint !is dev.caulis.render.Paint.Shader) return
        val code = paint.code
        shaderWorks(paint.name, code, if (drawn) null else renderer.effectProblem(code) ?: "it does not compile")
    }

    // Shaders that do not compile now, by name: their code then, and the notice that says so.
    private val shaderNotices = HashMap<String, Pair<String, Notice>>()

    // A shader was used: a notice says what is wrong with it (once per version of its code),
    // and goes once a new version works.
    internal fun shaderWorks(name: String, code: String, problem: String?) {
        val known = shaderNotices[name]
        if (problem == null) {
            if (known != null && known.first != code) {
                dismiss(known.second)
                shaderNotices -= name
                renderLog.info { "$name works now" }
            }
            return
        }
        if (known?.first == code) return
        known?.let { dismiss(it.second) }
        renderLog.warn("$name does not compile (drawn without it): $problem")
        shaderNotices[name] = code to notify(readableShaderProblem(problem), NoticeLevel.ERROR, "$name does not compile")
    }

    // "Shader failed to compile: 0:15(2): error: ..." → "line 15: ...".
    private fun readableShaderProblem(problem: String): String =
        problem.removePrefix("Shader failed to compile: ").lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            Regex("""^\d+:(\d+)\(\d+\): (?:error: )?(.*)$""").find(line)?.let { "line ${it.groupValues[1]}: ${it.groupValues[2]}" } ?: line
        }.take(4).joinToString("\n")

    // [fx] at [alpha] of its strength (a window fading in or out).
    private fun faded(fx: Effects, alpha: Float): Effects {
        if (alpha >= 1f) return fx
        if (fx == Effects.NONE) return Effects(opacity = alpha, inactiveOpacity = alpha)
        return fx.copy(
            opacity = fx.opacity * alpha,
            inactiveOpacity = fx.inactiveOpacity * alpha,
            shadow = fx.shadow?.let { it.copy(color = it.color.faded(alpha)) },
            inactiveShadow = fx.inactiveShadow?.takeIf { it != Shadow.NONE }?.let { it.copy(color = it.color.faded(alpha)) } ?: fx.inactiveShadow,
            border = fx.border?.let { it.copy(focused = it.focused.faded(alpha), unfocused = it.unfocused.faded(alpha)) },
            blur = null,
        )
    }

    // A window on its way: where it started; the end is wherever the window is now.
    private class Glide(val from: Rect, val startNanos: Long, val to: Rect? = null)

    private val glides = HashMap<Window, Glide>()

    // Where each window was drawn last (with any glide), so a new glide starts there.
    private val shownRects = HashMap<Window, Rect>()

    // Where each window appears on screen (with the overview's zoom).
    private val screenRects = HashMap<Window, Rect>()

    /** Where [window] appears on screen right now, also zoomed out in the overview (null = not drawn yet). */
    fun screenRect(window: Window): Rect? = screenRects[window]

    /**
     * Where [window] is drawn right now — during a glide, somewhere between its old and new
     * place (null if it has not been drawn yet).
     */
    fun shownRect(window: Window): Rect? = shownRects[window]

    // Moves [window] to ([x], [y]); if it was on screen, it glides there instead of jumping.
    private fun moveSmoothly(window: Window, x: Int, y: Int) {
        val shown = shownRects[window]
        window.x = x
        window.y = y
        if (animationMillis > 0 && shown != null && (window in activeWorkspace.windows || scratchpadOf(window) != null) && behaviorOf(window).animated) {
            glides[window] = Glide(shown, System.nanoTime())
        }
    }

    // Where a gliding window is drawn now: from its start towards its current place, fast
    // at first and gently slowing down. Null when the glide is over.
    private fun glidePosition(glide: Glide, current: Rect, nowNanos: Long): Rect? {
        val target = glide.to ?: current
        val animation = animations.windowMove
        val t = animationProgress(glide.startNanos, millisOf(animation), nowNanos) ?: return null
        return try {
            animation.frame(Motion(null, glide.from, target, curveOf(animation).safeAt(t), t, focusedMonitor.usableArea)).rect
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.error("Your window move animation failed (windows jump): $e", e)
            null
        }
    }

    // Takes a window out of tiling: it gets its floating place and size back.
    private fun untile(window: Window) {
        pending.remove(window)
        if (!window.tiled) return
        window.tiled = false
        val floating = floatingRects[window] ?: cascadeRect(window.visibleRect.width, window.visibleRect.height)
        moveSmoothly(window, floating.x, floating.y)
        window.configure(floating.width, floating.height)
        changed()
    }

    // The next free spot of the cascade (top left, stepping down and right).
    private fun cascadeRect(windowWidth: Int, windowHeight: Int, area: Rect = focusedMonitor.area): Rect {
        val step = 32
        val x = (40 + cascade * step).coerceAtMost((area.width - windowWidth).coerceAtLeast(0))
        val y = (40 + cascade * step).coerceAtMost((area.height - windowHeight).coerceAtLeast(0))
        cascade = (cascade + 1) % 8
        return Rect(area.x + x, area.y + y, windowWidth, windowHeight)
    }

    /** True if [window] floats (placed by hand) rather than being tiled by the layout. */
    fun isFloating(window: Window): Boolean = window.tiled.not()

    /** Takes [window] out of the layout (floating) or puts it back (tiled). */
    fun setFloating(window: Window, floating: Boolean) {
        layoutLog.info { "$window: ${if (floating) "floating" else "tiled"}" }
        val workspace = workspaceOf(window) ?: return
        if (floating) {
            if (!window.tiled && window in workspace.floating) return
            floatingRects.getOrPut(window) { cascadeRect(window.visibleRect.width, window.visibleRect.height) }
            workspace.floating += window
            untile(window)
        } else {
            pinned -= window // (tiled: in one workspace's layout)
            floatingRects[window] = currentRect(window)
            workspace.floating -= window
        }
        arrange(workspace)
        changed()
    }

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

    private val pinned = HashSet<Window>()

    /** True if [window] is pinned: it floats and stays on screen when its monitor shows another workspace. */
    fun isPinned(window: Window): Boolean = window in pinned

    // The window of [workspace] that gets the keyboard when it is shown: the top one of its
    // own — a pinned window (which only came along) just when there is no other.
    private fun topWindow(workspace: Workspace): Window? =
        workspace.windows.lastOrNull { it !in pinned } ?: workspace.windows.lastOrNull()

    /** Pins [window] (it floats, and goes along to every workspace its monitor shows) or lets it go. */
    fun setPinned(window: Window, on: Boolean) {
        if (on == isPinned(window) || (on && workspaceOf(window) == null)) return
        layoutLog.info { "$window: ${if (on) "pinned (on every workspace)" else "no longer pinned"}" }
        if (on) {
            if (window.tiled) setFloating(window, true)
            pinned += window
        } else {
            pinned -= window
        }
        changed()
    }

    // What placementFor said, from a window's first configure until it appears.
    private val placements = HashMap<Window, WindowPlacement>()

    private fun placementOf(window: Window): WindowPlacement = placements.getOrPut(window) {
        try {
            placementFor(window) ?: WindowPlacement.USUAL
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.error("Your placement rule failed for $window (ignored): $e", e)
            WindowPlacement.USUAL
        }
    }

    private fun monitorNamed(name: String?): Monitor? = name?.let { wanted -> monitorList.firstOrNull { it.name == wanted } }

    // The placement's workspace if there is one with that number (else it is not used: a window
    // on workspace 42 of 9 could never be reached).
    private fun validWorkspace(placement: WindowPlacement): Int? = placement.workspace?.let { number ->
        if (number <= workspaceCount) number else null.also { layoutLog.warn("There is no workspace $number (workspaceCount is $workspaceCount): the window opens where it would anyway") }
    }

    // The workspace a new window opens on: the one its placement says, or the one with the focus.
    private fun workspaceFor(placement: WindowPlacement): Workspace {
        val monitor = monitorNamed(placement.monitor) ?: focusedMonitor
        return validWorkspace(placement)?.let { workspace(it, monitor) } ?: monitor.workspace
    }

    // Its floating place on [monitor]: as the placement says, else the next step of the cascade.
    // [width] x [height] is its picture; its title bar (if ours) stays on screen above it.
    private fun floatingPlace(window: Window, placement: WindowPlacement, monitor: Monitor, width: Int, height: Int): Rect {
        val w = placement.width ?: width
        val h = placement.height ?: height
        val bar = titleHeight(window)
        val area = monitor.usableArea
        return when {
            placement.x != null || placement.y != null -> Rect(area.x + (placement.x ?: 0), area.y + (placement.y ?: 0) + bar, w, h)
            placement.center -> Rect(area.x + (area.width - w) / 2, area.y + (area.height - h - bar) / 2 + bar, w, h)
            else -> cascadeRect(w, h, monitor.area).translate(0, bar)
        }
    }

    /**
     * Applies [placement] to [window], which is open already (e.g. a rule that matches it
     * only after it changed its title): to its workspace or monitor, floating, its size,
     * maximized, fullscreen.
     */
    fun applyPlacement(window: Window, placement: WindowPlacement) {
        if (scratchpadOf(window) != null || workspaceOf(window) == null) return
        layoutLog.info { "$window: placed by a rule ($placement)" }
        val monitor = monitorNamed(placement.monitor)
        when {
            validWorkspace(placement) != null -> moveToWorkspace(window, workspace(placement.workspace!!, monitor ?: monitorOf(window)))
            monitor != null -> moveToWorkspace(window, monitor.workspace)
        }
        placement.floating?.let { setFloating(window, it) }
        if (placement.width != null || placement.height != null || placement.x != null || placement.y != null || placement.center) {
            val visible = window.visibleRect
            val place = floatingPlace(window, placement, monitorOf(window), visible.width, visible.height).let { place ->
                behaviorOf(window).limit(place.width, place.height).let { (w, h) -> Rect(place.x, place.y, w, h) }
            }
            floatingRects[window] = place
            if (!window.tiled && !window.maximized && !window.fullscreen) {
                moveSmoothly(window, place.x, place.y)
                window.configure(place.width, place.height)
            }
        }
        placement.maximized?.let { setMaximized(window, it) }
        placement.fullscreen?.let { setFullscreen(window, it) }
        placement.pinned?.let { setPinned(window, it) }
        if (placement.goToWorkspace) focus(window)
        changed()
    }

    // --- windows appear and disappear ----------------------------------------------------

    // The layout decides the size before the first frame, so a new window never shows up
    // in a wrong size first (R1). Maximized/fullscreen apps get the whole desktop.
    override fun initialSize(window: Window): Pair<Int, Int> {
        if (directed(window)) return userCode("Your WindowDirector (initialSize)", 0 to 0) { director!!.initialSize(window) }
        scratchpadFor(window)?.let { pad ->
            window.tiled = pad.style is Scratchpad.Overlay // told in the very first configure
            return padTargets(pad, extra = window).getValue(window).let { it.width to it.height }
        }
        // Where it goes (rules), told in the very first configure too.
        val placement = placementOf(window)
        placement.maximized?.let { window.maximized = it }
        placement.fullscreen?.let { window.fullscreen = it }
        val workspace = workspaceFor(placement)
        val monitor = workspace.monitor ?: focusedMonitor
        if (window.maximized && !window.fullscreen) return withoutTitle(window, monitor.usableArea).let { it.width to it.height }
        if (window.fullscreen) return monitor.area.let { it.width to it.height }
        if (placement.floating ?: (placement.pinned == true || floats(window))) return (placement.width ?: 0) to (placement.height ?: 0)
        val target = arrange(workspace, extra = window)[window] ?: return 0 to 0
        window.tiled = true
        return target.width to target.height
    }

    override fun windowMapped(window: Window) {
        scratchpadFor(window)?.let { pad ->
            // Its program was just started (usually by the scratchpad's key): show it.
            pad.windows += window
            showScratchpad(pad, appearing = setOf(window))
            windowsLog.info { "Opened $window (workspace ${workspaceOf(window)?.number ?: "-"}, ${if (window.tiled) "tiled" else "floating"})" }
            notify(windowOpenedListeners, window, "window-opened")
            return
        }
        val placement = placementOf(window)
        placements -= window
        val workspace = workspaceFor(placement)
        if (directed(window)) {
            // Its place is the director's: it only joins a workspace (focus, the window list).
            workspace.add(window)
            window.tiled = false
            if (placement.goToWorkspace || placement.focus) focus(window) else changed()
            windowsLog.info { "Opened $window (placed by the director)" }
            notify(windowOpenedListeners, window, "window-opened")
            return
        }
        val monitor = workspace.monitor ?: focusedMonitor
        val visibleRect = window.visibleRect
        // Its place when floating: its own size, or (if the layout sized it) 2/3 of the monitor.
        val floatingPlace = if (window.tiled) floatingPlace(window, placement, monitor, monitor.area.width * 2 / 3, monitor.area.height * 2 / 3)
            else floatingPlace(window, placement, monitor, visibleRect.width, visibleRect.height)
        floatingRects[window] = floatingPlace
        workspace.add(window)
        val floats = placement.floating ?: (placement.pinned == true || floats(window))
        if (floats || workspace.layout === Floating) {
            if (floats) workspace.floating += window
            window.tiled = false
            window.x = floatingPlace.x
            window.y = floatingPlace.y
        }
        if (window.maximized || window.fullscreen) {
            // Started maximized: if it is ever restored, it gets a sensible floating place.
            if (placement.width == null) floatingRects[window] = Rect(floatingPlace.x, floatingPlace.y, monitor.area.width * 2 / 3, monitor.area.height * 2 / 3)
            window.x = if (window.fullscreen) monitor.area.x else monitor.usableArea.x
            window.y = if (window.fullscreen) monitor.area.y else monitor.usableArea.y + titleHeight(window)
        }
        // Floating: within the size limits of its rules (it draws itself anew in that size).
        val behavior = behaviorOf(window)
        if (!window.tiled && !window.maximized && !window.fullscreen) {
            val (w, h) = behavior.limit(visibleRect.width, visibleRect.height)
            if (w != visibleRect.width || h != visibleRect.height) {
                floatingRects[window] = Rect(window.x, window.y, w, h)
                window.configure(w, h)
            }
        }
        if (placement.pinned == true && !window.tiled) pinned += window
        // Its own tile was decided before the first frame: it appears there right away.
        val targets = arrange(workspace)
        targets[window]?.let { window.x = it.x; window.y = it.y }
        // The keyboard: if it is on screen (or should be shown) and may take it.
        val shown = monitorList.any { it.workspace === workspace }
        if ((shown || placement.goToWorkspace) && millisOf(animations.windowOpen) > 0 && !locked && behavior.animated) {
            appearing[window] = System.nanoTime()
        }
        if (placement.goToWorkspace || (shown && placement.focus)) focus(window) else changed()
        windowsLog.info { "Opened $window (workspace ${workspaceOf(window)?.number ?: "-"}, ${if (window.tiled) "tiled" else "floating"})" }
        notify(windowOpenedListeners, window, "window-opened")
    }

    override fun windowUnmapped(window: Window) {
        windowsLog.info { "Closed $window" }
        placements -= window
        appearing -= window
        pinned -= window
        // Its last look goes away smoothly (taken just before its picture went, or now).
        stopFlights(window)
        appearingPictures.remove(window)?.close()
        (lastLooks.remove(window) ?: lastLookOf(window))?.let { flights += it }
        titleTextures.remove(window)?.second?.close()
        if (titleHover?.first === window) titleHover = null
        if (titlePressed?.first === window) titlePressed = null
        minimizeTargets -= window
        // What every closed window leaves behind (a scratchpad's too).
        pending.remove(window)
        floatingRects -= window
        glides -= window
        shownRects -= window
        screenRects -= window
        if (grab?.window === window) grab = null
        if (hovered === window) hovered = null
        if (lastFocused === window) lastFocused = null
        windowListChanged()
        scratchpadOf(window)?.let { pad ->
            pad.windows -= window
            if (pad.windows.isEmpty()) {
                pad.shown = false
                pad.leaving = false
                shownPads -= pad
            } else if (pad.shown) {
                placeScratchpad(pad, appearing = emptySet())
            }
            if (padFocus === window || seat.keyboardFocus === window.surface) focus(pad.windows.lastOrNull()?.takeIf { pad.shown } ?: windows.lastOrNull())
            changed()
            notify(windowClosedListeners, window, "window-closed")
            return
        }
        val workspace = workspaceOf(window)
        workspace?.remove(window)
        workspace?.model?.forget(window) // closed for good: its place is free now
        if (workspace != null) {
            arrange(workspace)
            dropIfEmpty(workspace)
        }
        if (seat.keyboardFocus === window.surface) focus(windows.lastOrNull())
        changed()
        notify(windowClosedListeners, window, "window-closed")
    }

    // Also for a window that never appeared: its placement (asked before its first frame) and
    // its place in a remembering layout (given to learn its size) go.
    override fun windowDestroyed(window: Window) {
        placements -= window
        for (workspace in workspaces.values) workspace.model.forget(window)
        scratchpads.values.forEach { it.windows -= window }
    }

    override fun windowCommitted(window: Window) {
        lastLooks.remove(window)?.target?.close() // it drew again: still here
        if (window in pending) applyWhenReady()
        // While resizing from the left or top, the opposite edge stays where it was.
        val resize = grab as? Grab.Resize ?: return
        if (resize.window !== window) return
        val visibleRect = window.visibleRect
        if (resize.edges and XdgToplevel.ResizeEdge.LEFT != 0) window.x = resize.start.right - visibleRect.width
        if (resize.edges and XdgToplevel.ResizeEdge.TOP != 0) window.y = resize.start.bottom - visibleRect.height
        changed()
    }

    override fun bounds(window: Window): Pair<Int, Int> = monitorOf(window).area.let { it.width to it.height }

    // --- window states: maximized, fullscreen, minimized ----------------------------------

    /** The surface that gets key presses now: a window, a menu, a panel or a launcher. */
    val keyboardFocus: Surface? get() = seat.keyboardFocus

    /** Open menus (popups), in the order they opened. */
    val openPopups: List<Popup> get() = popups

    /** Where [popup] is on screen. */
    fun screenRect(popup: Popup): Rect {
        val (x, y) = popupOrigin(popup)
        val visible = popup.shell.visibleRect
        return Rect(x + visible.x, y + visible.y, visible.width, visible.height)
    }

    /** The window that has the keyboard, if any. */
    val focusedWindow: Window? get() = padFocus?.takeIf { it.activated } ?: windows.lastOrNull()?.takeIf { it.activated }

    // A request its rules say is not done: the program only hears how its window is.
    private fun refuses(window: Window, request: WindowRequest): Boolean {
        if (request !in behaviorOf(window).ignored) return false
        windowsLog.info { "$window asked for ${request.name.lowercase()}: not done (its rules say so)" }
        return true
    }

    // The director handles [request] of [window] itself (true), or leaves it to us.
    private fun directorTakes(window: Window, request: WindowRequest, on: Boolean): Boolean =
        directed(window) && userCode("Your WindowDirector (requested)", false) { director!!.requested(window, request, on) }

    override fun maximizeRequested(window: Window, maximized: Boolean) {
        if (directorTakes(window, WindowRequest.MAXIMIZE, maximized)) return
        // A scratchpad window already has its place; it only gets an answer.
        if (scratchpadOf(window) != null || refuses(window, WindowRequest.MAXIMIZE)) window.configure() else setMaximized(window, maximized)
    }

    override fun fullscreenRequested(window: Window, fullscreen: Boolean) {
        if (directorTakes(window, WindowRequest.FULLSCREEN, fullscreen)) return
        if (scratchpadOf(window) != null || refuses(window, WindowRequest.FULLSCREEN)) window.configure() else setFullscreen(window, fullscreen)
    }

    // Minimizing a scratchpad window hides its scratchpad.
    override fun minimizeRequested(window: Window) {
        if (directorTakes(window, WindowRequest.MINIMIZE, true)) return
        if (refuses(window, WindowRequest.MINIMIZE)) return
        val pad = scratchpadOf(window)
        if (pad != null) hideScratchpad(pad) else minimize(window)
    }

    /** Makes [window] fill the desktop (or brings it back to its earlier place and size). */
    fun setMaximized(window: Window, maximized: Boolean) {
        windowsLog.info { "$window: ${if (maximized) "maximized" else "not maximized"}" }
        if (!window.configured) {
            window.maximized = maximized // sent with the first configure (see initialSize)
            return
        }
        if (window.maximized == maximized) {
            window.configure() // the app expects an answer anyway
            return
        }
        window.maximized = maximized
        applyState(window)
    }

    /** Shows [window] on the whole screen, above everything (or back as before). */
    fun setFullscreen(window: Window, fullscreen: Boolean) {
        windowsLog.info { "$window: ${if (fullscreen) "fullscreen" else "not fullscreen"}" }
        if (!window.configured) {
            window.fullscreen = fullscreen
            return
        }
        if (window.fullscreen == fullscreen) {
            window.configure()
            return
        }
        window.fullscreen = fullscreen
        applyState(window)
        if (fullscreen && window.mapped) focus(window)
    }

    // Puts a window where its states say: full desktop, its tile, or its floating place.
    private fun applyState(window: Window) {
        pending.remove(window)
        val workspace = workspaceOf(window)
        if (window.maximized || window.fullscreen) {
            if (!window.tiled && window.mapped) floatingRects[window] = currentRect(window)
            // Fullscreen covers its monitor; maximized leaves panels and docks free.
            val monitor = monitorOf(window)
            val area = if (window.fullscreen) monitor.area else withoutTitle(window, monitor.usableArea)
            moveSmoothly(window, area.x, area.y)
            window.configure(area.width, area.height)
        } else if (workspace != null && window in workspace.tiledWindows()) {
            window.tiled = false // makes arrange send the tile size again
        } else {
            val floating = floatingRects[window] ?: currentRect(window)
            moveSmoothly(window, floating.x, floating.y)
            window.configure(floating.width, floating.height)
        }
        workspace?.let { arrange(it) }
        changed()
    }

    private fun currentRect(window: Window): Rect {
        val visibleRect = window.visibleRect
        return Rect(window.x, window.y, visibleRect.width, visibleRect.height)
    }

    /** Hides [window]; it comes back with [focus] or [cycleFocus] (Alt+Tab). */
    fun minimize(window: Window) {
        val workspace = workspaceOf(window) ?: return
        if (window !in workspace.windows) return
        windowsLog.info { "$window: minimized${if (window in minimizeTargets) " (into its dock icon)" else ""}" }
        // Into its dock icon (or down out of sight), as the minimize animation says.
        val shown = shownRects[window]
        stopFlights(window)
        if (shown != null) flightOf(window, FlightKind.MINIMIZE, animations.minimize, to = minimizedRect(window, shown))?.let { flights += it }
        workspace.windowList -= window
        workspace.minimizedList += window
        window.setMinimized(true)
        pending.remove(window)
        if (grab?.window === window) grab = null
        arrange(workspace)
        if (workspace === activeWorkspace) focus(windows.lastOrNull())
    }

    /**
     * Brings the window at the bottom of the stack to the front; pressed repeatedly, it
     * goes through all windows, minimized ones included (they come back).
     */
    fun cycleFocus() {
        val next = minimized.firstOrNull() ?: windows.firstOrNull() ?: return
        if (next === windows.lastOrNull()) return // only one window
        focus(next)
    }

    /**
     * Focuses the nearest window in a direction ([dx], [dy] = -1, 0 or 1), e.g. the column
     * to the right in a scrolling layout. Scrolling and canvas layouts then bring it into view.
     */
    fun focusDirection(dx: Int, dy: Int) {
        val from = focusedWindow
        if (from == null) {
            // Nothing focused here: the monitor beside, if there is one.
            val beside = monitorBeside(dx, dy)
            if (beside != null) focusMonitor(beside) else focus(windows.lastOrNull())
            return
        }
        val origin = currentRect(from)
        // Windows on every monitor count: past the last one here comes the next monitor.
        val shown = stackingOrder()
        val candidates = shown.filter { it !== from }.map { it to currentRect(it) }.filter { (_, rect) ->
            (dx > 0 && rect.x >= origin.right - 1) || (dx < 0 && rect.right <= origin.x + 1) ||
                (dy > 0 && rect.y >= origin.bottom - 1) || (dy < 0 && rect.bottom <= origin.y + 1)
        }
        val nearest = candidates.minByOrNull { (_, rect) ->
            val distanceX = (rect.x + rect.width / 2) - (origin.x + origin.width / 2)
            val distanceY = (rect.y + rect.height / 2) - (origin.y + origin.height / 2)
            distanceX.toLong() * distanceX + distanceY.toLong() * distanceY
        }
        if (nearest != null) focus(nearest.first) else monitorBeside(dx, dy)?.let(::focusMonitor) // an empty monitor
    }

    /** Asks the focused window to close. */
    fun closeFocused() {
        focusedWindow?.close()
    }

    /** The window under the desktop point ([x], [y]), if any. */
    fun windowAt(x: Double, y: Double): Window? = surfaceAt(x, y)?.root?.roleObject as? Window

    /** The windows on screen bottom to top, as they are drawn (X11 windows are stacked the same way). */
    val stacking: List<Window> get() = stackingOrder()

    // Windows bottom to top as drawn, on every monitor: tiled ones, floating ones above
    // them, fullscreen on top.
    private fun stackingOrder(): List<Window> = shownWorkspaces().flatMap { workspace ->
        val (fullscreen, others) = workspace.windows.partition { it.fullscreen }
        others.filter { it.tiled } + others.filter { !it.tiled } + fullscreen
    }

    // The workspaces on screen, one per monitor.
    private fun shownWorkspaces(): List<Workspace> = monitorList.map { it.workspace }.distinct()

    // --- moving and resizing with the pointer ---------------------------------------------

    private sealed class Grab(val window: Window, val pointerX: Double, val pointerY: Double) {
        class Move(window: Window, pointerX: Double, pointerY: Double, val startX: Int, val startY: Int) :
            Grab(window, pointerX, pointerY)

        class Resize(window: Window, pointerX: Double, pointerY: Double, val edges: Int, val start: Rect) :
            Grab(window, pointerX, pointerY) {
            var lastWidth = start.width
            var lastHeight = start.height
        }
    }

    private var grab: Grab? = null
    private var buttonsHeld = 0

    override fun moveRequested(window: Window, serial: Int) {
        if (directed(window) && userCode("Your WindowDirector (moveRequested)", false) { director!!.moveRequested(window) }) return
        beginMove(window)
    }

    override fun resizeRequested(window: Window, serial: Int, edges: Int) {
        if (directed(window) && userCode("Your WindowDirector (resizeRequested)", false) { director!!.resizeRequested(window, edges) }) return
        beginResize(window, edges)
    }

    /**
     * Moves [window] with the pointer until the button is released. Only while a button is
     * held (an app cannot grab the pointer on its own). A tiled window starts floating.
     */
    fun beginMove(window: Window) {
        if (locked || buttonsHeld == 0 || window !in windows || window.fullscreen) return
        if (window.maximized) {
            // Dragging a maximized window: it gets its old size back, under the pointer.
            val floating = floatingRects[window] ?: currentRect(window)
            floatingRects[window] = Rect((pointerX - floating.width / 2).toInt(), pointerY.toInt() - 10, floating.width, floating.height)
            setMaximized(window, false)
        }
        if (window.tiled) {
            floatingRects[window] = currentRect(window) // it floats from where it is now
            setFloating(window, true)
        }
        startGrab(Grab.Move(window, pointerX, pointerY, window.x, window.y))
    }

    /** Resizes [window] with the pointer at [edges] (XdgToplevel.ResizeEdge) until the button is released. */
    fun beginResize(window: Window, edges: Int) {
        if (locked || buttonsHeld == 0 || window !in windows || window.maximized || window.fullscreen) return
        if (window.tiled) {
            floatingRects[window] = currentRect(window)
            setFloating(window, true)
        }
        startGrab(Grab.Resize(window, pointerX, pointerY, edges, currentRect(window)))
    }

    /** The resize edges nearest to the desktop point (for Super+right-drag). */
    fun nearestEdges(window: Window, x: Double, y: Double): Int {
        val rect = currentRect(window)
        val horizontal = if (x < rect.x + rect.width / 2) XdgToplevel.ResizeEdge.LEFT else XdgToplevel.ResizeEdge.RIGHT
        val vertical = if (y < rect.y + rect.height / 2) XdgToplevel.ResizeEdge.TOP else XdgToplevel.ResizeEdge.BOTTOM
        return horizontal or vertical
    }

    private fun startGrab(newGrab: Grab) {
        grab = newGrab
        focus(newGrab.window)
        // The app under the pointer stops getting pointer events during the grab.
        seat.pointerMotion((System.nanoTime() / 1_000_000).toInt(), null, 0.0, 0.0)
    }

    private fun continueGrab(current: Grab) {
        val dx = (pointerX - current.pointerX).toInt()
        val dy = (pointerY - current.pointerY).toInt()
        when (current) {
            is Grab.Move -> {
                current.window.x = current.startX + dx
                current.window.y = current.startY + dy
                changed()
            }
            is Grab.Resize -> {
                val window = current.window
                val edges = current.edges
                var newWidth = current.start.width
                var newHeight = current.start.height
                if (edges and XdgToplevel.ResizeEdge.LEFT != 0) newWidth -= dx
                if (edges and XdgToplevel.ResizeEdge.RIGHT != 0) newWidth += dx
                if (edges and XdgToplevel.ResizeEdge.TOP != 0) newHeight -= dy
                if (edges and XdgToplevel.ResizeEdge.BOTTOM != 0) newHeight += dy
                newWidth = limit(newWidth, window.minWidth, window.maxWidth)
                newHeight = limit(newHeight, window.minHeight, window.maxHeight)
                behaviorOf(window).limit(newWidth, newHeight).let { (w, h) -> newWidth = w; newHeight = h }
                if (newWidth != current.lastWidth || newHeight != current.lastHeight) {
                    current.lastWidth = newWidth
                    current.lastHeight = newHeight
                    window.resizing = true
                    window.configure(newWidth, newHeight)
                }
            }
        }
    }

    private fun endGrab() {
        val ended = grab ?: return
        grab = null
        if (ended is Grab.Move) {
            floatingRects[ended.window] = currentRect(ended.window)
            // Dropped on another monitor: it belongs to the workspace shown there now.
            val rect = currentRect(ended.window)
            val target = monitorAt(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
            val from = monitorOf(ended.window)
            if (target !== from && workspaceOf(ended.window) != null) {
                moveToWorkspace(ended.window, target.workspace)
                setFocusedMonitor(target)
                focus(ended.window)
            }
        }
        if (ended is Grab.Resize) {
            if (ended.window.resizing) {
                ended.window.resizing = false
                ended.window.configure()
            }
            floatingRects[ended.window] = currentRect(ended.window)
        }
    }

    // A size within the app's limits (0 = no limit), never smaller than 1.
    private fun limit(size: Int, min: Int, max: Int): Int {
        var result = maxOf(size, min, 1)
        if (max > 0) result = minOf(result, max)
        return result
    }

    // --- popups and focus ------------------------------------------------------------------

    /** Mapped popups, in the order they opened (children after parents). */
    private val popups = ArrayList<Popup>()

    /** Popups that asked for a grab: a click outside them closes them. */
    private val grabs = ArrayList<Popup>()

    /** Pointer position on the output. */
    var pointerX = width / 2.0
        private set
    var pointerY = height / 2.0
        private set

    // Surfaces visible right now; they get "frame done" at every refresh.
    private val visible = LinkedHashSet<Surface>()

    // A menu stays on the monitor its window (or panel) is on.
    override fun popupBounds(popup: Popup): Rect? {
        val (ox, oy) = popup.parent?.let(::visibleOrigin) ?: popup.layerParent?.let { it.x to it.y } ?: return null
        val window = generateSequence(popup.parent) { (it.role as? Popup)?.parent }.mapNotNull { it.role as? Window }.firstOrNull()
        val monitor = window?.let { if (workspaceOf(it) != null) monitorOf(it) else null } ?: monitorAt(ox.toDouble(), oy.toDouble())
        val area = monitor.area
        return Rect(area.x - ox, area.y - oy, area.width, area.height)
    }

    override fun popupMapped(popup: Popup) {
        popups += popup
        changed()
    }

    override fun popupUnmapped(popup: Popup) {
        popups -= popup
        grabs -= popup
        changed()
        if (seat.keyboardFocus === popup.surface) {
            seat.focusKeyboard(grabs.lastOrNull()?.surface ?: windows.lastOrNull()?.surface)
        }
    }

    override fun popupGrab(popup: Popup) {
        // Only a program you are using now may take the keyboard with a menu (one in the
        // background would get your keys): else the menu is closed at once (an invalid grab).
        val client = popup.resource.client
        val inUse = seat.keyboardFocus?.resource?.client === client || seat.pointerFocus?.resource?.client === client
        if (!inUse || locked || (popup.parent == null && popup.layerParent == null)) {
            windowsLog.info { "${client.name} asked for a menu grab while not in use: the menu is closed" }
            popup.dismiss()
            return
        }
        grabs += popup
        seat.focusKeyboard(popup.surface)
    }

    /** Marks [window] as wanting attention (or not); bars and the IPC socket show it, the focus clears it. */
    fun setUrgent(window: Window, urgent: Boolean) {
        if (window.urgent == urgent) return
        window.urgent = urgent
        focusLog.info { "$window ${if (urgent) "wants attention" else "no longer wants attention"}" }
        if (window.mapped) notify(windowInfoListeners, window, "window-info-changed")
        windowListChanged()
        changed()
    }

    /** The windows that want attention, oldest first. */
    val urgentWindows: List<Window> get() = allWindows.filter { it.urgent }

    /**
     * Raises [window], marks it active and gives it the keyboard (null = nothing focused).
     * A minimized window comes back; a window on another workspace brings that workspace.
     */
    fun focus(window: Window?) {
        // A window getting the focus takes the keyboard back from a widget.
        if (window != null && keyboardWidget != null) focusWidget(null)
        // One that never takes the keyboard: the top one of its workspace that does, instead.
        if (window != null && !behaviorOf(window).takesFocus) {
            val instead = workspaceOf(window)?.windows?.lastOrNull { it !== window && behaviorOf(it).takesFocus }
            if (instead === window || (instead == null && focusedWindow != null && workspaceOf(focusedWindow!!) === workspaceOf(window))) return
            return focus(instead)
        }
        if (window != null && window.urgent) setUrgent(window, false)
        val pad = window?.let(::scratchpadOf)
        // Everything else loses the "active" look.
        for (other in scratchpads.values.flatMap { it.windows }) if (other !== window) other.setActivated(false)
        if (pad != null) {
            for (other in activeWorkspace.windows) other.setActivated(false)
            pad.windows -= window
            pad.windows += window
            window.setActivated(true)
            padFocus = window
            if (exclusiveLayer() == null) {
                layerFocus = null
                seat.focusKeyboard(window.surface)
            }
            if (window !== lastFocused) {
                lastFocused = window
                focusLog.info { "Focus: ${window ?: "nothing"}" }
                notify(focusListeners, window, "focus")
            }
            changed()
            return
        }
        padFocus = null
        if (window != null) {
            val workspace = workspaceOf(window)
            if (workspace != null && workspace !== activeWorkspace) {
                // Its monitor gets the focus; a workspace on no monitor comes onto the focused one.
                val showing = monitorList.firstOrNull { it.workspace === workspace }
                if (showing != null) {
                    setFocusedMonitor(showing)
                } else {
                    // Where this workspace belongs, as the workspace mode says (as switchTo does).
                    when {
                        workspaceMode == WorkspaceMode.SPANNING && workspace.number > 0 ->
                            for (monitor in monitorList) show(workspace(workspace.number, monitor), on = monitor)
                        else -> {
                            val on = monitorNamed(workspace.homeMonitor)
                                ?: (if (workspaceMode == WorkspaceMode.PRIMARY_ONLY && workspace.number > 0) primaryMonitor else null)
                                ?: (if (workspace.number > 0) homeOf(workspace.number) else null)
                                ?: focusedMonitor
                            setFocusedMonitor(on)
                            show(workspace, on = on)
                        }
                    }
                    layoutLog.info { "Workspace ${workspace.number} (${workspace.layout.name})" }
                    notify(workspaceListeners, workspace, "workspace")
                }
            }
            if (activeWorkspace.minimizedList.remove(window)) {
                if (window !in activeWorkspace.windowList) activeWorkspace.windowList += window
                window.setMinimized(false)
                // Back out of its dock icon (or up from below), as the restore animation says.
                stopFlights(window)
                glides -= window
                flightOf(window, FlightKind.RESTORE, animations.restore, to = minimizedRect(window, currentRect(window)))?.let { flights += it }
            }
        }
        val list = activeWorkspace.windowList
        for (other in list) if (other !== window) other.setActivated(false)
        if (window != null) {
            // On top of its own workspace (only if it is on this one: never into another's list).
            if (list.remove(window)) list += window
            window.setActivated(true)
        }
        if (exclusiveLayer() == null) {
            layerFocus = null
            seat.focusKeyboard(window?.surface)
        }
        if (window !== lastFocused) {
            lastFocused = window
            focusLog.info { "Focus: ${window ?: "nothing"}" }
            notify(focusListeners, window, "focus")
        }
        // Layouts that follow the focus (scrolling) move their windows.
        if (window != null && window.tiled && activeWorkspace.layout !== Floating) arrange(activeWorkspace)
        changed()
    }

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
    private fun constraintNow(): dev.caulis.core.input.PointerConstraints.Constraint? {
        val now = if (grab == null && !inOverview && !locked) pointerConstraints?.current() else null
        val ended = lastLock
        if (ended != null && ended !== now) {
            // A lock let go: the pointer goes where the program said it should be.
            ended.hint?.let { (hx, hy) -> surfaceOrigin(ended.surface)?.let { (ox, oy) -> pointerX = ox + hx; pointerY = oy + hy } }
        }
        lastLock = now?.takeIf { it.locks }
        return now
    }

    // [x], [y] kept inside the confinement (the part of the region the pointer is in).
    private fun confined(c: dev.caulis.core.input.PointerConstraints.Constraint, x: Double, y: Double): Pair<Double, Double> {
        val (ox, oy) = surfaceOrigin(c.surface) ?: return x to y
        val rects = c.region?.solidRects()?.takeIf { it.isNotEmpty() } ?: listOf(Rect(0, 0, c.surface.width, c.surface.height))
        val px = pointerX - ox
        val py = pointerY - oy
        val rect = rects.firstOrNull { px >= it.x && py >= it.y && px < it.right && py < it.bottom } ?: return x to y
        return (x - ox).coerceIn(rect.x.toDouble(), rect.right - 1.0) + ox to (y - oy).coerceIn(rect.y.toDouble(), rect.bottom - 1.0) + oy
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
        cursorActive()
        // On a monitor: past an edge with no monitor beyond, it stops there.
        val on = monitorAt(x, y)
        pointerX = x.coerceIn(on.area.x.toDouble(), on.area.right - 1.0)
        pointerY = y.coerceIn(on.area.y.toDouble(), on.area.bottom - 1.0)
        if (!locked) noticeBoard.pointerAt(pointerX, pointerY)
        if (!locked && withOverlay { it.pointerMoved(pointerX, pointerY) }) {
            onChange()
            return
        }
        grab?.let { current ->
            continueGrab(current)
            onChange()
            return
        }
        if (inOverview) {
            onChange() // only our cursor moves; programs do not see it
            return
        }
        // Buttons held since a press in a program: it keeps the pointer, wherever it goes.
        pressedIn?.let { pressed ->
            if (buttonsHeld > 0 && !locked && pressed.surface.resource.isAlive) {
                seat.pointerMotion(timeMillis, pressed.surface, (pointerX - pressed.x) / pressed.scaleX, (pointerY - pressed.y) / pressed.scaleY)
                onChange()
                return
            }
            pressedIn = null
        }
        // A button pressed in a widget: it keeps the pointer until let go.
        pressedWidget?.let { pressed ->
            if (buttonsHeld > 0 && !locked) {
                userCode("Your widget (pointerMoved)", Unit) { pressed.widget.pointerMoved(pressed.monitor, pointerX - pressed.area.x, pointerY - pressed.area.y) }
                onChange()
                return
            }
            pressedWidget = null
        }
        // Over one of our widgets: it has the pointer (the program below loses it).
        val overWidget = widgetAt(pointerX, pointerY, ABOVE_LEVELS)
            ?: if (widgets.any { it.level == DrawingLevel.BELOW_WINDOWS } && surfaceAt(pointerX, pointerY) == null) widgetAt(pointerX, pointerY, BELOW_LEVEL) else null
        if (hoveredWidget != null && hoveredWidget !== overWidget?.first) {
            val left = hoveredWidget!!
            hoveredWidget = null
            userCode("Your widget (pointerLeft)", Unit) { left.pointerLeft() }
            cursorChanged()
        }
        if (overWidget != null) {
            val (widget, area) = overWidget
            if (hoveredWidget !== widget) {
                hoveredWidget = widget
                cursorChanged()
            }
            seat.pointerMotion(timeMillis, null, 0.0, 0.0)
            userCode("Your widget (pointerMoved)", Unit) { widget.pointerMoved(monitorAt(pointerX, pointerY), pointerX - area.x, pointerY - area.y) }
            onChange()
            return
        }
        // Over one of our title bars: the program does not see the pointer, the bar may light up.
        val decoration = decorationAt(pointerX, pointerY)
        val hover = (decoration as? Decoration.Bar)?.let { it.window to it.button }
        decorationCursor = (decoration as? Decoration.Edge)?.let { resizeCursor(it.edges) }
        if (hover != titleHover) {
            titleHover = hover
            changed()
        }
        // The keyboard follows the pointer: onto another window, it gets the focus (once, so
        // keys can still move the focus while the pointer rests).
        if (focusFollowsMouse && !locked) {
            val under = windowAt(pointerX, pointerY) ?: (decoration as? Decoration.Bar)?.window ?: (decoration as? Decoration.Edge)?.window
            if (under !== hovered) {
                hovered = under
                if (under != null && under !== focusedWindow && exclusiveLayer() == null) focus(under)
            }
        }
        if (decoration != null) {
            seat.pointerMotion(timeMillis, null, 0.0, 0.0)
            onChange()
            return
        }
        val hit = surfaceAt(pointerX, pointerY)
        seat.pointerMotion(timeMillis, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
        onChange() // only the cursor moved: the rest of the scene stays as it is
    }

    // The version of the scene the pointer's program was last checked against.
    private var pointerCheckedVersion = -1L

    // Windows moved under a pointer standing still (another workspace shown, a window
    // opened, closed or moved): the program now under it gets it — with its cursor — and the
    // one that went away stops getting it (a game on a hidden workspace saw every move).
    private fun pointerOntoWhatIsThere() {
        if (pointerCheckedVersion == version) return
        pointerCheckedVersion = version
        if (locked || overlay != null || grab != null || inOverview || buttonsHeld > 0) return
        if (decorationAt(pointerX, pointerY) != null) return
        val hit = surfaceAt(pointerX, pointerY)
        if (hit?.surface === seat.pointerFocus) return
        inputLog.trace { "The pointer is over ${hit?.root?.roleObject ?: "nothing"} now (the windows moved)" }
        seat.pointerMotion((System.nanoTime() / 1_000_000).toInt(), hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
    }

    // Our own cursor over a window's edge (resize arrows), or null.
    private var decorationCursor: String? = null

    /** The window under the pointer gets the keyboard as soon as the pointer comes onto it. */
    var focusFollowsMouse = false

    // The window the pointer was over last (for focusFollowsMouse).
    private var hovered: Window? = null

    // The button that closed a notice: its release is ours too.
    private var noticeButton: Int? = null

    /**
     * A mouse button changed. With [passToApps] false it only counts as held (for a move or
     * resize the window manager starts itself, e.g. Super+drag) and no program sees it.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean, passToApps: Boolean = true) {
        inputLog.trace { "Button $button ${if (pressed) "pressed" else "released"} at ${pointerX.toInt()},${pointerY.toInt()}${if (passToApps) "" else " (for the window manager)"}" }
        buttonsHeld = (buttonsHeld + if (pressed) 1 else -1).coerceAtLeast(0)
        // The last button let go: the program the press was in lets go of the pointer.
        val releasedFrom = if (!pressed && buttonsHeld == 0) pressedIn.also { pressedIn = null } else null
        cursorActive()
        if (!passToApps) return
        if (locked) {
            seat.pointerButton(timeMillis, button, pressed) // only the locker can be under the pointer
            return
        }
        if (withOverlay { it.pointerButton(button, pressed) }) return
        // A widget's (the one pressed in keeps it until the last button is let go).
        val widgetTarget = if (locked) null else pressedWidget?.widget ?: hoveredWidget
        if (widgetTarget != null) {
            if (pressed && buttonsHeld == 1) {
                val area = widgetAt(pointerX, pointerY, ALL_LEVELS)?.takeIf { it.first === widgetTarget }?.second
                if (area != null) pressedWidget = PressedWidget(widgetTarget, area, monitorAt(pointerX, pointerY))
            }
            if (pressed && widgetTarget.takesKeyboard) focusWidget(widgetTarget)
            userCode("Your widget (pointerButton)", Unit) { widgetTarget.pointerButton(button, pressed) }
            if (!pressed && buttonsHeld == 0 && pressedWidget != null) {
                pressedWidget = null
                movePointer(timeMillis, pointerX, pointerY)
            }
            return
        }
        // A click anywhere else takes the keyboard back from a widget.
        if (pressed && keyboardWidget != null) focusWidget(null)
        if (inOverview) {
            if (pressed) overviewClick()
            return
        }
        if (grab != null) {
            // The button that started a move or resize ends it; the app does not see it.
            if (!pressed && buttonsHeld == 0) {
                endGrab()
                movePointer(timeMillis, pointerX, pointerY) // the app under the pointer gets it back
            }
            return
        }
        // A click on a notice of ours closes it (the program below sees neither press nor release).
        if (pressed) {
            noticeBoard.at(pointerX, pointerY)?.let { notice ->
                dismiss(notice)
                noticeButton = button
                return
            }
        } else if (button == noticeButton) {
            noticeButton = null
            return
        }
        // Title bars and edges of our own (no program sees these clicks).
        if (grabs.isEmpty() && barButton(button, pressed)) return
        if (pressed) {
            val hit = surfaceAt(pointerX, pointerY)
            // A click outside the open menus closes them (and is not passed to the window below).
            if (grabs.isNotEmpty() && (hit == null || popups.none { it.surface === hit.root })) {
                grabs.toList().asReversed().forEach { it.dismiss() }
                grabs.clear()
                return
            }
            // A click on the dimmed area around an overlay scratchpad hides it.
            val overlay = shownPads.lastOrNull { it.style is Scratchpad.Overlay && !it.leaving }
            if (overlay != null && (hit == null || (hit.root.roleObject as? Window)?.let(::scratchpadOf) !== overlay)) {
                hideScratchpad(overlay)
                return
            }
            // A click on a panel that wants the keyboard on demand gives it the keyboard.
            (hit?.root?.roleObject as? LayerSurface)?.let { layer ->
                if (layer.current.keyboard != KEYBOARD_NONE && exclusiveLayer() == null) {
                    layerFocus = layer
                    seat.focusKeyboard(layer.surface)
                }
            }
            val window = hit?.root?.roleObject as? Window
            if (window != null && (window !== focusedWindow || layerFocus != null)) {
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
    val pointerWindow: Window? get() = seat.pointerFocus?.let(::ownerOf) as? Window

    // The surface the held buttons were pressed in, and where its top left corner was (desktop
    // units): pointer moves go to it until they are let go (Wayland's implicit grab).
    private var pressedIn: PressedIn? = null

    private class PressedIn(val surface: Surface, val x: Double, val y: Double, val scaleX: Double, val scaleY: Double)

    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) {
        inputLog.trace { "Scroll ${if (vertical) "vertical" else "horizontal"} $value ($value120/120)" }
        cursorActive()
        if (!locked && withOverlay { it.scroll(vertical, if (value120 != 0) value120 / 120.0 else value / 15.0) }) return
        hoveredWidget?.takeIf { !locked }?.let { widget ->
            userCode("Your widget (scroll)", Unit) { if (vertical) widget.scroll(0.0, value) else widget.scroll(value, 0.0) }
            return
        }
        seat.pointerAxis(timeMillis, vertical, value, value120)
    }

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        // Never what is typed into a locker (a password).
        inputLog.trace { if (locked) "A key (not logged while locked)" else "Key $evdevCode ${if (pressed) "pressed" else "released"} → ${seat.keyboardFocus}" }
        // Typing (not just holding Ctrl or Super): the cursor makes way.
        if (pressed && hideCursorWhileTyping && evdevCode !in MODIFIER_KEYS && seat.keyboardFocus != null) hideCursor()
        keyboardWidget?.takeIf { !locked && overlay == null }?.let { widget ->
            val text = if (pressed) seat.textOf(evdevCode) else null
            seat.key(timeMillis, evdevCode, pressed) // (no program has the keyboard: only its state moves on)
            val m = seat.modifierBits
            userCode("Your widget (key)", Unit) { widget.key(WidgetKey(evdevCode, pressed, text, m and 1 != 0, m and 4 != 0, m and 8 != 0, m and 64 != 0, timeMillis)) }
            return
        }
        if (!locked && overlay != null) {
            withOverlay { it.key(evdevCode, pressed) }
            if (pressed) return // ...as with the overview below
        }
        if (inOverview && pressed) {
            if (evdevCode == KEY_ESC) setOverview(false)
            return // new key presses are not for programs while the overview is open...
        }
        seat.key(timeMillis, evdevCode, pressed) // ...but releases are, or keys would stick
    }

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

    private fun surfaceAt(x: Double, y: Double): Hit? {
        if (locked) {
            // Only the locker is there.
            for (lockSurface in lockSurfaces()) hitTree(lockSurface.surface, x, y, lockSurface.surface)?.let { return it }
            return null
        }
        for ((surface, place) in unmanaged.entries.reversed().map { it.key to it.value }) {
            hitTree(surface, x - place.first, y - place.second, surface)?.let { return it }
        }
        for (popup in popups.asReversed()) {
            val (px, py) = popupOrigin(popup)
            hitTree(popup.surface, x - px, y - py, popup.surface)?.let { return it }
        }
        layerAt(LayerShell.Layer.OVERLAY, x, y)?.let { return it }
        for (pad in shownPads.asReversed()) {
            if (pad.leaving) continue
            for (window in pad.windows.asReversed()) {
                val visible = window.visibleRect
                hitTree(window.surface, x - (window.x - visible.x), y - (window.y - visible.y), window.surface)?.let { return it }
            }
        }
        val (fullscreen, normal) = stackingOrder().partition { it.fullscreen }
        for (window in fullscreen.asReversed()) if (window !in places) windowHit(window, x, y)?.let { return it }
        layerAt(LayerShell.Layer.TOP, x, y)?.let { return it }
        // The director's windows, topmost first, where they are drawn (stretched maybe).
        for ((window, place) in places.entries.reversed()) if (place.takesInput) directedHit(window, place, x, y)?.let { return it }
        for (window in normal.asReversed()) if (window !in places && !directed(window)) windowHit(window, x, y)?.let { return it }
        layerAt(LayerShell.Layer.BOTTOM, x, y)?.let { return it }
        return layerAt(LayerShell.Layer.BACKGROUND, x, y)
    }

    // [place] is where the picture is; the program is told the point in its own size.
    private fun directedHit(window: Window, place: WindowPlace, x: Double, y: Double): Hit? {
        if (x < place.x || y < place.y || x >= place.x + place.width || y >= place.y + place.height) return null
        place.clip?.let { c -> if (x < c.x || y < c.y || x >= c.right || y >= c.bottom) return null }
        val visible = window.visibleRect
        val scaleX = if (visible.width > 0 && place.width > 0) place.width / visible.width else 1.0
        val scaleY = if (visible.height > 0 && place.height > 0) place.height / visible.height else 1.0
        val hit = hitTree(window.surface, visible.x + (x - place.x) / scaleX, visible.y + (y - place.y) / scaleY, window.surface) ?: return null
        return Hit(hit.surface, hit.localX, hit.localY, hit.root, scaleX, scaleY)
    }

    private fun windowHit(window: Window, x: Double, y: Double): Hit? {
        val visible = window.visibleRect
        return hitTree(window.surface, x - (window.x - visible.x), y - (window.y - visible.y), window.surface)
    }

    private fun layerAt(layer: LayerShell.Layer, x: Double, y: Double): Hit? {
        for (surface in layerSurfaces.asReversed()) {
            if (!surface.mapped || surface.current.layer != layer) continue
            hitTree(surface.surface, x - surface.x, y - surface.y, surface.surface)?.let { return it }
        }
        return null
    }

    // Checks a surface and its subsurfaces, topmost first. x, y are relative to [surface].
    private fun hitTree(surface: Surface, x: Double, y: Double, root: Surface): Hit? {
        for (child in surface.stack.asReversed()) {
            if (child === surface) {
                if (surface.hasContent && surface.acceptsInput(x, y)) return Hit(surface, x, y, root)
            } else {
                val sub = child.subsurface ?: continue
                hitTree(child, x - sub.x, y - sub.y, root)?.let { return it }
            }
        }
        return null
    }

    // Where a popup's surface starts on the output.
    private fun popupOrigin(popup: Popup): Pair<Int, Int> {
        val (parentX, parentY) = popup.parent?.let(::visibleOrigin) ?: popup.layerParent?.let { it.x to it.y } ?: (0 to 0)
        val visible = popup.shell.visibleRect
        return (parentX + popup.x - visible.x) to (parentY + popup.y - visible.y)
    }

    // Where the visible top left corner of a window or popup is on the output.
    private fun visibleOrigin(shell: ShellSurface): Pair<Int, Int> = when (val role = shell.role) {
        is Window -> role.x to role.y
        is Popup -> {
            val (parentX, parentY) = role.parent?.let(::visibleOrigin) ?: role.layerParent?.let { it.x to it.y } ?: (0 to 0)
            (parentX + role.x) to (parentY + role.y)
        }
        else -> 0 to 0
    }

    // --- drawing ----------------------------------------------------------------------

    // A program drew something: mark those parts as changed where the surface is shown.
    private fun surfaceCommitted(surface: Surface) {
        val changes = surface.takeChanges()
        if (changes.isNotEmpty()) {
            for (view in views + captureViews) {
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
        for (listener in commitListeners.toList()) listener(surface)
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
        val noticesMoving = noticeBoard.animating() || osd.animating(System.nanoTime()) // (also lets go of those that faded away)
        // Each asked every time (they move their animations on: none may be skipped by another).
        val fadingNow = fading()
        val animatingNow = animating()
        val backdropsChanging = backdrops.changing()
        val directorMoving = director?.let { d -> userCode("Your WindowDirector (animating)", false) { d.animating(System.nanoTime()) } } == true ||
            drawings.values.any { list -> list.any { d -> userCode("Your drawing (animating)", false) { d.animating(System.nanoTime()) } } } ||
            widgets.any { w -> userCode("Your widget (animating)", false) { w.animating(System.nanoTime()) } }
        if (glides.isNotEmpty() || cameraMoving(System.nanoTime()) || fadingNow || noticesMoving || animatingNow || backdropsChanging || paintsMoving || focusChanging || directorMoving) {
            // Windows are gliding (or the overview zooms): the scene changes every frame until done.
            version++
            onChange()
        }
        pointerOntoWhatIsThere()
        placeHardwareCursor(view)
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
            present { target, age -> render(view, target, age) }
        } else {
            // Nothing new to show: whatever programs committed is on screen already.
            takeFeedbacks(view)
            presented(view, System.nanoTime(), refreshNanos = 0, sequence = 0, flags = 0)
        }
        frameShown((System.nanoTime() / 1_000_000).toInt())
        for (listener in frameListeners.toList()) listener()
        return draw
    }

    /**
     * The frame last drawn for [view] reached the screen at [nanos] (monotonic clock).
     * Programs that asked are told (presentation-time).
     */
    fun presented(view: DesktopView, nanos: Long, refreshNanos: Long, sequence: Long, flags: Int) {
        Presentation.presented(view.inFlight, nanos, refreshNanos, sequence, flags)
        view.inFlight.clear()
        if (lockPending && view.showsLock) {
            lockShownOn += view
            confirmLockIfShown()
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

    // Puts the cursor on [view]'s cursor plane if it can be there (on this monitor, fitting the
    // plane, the monitor not turned); else back into the frames. Called before each frame.
    private fun placeHardwareCursor(view: DesktopView) {
        val plane = view.hardwareCursor
        if (plane == null) {
            view.cursorOnPlane = false
            return
        }
        val monitor = view.monitor
        val all = items()
        val cursor = all.subList(itemsCursorStart.coerceAtMost(all.size), all.size)
        fun intoFrames() {
            if (view.cursorOnPlane || view.planeCursorKey != null) plane.hide()
            view.cursorOnPlane = false
            view.planeCursorKey = null
        }
        if (cursor.isEmpty()) {
            // No cursor at all (hidden): nothing on the plane, nothing in the frames.
            if (view.planeCursorKey != null) plane.hide()
            view.planeCursorKey = null
            view.cursorOnPlane = true
            return
        }
        if (monitor.transform != Transform.NORMAL || locked) return intoFrames()
        val bounds = cursor.map { it.rect }.reduce(Rect::union)
        if (bounds.intersect(monitor.area) == null) {
            // On another monitor: none here.
            if (view.planeCursorKey != null) plane.hide()
            view.planeCursorKey = null
            view.cursorOnPlane = true
            return
        }
        val pixels = monitor.toPixels(bounds)
        if (pixels.width > plane.width || pixels.height > plane.height) return intoFrames()
        // A new picture when the cursor looks different (another shape, its next frame, the
        // program drew a new one, another size or scale); else it only moves.
        val key = listOf(cursor.map { item -> (item as? DrawItem.Picture)?.surface ?: (item as? DrawItem.Image)?.texture ?: item.key }, pixels.width, pixels.height, monitor.scale,
            cursorVersion, if (cursor.any { it is DrawItem.Picture }) version else 0L)
        val draw: ((GpuTarget) -> Unit)? = if (key == view.planeCursorKey) null else { target ->
            renderer.begin(target, 0f, 0f, 0f, alpha = 0f)
            draw(cursor, null, { r -> monitor.toPixels(r).let { Rect(it.x - pixels.x, it.y - pixels.y, it.width, it.height) } }, monitor.scale, monitor)
            renderer.end()
        }
        if (!plane.show(pixels.x, pixels.y, draw)) return intoFrames()
        view.planeCursorKey = key
        view.cursorOnPlane = true
    }

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
        view.showsLock = locked
        var parts = view.takeRepaintParts(age, bounds) ?: return
        // Blur reads around its area: near blur, one part with all it needs.
        if (items.any { it is DrawItem.Blur && parts.any { part -> it.rect.intersect(part) != null } }) {
            parts = listOf(withBlurReach(items, parts.reduce(Rect::union), bounds))
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
                renderer.begin(target, BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], clip = pixels)
            } else {
                renderer.setClip(pixels)
                renderer.drawColor(pixels.x, pixels.y, pixels.width, pixels.height, BACKGROUND[0], BACKGROUND[1], BACKGROUND[2])
            }
            draw(items, near, monitor::toPixels, monitor.scale, monitor)
        }
        renderer.end()
    }

    // Draws [items] (only those touching [repaint]) into the frame begun; [p] turns desktop
    // units into the target's pixels, at [scale].
    private fun draw(items: List<DrawItem>, repaint: Rect?, p: (Rect) -> Rect, scale: Double, monitor: Monitor? = null) {
        val s = scale.toFloat()
        for (item in items) {
            if (repaint != null && item.rect.intersect(repaint) == null) continue
            if (monitor != null && item.only != null && item.only !== monitor) continue // (a sliding workspace of another monitor)
            val r = p(item.rect)
            when (item) {
                is DrawItem.Picture -> renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, item.opaque, item.alpha, item.clip?.let(p), item.radius * s, item.source, item.transform)
                is DrawItem.Color -> renderer.drawColor(r.x, r.y, r.width, r.height, item.red, item.green, item.blue, item.alpha)
                is DrawItem.Shadow -> {
                    val drawn = renderer.drawShadow(p(item.box), item.radius * s, item.softness * s, item.paint, p(item.cutOut), item.cutOutRadius * s, item.alpha, item.time)
                    paintWorks(item.paint, drawn)
                    if (!drawn) renderer.drawShadow(p(item.box), item.radius * s, item.softness * s, dev.caulis.render.Paint.solid(dev.caulis.render.Color(0f, 0f, 0f, 0.45f * item.alpha)), p(item.cutOut), item.cutOutRadius * s)
                }
                is DrawItem.Border -> {
                    val drawn = renderer.drawRoundedRect(r, item.radius * s, item.paint, item.width * s, item.alpha, item.time)
                    paintWorks(item.paint, drawn)
                    if (!drawn) renderer.drawRoundedRect(r, item.radius * s, item.paint.firstColor().let { it.copy(alpha = it.alpha * item.alpha) }, item.width * s)
                }
                is DrawItem.Blur -> renderer.drawBlur(r, item.radius * s, Math.round(item.size * s).coerceAtLeast(1), item.passes, item.tone)
                is DrawItem.Image -> renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, opaque = false, alpha = item.alpha, clip = item.clip?.let(p), cornerRadius = item.radius * s)
                is DrawItem.Custom -> item.paint(r)
                is DrawItem.Tint -> renderer.drawRoundedRect(r, item.radius * s, dev.caulis.render.Color(item.red, item.green, item.blue, item.alpha))
                is DrawItem.Effect -> {
                    val code = item.shader.code
                    val drawn = renderer.drawTextureEffect(item.texture, r.x, r.y, r.width, r.height, item.alpha, code, item.from, item.to, item.progress)
                    shaderWorks(item.shader.name, code, item.shader.fileProblem ?: if (drawn) null else renderer.effectProblem(code) ?: "it does not compile")
                    if (!drawn) {
                        val f = p(item.fallback)
                        renderer.drawTexture(item.texture, f.x, f.y, f.width, f.height, opaque = false, alpha = item.alpha)
                    }
                }
            }
        }
    }

    /**
     * Blur reads the pixels around its area, so they must be fresh in this frame: if anything
     * near a blurred area is redrawn, the whole area and its surroundings are redrawn.
     */
    private fun withBlurReach(items: List<DrawItem>, repaint: Rect, bounds: Rect): Rect {
        var result = repaint
        var grew = true
        while (grew) {
            grew = false
            for (item in items) {
                if (item !is DrawItem.Blur) continue
                val reach = item.reach
                val around = Rect(item.rect.x - reach, item.rect.y - reach, item.rect.width + 2 * reach, item.rect.height + 2 * reach).intersect(bounds) ?: continue
                if (around.intersect(result) == null || result.contains(around)) continue
                result = result.union(around)
                grew = true
            }
        }
        return result
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
            val feedbacks = surface.current.presentationFeedbacks
            if (feedbacks.isEmpty()) continue
            view.inFlight += feedbacks
            feedbacks.clear()
        }
    }

    // Marks everything that appeared, disappeared, moved or changed order since the view's last frame.
    private fun compare(view: DesktopView, items: List<DrawItem>) {
        if (items === view.shown) return // nothing changed since the last comparison
        val before = view.shown.withIndex().associateBy({ it.value.key }, { it })
        val now = items.withIndex().associateBy({ it.value.key }, { it })
        for ((key, old) in before) {
            val new = now[key]
            if (new == null) {
                old.value.areas.forEach(view::damage)
            } else if (new.index != old.index || new.value.differsFrom(old.value)) {
                old.value.areas.forEach(view::damage)
                new.value.areas.forEach(view::damage)
            }
        }
        for ((key, new) in now) if (key !in before) new.value.areas.forEach(view::damage)
        view.shown = items
    }

    // The scene (everything except the cursor), rebuilt only after a change.
    private var scene: List<DrawItem> = emptyList()
    private val sceneVisible = ArrayList<Surface>()
    private var sceneVersion = -1L

    // The scene plus the cursor, rebuilt when the scene changed or the pointer moved.
    private var items: List<DrawItem> = emptyList()
    private var itemsVersion = -1L
    private var itemsPointerX = Double.NaN
    private var itemsPointerY = Double.NaN
    private var itemsCursorVersion = -1L
    private var itemsCursorStart = 0

    /** Everything to draw now, bottom to top, the cursor last. */
    private fun items(): List<DrawItem> {
        if (itemsVersion == version && itemsPointerX == pointerX && itemsPointerY == pointerY && itemsCursorVersion == cursorVersion) return items
        val all = ArrayList<DrawItem>(scene().size + 2)
        all += scene
        visible.clear()
        visible += sceneVisible
        // The frame before a scale change, fading out over the new one (the cursor stays above).
        for ((monitor, fade) in fades) all += DrawItem.Image("$FADE_KEY ${monitor.name}", fade.texture, monitor.area, alpha = fadeAlphas[monitor] ?: 1f)
        val cursorStart = all.size
        itemsCursorStart = cursorStart
        addCursor(all)
        for (i in cursorStart until all.size) (all[i] as? DrawItem.Picture)?.let { visible += it.surface }
        items = all
        itemsVersion = version
        itemsPointerX = pointerX
        itemsPointerY = pointerY
        itemsCursorVersion = cursorVersion
        return all
    }

    /**
     * Windows and popups to draw, bottom to top. Things nobody can see are left out:
     * outside the desktop, or completely behind something opaque.
     */
    private fun scene(): List<DrawItem> {
        if (sceneVersion == version) return scene
        paintsMoving = false
        focusChanging = false
        val all = ArrayList<DrawItem>()
        if (locked) {
            // Nothing of the desktop: a plain colour and the locker's pictures, on every monitor.
            val color = if (lock?.alive == true) lockColor else abandonedLockColor
            for (monitor in monitorList) {
                all += DrawItem.Color("$LOCK_BACKGROUND ${monitor.name}", monitor.area, color.red, color.green, color.blue, covers = listOf(monitor.area))
            }
            for (lockSurface in lockSurfaces()) lockMonitor(lockSurface).area.let { addTree(all, lockSurface.surface, it.x, it.y) }
            sceneVisible.clear()
            for (item in all) if (item is DrawItem.Picture) sceneVisible += item.surface
            scene = all
            sceneVersion = version
            return all
        }
        val now = System.nanoTime()
        val camera = cameraAt(now)
        val placed = placeDirected(now)
        val (fullscreen, normal) = stackingOrder().filter { !directed(it) }.partition { it.fullscreen }
        // Our background on each monitor, below a wallpaper program's (which wins).
        val allMonitors = desktopBounds()
        for (monitor in monitorList) all += backdrops.items(monitor, monitor.workspace.number, pointerX, pointerY, now, allMonitors, largestScale)
        addLayers(all, LayerShell.Layer.BACKGROUND)
        addLayers(all, LayerShell.Layer.BOTTOM)
        addDrawings(all, DrawingLevel.BELOW_WINDOWS)
        // A monitor changing its workspace: how the old and the new one are drawn now. The new
        // one's windows (and the old one's) show only on that monitor while they move.
        class Change(val monitor: Monitor, val frame: WorkspaceFrame)
        val changes = HashMap<Workspace, Change>()
        for ((monitor, slide) in slides.toList()) {
            val animation = animations.workspaceSwitch
            val t = animationProgress(slide.start, millisOf(animation), now)
            if (t == null || inOverview || monitor !in monitorList || monitor.workspace !== slide.to) {
                slides -= monitor
                continue
            }
            val frame = try {
                animation.frame(curveOf(animation).safeAt(t), slide.direction, monitor.area)
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.error("Your workspace animation failed (workspaces change at once): $e", e)
                slides -= monitor
                continue
            }
            changes[slide.to] = Change(monitor, frame)
            // The workspace before, on its way out (unless another monitor shows it now).
            if (monitorList.none { it.workspace === slide.from }) {
                val start = all.size
                val outgoing = Camera(1.0, frame.outgoingDx, frame.outgoingDy)
                for (window in stacking(slide.from)) addWindow(all, window, now, camera = outgoing)
                fade(all, start, frame.outgoingAlpha, monitor)
            }
        }
        fun changeOf(window: Window) = changes.entries.firstOrNull { (workspace, _) -> window in workspace.windows || window in workspace.minimized }?.value
        // The overview's camera is on the monitor with the focus only; a changing workspace moves.
        fun cameraFor(window: Window): Camera {
            if (camera !== Camera.NONE) return if (window in activeWorkspace.windows || window in activeWorkspace.minimized) camera else Camera.NONE
            val change = changeOf(window) ?: return Camera.NONE
            return Camera(1.0, change.frame.incomingDx, change.frame.incomingDy)
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
        addFlights(all, now, FlightKind.CLOSE)
        addDrawings(all, DrawingLevel.ABOVE_WINDOWS)
        addLayers(all, LayerShell.Layer.TOP)
        // Windows on their way into the dock and out of it (above it, so they are seen going into their icon).
        addFlights(all, now, FlightKind.MINIMIZE)
        addFlights(all, now, FlightKind.RESTORE)
        // Fullscreen windows cover panels and bars (not notifications on the overlay layer).
        for (window in fullscreen) addWindowNow(window)
        // Shown scratchpads above every workspace, the last shown on top.
        for (pad in shownPads) {
            val overlay = pad.style as? Scratchpad.Overlay
            if (overlay != null && !pad.leaving) {
                all += DrawItem.Color("dim ${pad.name}", padArea(pad), 0f, 0f, 0f, alpha = overlay.dim)
            }
            for (window in pad.windows) addWindow(all, window, now, leaving = pad.leaving)
        }
        addLayers(all, LayerShell.Layer.OVERLAY)
        for (popup in popups) {
            if (camera !== Camera.NONE) break // menus are not part of the overview
            val (x, y) = popupOrigin(popup)
            addTree(all, popup.surface, x, y)
        }
        if (camera === Camera.NONE) for ((surface, place) in unmanaged) addTree(all, surface, place.first, place.second)
        addDrawings(all, DrawingLevel.ON_TOP)
        // Our own notices above everything (not on a locked screen: nothing of the desktop is).
        for (monitor in monitorList) all += noticeBoard.items(monitor, monitorList, noticeFallback(monitor))
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
        // A moving background goes on only where it is seen (not under a wallpaper program).
        backdrops.sceneBuilt(kept.mapNotNullTo(HashSet()) { item -> (item as? DrawItem.Custom)?.key?.toString()?.substringAfter("background ", "")?.takeIf { it.isNotEmpty() } })
        sceneVisible.clear()
        for (item in kept) if (item is DrawItem.Picture) sceneVisible += item.surface
        placeSurfaces(kept)
        scene = kept
        sceneVersion = version
        return kept
    }

    // The pictures of windows on their way of [kind] (those that arrived are let go).
    private fun addFlights(all: MutableList<DrawItem>, now: Long, kind: FlightKind) {
        for (flight in flights.toList()) {
            if (flight.kind != kind) continue
            val item = flightItem(flight, now)
            if (item == null) {
                flights -= flight
                flight.target.close()
                if (kind == FlightKind.RESTORE) changed() // the window itself is drawn again
                continue
            }
            if (monitorList.any { it.workspace === flight.workspace }) all += item
            // Where the window is drawn now, for shownRect (going away: it is not there any more).
            if (flight.window != null && kind != FlightKind.CLOSE) {
                shownRects[flight.window] = (item as? DrawItem.Effect)?.fallback ?: item.rect
            }
        }
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

    private fun addLayers(all: MutableList<DrawItem>, layer: LayerShell.Layer) {
        for (layerSurface in layerSurfaces) {
            if (!layerSurface.mapped || layerSurface.current.layer != layer) continue
            val surface = layerSurface.surface
            val rect = Rect(layerSurface.x, layerSurface.y, surface.width, surface.height)
            // Everything behind it darker (a launcher in front of the desktop).
            val dim = layerBehaviorOf(layerSurface).dimAround
            if (dim > 0f) all += DrawItem.Tint(EffectKey("dim around", layerSurface), monitorOf(layerSurface).area, 0f, 0f, 0f, 0f, dim)
            val look = addEffects(all, layerSurface, surface, Rect(0, 0, surface.width, surface.height), rect, 1.0, userCode("Your layerEffects", Effects.NONE) { layerEffects(layerSurface) }, focused = true)
            addTree(all, surface, layerSurface.x, layerSurface.y, look = look)
        }
    }

    // A window where it is (or on its way, see glides); a leaving one only while it moves.
    private fun addWindow(all: MutableList<DrawItem>, window: Window, now: Long, leaving: Boolean = false, camera: Camera = Camera.NONE, plain: Boolean = false) {
        // Coming back out of the dock: its picture flies (see flights) until it is there.
        if (!plain && flights.any { it.window === window && it.kind == FlightKind.RESTORE }) {
            shownRects[window] = currentRect(window)
            return
        }
        val visibleRect = window.visibleRect
        val target = currentRect(window)
        val glide = if (plain) null else glides[window]
        val shown = glide?.let { glidePosition(it, target, now) }
        if (glide != null && shown == null) glides -= window
        if (leaving && shown == null) return // gone out of sight
        var world = shown ?: target
        // Tiled windows follow the fingers while the view is dragged.
        if ((dragX != 0.0 || dragY != 0.0) && window.tiled && window in activeWorkspace.windows) {
            world = world.translate(dragX.roundToInt(), dragY.roundToInt())
        }
        shownRects[window] = world
        // Appearing: as the open animation says (only its picture: the window itself is in place).
        var alpha = 1f
        val appearSince = if (plain) null else appearing[window]
        if (appearSince != null) {
            val animation = animations.windowOpen
            val t = animationProgress(appearSince, millisOf(animation), now)
            val frame = if (t == null) null else try {
                animation.frame(Motion(window, world, world, curveOf(animation).safeAt(t), t, monitorOf(window).usableArea))
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.error("Your window animation failed (it is drawn without): $e", e)
                null
            }
            if (frame == null) {
                appearing -= window
                appearingPictures.remove(window)?.close()
            } else if (frame.shader != null) {
                // Through a shader: the whole window as one picture, made anew each frame.
                val picture = pictureOf(window)
                (if (picture != null) appearingPictures.put(window, picture.first) else appearingPictures.remove(window))?.close()
                if (picture != null) {
                    // The picture has the window's shadow around it: every rectangle grows the same.
                    val (_, texture, box) = picture
                    fun around(r: Rect) = Rect(r.x + box.x - world.x, r.y + box.y - world.y, r.width + box.width - world.width, r.height + box.height - world.height)
                    all += frameItem(EffectKey("appearing", window), texture, WindowFrame(around(frame.rect), frame.alpha, frame.shader, around(frame.shaderFrom), around(frame.shaderTo), frame.progress))
                    return
                }
            } else {
                world = frame.rect
                alpha = frame.alpha
            }
        }
        val rect = if (camera === Camera.NONE) world else camera.apply(world)
        screenRects[window] = rect
        // While gliding (or zoomed out), the picture is stretched to the in-between size.
        val scaleX = if (target.width > 0) rect.width.toDouble() / target.width else 1.0
        val scaleY = if (target.height > 0) rect.height.toDouble() / target.height else 1.0
        val fx = faded(if (window.fullscreen) Effects.NONE else userCode("Your windowEffects", effects) { windowEffects(window) }, alpha)
        // The compositor's title bar over it, if it has one: effects go around both.
        val bar = titleHeight(window).let { if (it > 0) Rect(rect.x, rect.y - (it * scaleY).roundToInt(), rect.width, (it * scaleY).roundToInt()) else null }
        val frame = bar?.union(rect) ?: rect
        val look = addEffects(all, window, window.surface, visibleRect, frame, minOf(scaleX, scaleY), fx, focused = window === focusedWindow)
        if (bar != null) all += DrawItem.Image(EffectKey("title", window), titleTexture(window, bar), bar, look.clip, look.radius, alpha = alpha)
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
        shownRects[window] = rect
        screenRects[window] = rect
        val visible = window.visibleRect
        val scaleX = if (visible.width > 0) place.width / visible.width else 1.0
        val scaleY = if (visible.height > 0) place.height / visible.height else 1.0
        val scale = minOf(scaleX, scaleY)
        var fx = faded(if (window.fullscreen) Effects.NONE else userCode("Your windowEffects", effects) { windowEffects(window) }, place.alpha)
        place.cornerRadius?.let { r -> fx = fx.copy(cornerRadius = if (scale > 0) Math.round(r / scale).toInt() else 0) }
        val look = addEffects(all, window, window.surface, visible, rect, scale, fx, focused = window === focusedWindow)
        val clip = when {
            place.clip == null -> look.clip
            look.clip == null -> place.clip
            else -> look.clip.intersect(place.clip) ?: return
        }
        val clipped = Look(clip, look.radius, look.alpha, look.dim)
        addDecorations(all, window, place, above = false)
        addTree(all, window.surface, rect.x - (visible.x * scaleX).roundToInt(), rect.y - (visible.y * scaleY).roundToInt(), scaleX, scaleY, clipped)
        if (look.dim > 0f) all += DrawItem.Tint(EffectKey("dim", window), rect, look.radius, 0f, 0f, 0f, look.dim * place.alpha)
        addDecorations(all, window, place, above = true)
    }

    private fun addTree(
        items: MutableList<DrawItem>, surface: Surface, x: Int, y: Int,
        scaleX: Double = 1.0, scaleY: Double = 1.0, look: Look = Look.PLAIN,
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
    private fun coversOf(opaque: List<Rect>, look: Look): List<Rect> {
        val clip = look.clip ?: return opaque
        val r = Math.ceil(look.radius.toDouble()).toInt()
        val wide = Rect(clip.x, clip.y + r, clip.width, clip.height - 2 * r)
        val tall = Rect(clip.x + r, clip.y, clip.width - 2 * r, clip.height)
        return opaque.flatMap { listOfNotNull(it.intersect(wide), it.intersect(tall)) }
    }

    private fun addCursor(items: MutableList<DrawItem>) {
        if (cursorHidden) return
        val x = pointerX.toInt()
        val y = pointerY.toInt()
        val shape = cursorShapeNow()
        if (shape == null) {
            // The program drew its own cursor (or hid it).
            val own = seat.cursorSurface
            if (own != null && own.hasContent) addTree(items, own, x - seat.cursorHotspotX, y - seat.cursorHotspotY)
            return
        }
        if (shape != shownShape) {
            cursorLog.trace { "Cursor: $shape" }
            shownShape = shape
            shapeSince = System.nanoTime()
        }
        // Loaded in the size it has on screen (the monitor it is on), so it is sharp at any scale.
        val pixels = Math.round(cursorTheme.size * monitorAt(pointerX, pointerY).scale).toInt()
        val cursor = cursorTheme.cursor(shape, pixels) ?: cursorTheme.cursor("default", pixels)
        if (cursor == null) {
            // Only if even Caulis's own cursors could not be read (a broken jar): a plain square.
            items += DrawItem.Color(CURSOR_BORDER, Rect(x, y, 12, 12), 0f, 0f, 0f)
            items += DrawItem.Color(CURSOR_FILL, Rect(x + 1, y + 1, 10, 10), 1f, 1f, 1f)
            return
        }
        val (image, untilNext) = cursor.frameAt((System.nanoTime() - shapeSince) / 1_000_000)
        // Themes have a few sizes; the nearest one is stretched to the size wanted.
        val scale = cursorTheme.size.toDouble() / image.nominalSize
        val rect = Rect(
            x - (image.hotspotX * scale).roundToInt(), y - (image.hotspotY * scale).roundToInt(),
            (image.width * scale).roundToInt().coerceAtLeast(1), (image.height * scale).roundToInt().coerceAtLeast(1),
        )
        items += DrawItem.Image(CURSOR_IMAGE, cursorTexture(image), rect)
        if (cursor.animated) nextCursorFrameIn(untilNext)
    }

    // --- the cursor ------------------------------------------------------------------------

    /**
     * The cursor theme and size, e.g. `CursorTheme.load("Bibata-Modern-Ice", 32)`. By default
     * a theme that came with a library (caulis-cursor-bibata), else `XCURSOR_THEME`, else
     * Caulis's own sharp cursors.
     */
    var cursorTheme: CursorTheme = CursorTheme.load()
        set(value) {
            field = value
            for (texture in cursorTextures.values) texture.close()
            cursorTextures.clear()
            cursorChanged()
        }

    /** Hide the cursor while typing (it comes back when the mouse moves). */
    var hideCursorWhileTyping = false

    /** Hide the cursor when the mouse has not moved for this long, in milliseconds (0 = never). */
    var hideCursorAfterMillis = 0L
        set(value) {
            field = value
            cursorActive()
        }

    // Changes only the cursor makes (a hidden cursor, the next frame of an animated one).
    private var cursorVersion = 0L
    private var cursorHidden = false
    private val cursorTextures = HashMap<dev.caulis.core.cursor.CursorImage, GpuTexture>()
    private var shownShape: String? = null
    private var shapeSince = 0L
    private var cursorFrameTimer: EventLoop.Timer? = null
    private var cursorIdleTimer: EventLoop.Timer? = null

    private fun cursorChanged() {
        cursorVersion++
        onChange()
    }

    /**
     * The cursor shown now, by name ("default", "text", "grabbing", ...), or null while the
     * program under the pointer shows a picture of its own (or none).
     */
    val cursorShape: String? get() = cursorShapeNow()

    // The cursor the window manager shows: its own while it moves or resizes a window or
    // nothing is under the pointer, else what the program there asked for (null = the
    // program's own picture).
    private fun cursorShapeNow(): String? {
        val current = grab
        return when {
            current is Grab.Move -> "grabbing"
            current is Grab.Resize -> resizeCursor(current.edges)
            overlay != null && !locked -> overlay?.cursor ?: "default"
            (pressedWidget != null || hoveredWidget != null) && !locked -> userCode("Your widget (cursor)", "default") { (pressedWidget?.widget ?: hoveredWidget)!!.cursor }
            inOverview -> "default"
            noticeBoard.hovering -> "default" // our notice, not the program below it
            decorationCursor != null -> decorationCursor
            seat.pointerFocus == null -> "default"
            else -> seat.cursorShape
        }
    }

    private fun resizeCursor(edges: Int): String = when (edges) {
        XdgToplevel.ResizeEdge.TOP -> "n-resize"
        XdgToplevel.ResizeEdge.BOTTOM -> "s-resize"
        XdgToplevel.ResizeEdge.LEFT -> "w-resize"
        XdgToplevel.ResizeEdge.RIGHT -> "e-resize"
        XdgToplevel.ResizeEdge.TOP_LEFT -> "nw-resize"
        XdgToplevel.ResizeEdge.TOP_RIGHT -> "ne-resize"
        XdgToplevel.ResizeEdge.BOTTOM_LEFT -> "sw-resize"
        XdgToplevel.ResizeEdge.BOTTOM_RIGHT -> "se-resize"
        else -> "all-resize"
    }

    // Each cursor picture goes to the GPU once.
    private fun cursorTexture(image: dev.caulis.core.cursor.CursorImage): GpuTexture = cursorTextures.getOrPut(image) {
        renderer.createTexture(image.width, image.height).also { texture ->
            java.lang.foreign.Arena.ofConfined().use { arena ->
                // Premultiplied 0xAARRGGBB ints are the BGRA bytes the texture wants.
                texture.upload(arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, *image.pixels), 0, image.height)
            }
        }
    }

    private fun nextCursorFrameIn(millis: Long) {
        if (cursorFrameTimer != null) return
        cursorFrameTimer = compositor.display.loop.after(millis.coerceIn(1, 1000)) {
            cursorFrameTimer = null
            cursorChanged()
        }
    }

    // The mouse was used: the cursor shows again, and the idle countdown starts over.
    private fun cursorActive() {
        cursorIdleTimer?.cancel()
        cursorIdleTimer = null
        if (cursorHidden) {
            cursorHidden = false
            cursorChanged()
        }
        if (hideCursorAfterMillis > 0) {
            cursorIdleTimer = compositor.display.loop.after(hideCursorAfterMillis) {
                cursorIdleTimer = null
                hideCursor()
            }
        }
    }

    private fun hideCursor() {
        if (cursorHidden) return
        cursorHidden = true
        cursorChanged()
    }

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
     * they do not burn the computer's power on pictures nobody sees either.
     */
    fun frameShown(timeMillis: Int) {
        for (surface in visible) if (surface.resource.isAlive) surface.sendFrameDone(timeMillis)
        for (surface in compositor.surfaces) {
            if (surface !in visible && isKeptUpdating(surface)) surface.sendFrameDone(timeMillis)
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
                surface.current.presentationFeedbacks.clear()
            }
            scheduleHiddenRefresh()
        }
    }

    private fun hiddenWaiting(): List<Surface> = compositor.surfaces.filter {
        it.current.frameCallbacks.isNotEmpty() && it !in visible && !isKeptUpdating(it)
    }

    private companion object {
        // The widget levels found before windows and panels, and the one below them.
        val ABOVE_LEVELS = setOf(DrawingLevel.ON_TOP, DrawingLevel.ABOVE_WINDOWS)
        val BELOW_LEVEL = setOf(DrawingLevel.BELOW_WINDOWS)
        val ALL_LEVELS = DrawingLevel.entries.toSet()
        val BACKGROUND = floatArrayOf(0.16f, 0.18f, 0.22f)
        const val FADE_KEY = "the frame before the scale change"
        const val CURSOR_BORDER = "cursor border"
        const val CURSOR_FILL = "cursor fill"
        const val LOCK_BACKGROUND = "lock background"
        const val CURSOR_IMAGE = "cursor"
        const val BTN_LEFT = 0x110

        // Ctrl, Shift, Alt, AltGr, Super (left and right): pressing them alone is not typing.
        val MODIFIER_KEYS = setOf(29, 97, 42, 54, 56, 100, 125, 126)

        /**
         * How long a layout change waits for every window to draw in its new size before
         * moving them anyway (a program that does not answer must not hold up the others).
         */
        const val TRANSACTION_TIMEOUT_MILLIS = 200L

        const val KEY_ESC = 1

        private const val ANCHOR_TOP = 1
        private const val ANCHOR_BOTTOM = 2
        private const val ANCHOR_LEFT = 4
        private const val ANCHOR_RIGHT = 8
        private const val KEYBOARD_NONE = 0
        private const val KEYBOARD_EXCLUSIVE = 1

        /** How often programs nobody sees may draw a frame (they must not freeze completely). */
        const val HIDDEN_REFRESH_MILLIS = 1000L
    }
}

// Java reads the system's fonts the first time text is measured — a second or more on a
// cold start. Done on a thread of its own as soon as a desktop exists, so the first title
// bar or notice does not stop the compositor for it. (Once per program.)
private val fontsWarm = java.util.concurrent.atomic.AtomicBoolean()

private fun warmUpFonts() {
    if (!fontsWarm.compareAndSet(false, true)) return
    Thread.ofPlatform().daemon().name("caulis-fonts").start {
        val log = Logger("render")
        val start = System.nanoTime()
        try {
            val g = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE).createGraphics()
            try {
                for (style in listOf(java.awt.Font.PLAIN, java.awt.Font.BOLD)) {
                    g.font = java.awt.Font(java.awt.Font.SANS_SERIF, style, 13)
                    g.fontMetrics.stringWidth("Caulis Каулис")
                }
            } finally {
                g.dispose()
            }
            log.info { "Fonts ready in ${(System.nanoTime() - start) / 1_000_000} ms" }
        } catch (e: Throwable) {
            log.warn("Fonts could not be read ahead: $e")
        }
    }
}
