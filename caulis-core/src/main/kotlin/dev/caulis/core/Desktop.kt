package dev.caulis.core

import dev.caulis.core.input.Seat
import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Layout
import java.util.TreeMap
import kotlin.math.roundToInt

/**
 * The window manager: workspaces, layouts (floating or tiled — see [Layout]), focus,
 * maximize/fullscreen/minimize, moving and resizing with the pointer, popups. It also draws
 * everything — only the parts that changed (see [DesktopView]).
 */
class Desktop(
    private val renderer: Renderer,
    private val seat: Seat,
    private val compositor: Compositor,
    width: Int,
    height: Int,
) : XdgShell.Listener {

    /** Size of the desktop in pixels. */
    var width = width
        set(value) {
            field = value
            refitWindows()
        }
    var height = height
        set(value) {
            field = value
            refitWindows()
        }

    // The desktop changed size: maximized and fullscreen windows follow it, layouts re-arrange.
    private fun refitWindows() {
        for (workspace in workspaces.values) {
            for (window in workspace.windows + workspace.minimized) {
                if ((window.maximized || window.fullscreen) && window.shell.configured) window.configure(width, height)
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

    /** A view for one more screen showing this desktop. */
    fun createView(): DesktopView = DesktopView().also { views += it }

    fun removeView(view: DesktopView) {
        views -= view
        for (feedback in view.inFlight) feedback.discard()
        view.inFlight.clear()
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
    }

    // --- events for your own code ------------------------------------------------------------

    private val windowOpenedListeners = ArrayList<(Window) -> Unit>()
    private val windowClosedListeners = ArrayList<(Window) -> Unit>()
    private val focusListeners = ArrayList<(Window?) -> Unit>()
    private val workspaceListeners = ArrayList<(Workspace) -> Unit>()

    /** Runs [listener] when a window appears (after it got its place and the focus). */
    fun onWindowOpened(listener: (Window) -> Unit) {
        windowOpenedListeners += listener
    }

    /** Runs [listener] when a window goes away. */
    fun onWindowClosed(listener: (Window) -> Unit) {
        windowClosedListeners += listener
    }

    /** Runs [listener] when another window gets the keyboard (null = none). */
    fun onFocusChanged(listener: (Window?) -> Unit) {
        focusListeners += listener
    }

    /** Runs [listener] when another workspace is shown. */
    fun onWorkspaceChanged(listener: (Workspace) -> Unit) {
        workspaceListeners += listener
    }

    // Calls listeners one by one; a mistake in one of them is reported and the rest go on
    // (your code must never be able to bring the whole desktop down).
    private fun <T> notify(listeners: List<(T) -> Unit>, value: T, event: String) {
        for (listener in listeners.toList()) {
            try {
                listener(value)
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "Your $event handler failed (ignored): $e", e)
            }
        }
    }

    private val log = System.getLogger("caulis")
    private var lastFocused: Window? = null

    // --- workspaces -----------------------------------------------------------------------

    /**
     * The layout new workspaces start with. Setting it changes every workspace at once;
     * windows move to their new places right away (see [setLayout] for one workspace).
     */
    var layout: Layout = Floating
        set(value) {
            field = value
            for (workspace in workspaces.values) setLayout(workspace, value)
        }

    private val workspaces = TreeMap<Int, Workspace>()

    /** The workspace on screen. */
    var activeWorkspace: Workspace = workspace(1)
        private set

    /** Workspaces that exist now (the active one and those with windows), in order. */
    val allWorkspaces: Collection<Workspace> get() = workspaces.values

    // The workspace with [number], created when first needed (there is no limit).
    private fun workspace(number: Int): Workspace = workspaces.getOrPut(number) { Workspace(number, layout) }

    /** Windows on the active workspace, bottom to top. */
    val windows: List<Window> get() = activeWorkspace.windows

    /** Minimized windows of the active workspace. */
    val minimized: List<Window> get() = activeWorkspace.minimized

    /** Shows workspace [number] (created if needed); its top window gets the keyboard. */
    fun switchTo(number: Int) {
        if (number == activeWorkspace.number) return
        endGrab()
        val previous = activeWorkspace
        activeWorkspace = workspace(number)
        // Empty workspaces disappear once you leave them (never the one on screen).
        if (previous.isEmpty) workspaces.remove(previous.number)
        arrange(activeWorkspace)
        focus(activeWorkspace.windows.lastOrNull())
        notify(workspaceListeners, activeWorkspace, "workspace")
    }

    /** Moves [window] to workspace [number] (it stays where it is on screen if floating). */
    fun moveToWorkspace(window: Window, number: Int) {
        val from = workspaceOf(window) ?: return
        val to = workspace(number)
        if (from === to) return
        val wasFloating = window in from.floating
        from.remove(window)
        pending.remove(window)
        to.add(window)
        if (wasFloating) to.floating += window
        window.setActivated(false)
        arrange(from)
        arrange(to)
        if (from === activeWorkspace) focus(from.windows.lastOrNull())
        changed()
    }

    /** Uses [layout] on [workspace]; windows move to their new places right away. */
    fun setLayout(workspace: Workspace, layout: Layout) {
        val wasTiled = workspace.tiledWindows().toSet()
        workspace.layout = layout
        // Windows leaving the layout get their own floating place back.
        for (window in wasTiled - workspace.tiledWindows().toSet()) untile(window)
        arrange(workspace)
        changed()
    }

    private fun workspaceOf(window: Window): Workspace? =
        workspaces.values.firstOrNull { window in it.windows || window in it.minimized }

    // --- placing windows: layouts and transactions ---------------------------------------

    /**
     * Decides whether a new window floats instead of being tiled. By default: dialogs and
     * windows that cannot be resized. Replace it for your own rules (e.g. by app id).
     */
    var floatRule: (Window) -> Boolean = { it.parent != null || it.fixedSize }

    private var cascade = 0

    // The free floating place of each window (also kept while it is tiled or maximized).
    private val floatingRects = HashMap<Window, Rect>()

    // New sizes asked for, positions applied together once every window has drawn in its
    // new size (or after a short wait): so neighbours never jump one after another.
    private val pending = HashMap<Window, Rect>()
    private var pendingTimer: EventLoop.Timer? = null

    // Puts the tiled windows of [workspace] where its layout wants them.
    private fun arrange(workspace: Workspace, extra: Window? = null): Map<Window, Rect> {
        val tiled = workspace.tiledWindows() + listOfNotNull(extra)
        if (tiled.isEmpty()) return emptyMap()
        val top = workspace.windows.lastOrNull { it in tiled }
        val rects = workspace.layout.arrange(tiled, Rect(0, 0, width, height), tiled.indexOf(top))
        val targets = tiled.zip(rects).associate { (window, rect) -> window to fit(window, rect) }
        if (extra == null) place(targets)
        return targets
    }

    // Respects the program's own size limits: a window that cannot be as big (or small) as
    // its tile is centred in it instead of being pushed off screen (R5).
    private fun fit(window: Window, tile: Rect): Rect {
        val w = limit(tile.width, window.minWidth, window.maxWidth)
        val h = limit(tile.height, window.minHeight, window.maxHeight)
        return Rect(tile.x + (tile.width - w) / 2, tile.y + (tile.height - h) / 2, w, h)
    }

    private fun place(targets: Map<Window, Rect>) {
        for ((window, rect) in targets) {
            val visibleRect = window.shell.visibleRect
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
            val visibleRect = window.shell.visibleRect
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

    // --- smooth movement -------------------------------------------------------------------

    /**
     * How long windows take to glide to a new place (a layout change, maximize, a
     * neighbour closing), in milliseconds. 0 = they jump.
     */
    var animationMillis = 220L

    // A window on its way: where it started; the end is wherever the window is now.
    private class Glide(val from: Rect, val startNanos: Long)

    private val glides = HashMap<Window, Glide>()

    // Where each window was drawn last (with any glide), so a new glide starts there.
    private val shownRects = HashMap<Window, Rect>()

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
        if (animationMillis > 0 && shown != null && window in activeWorkspace.windows) {
            glides[window] = Glide(shown, System.nanoTime())
        }
    }

    // Where a gliding window is drawn now: from its start towards its current place, fast
    // at first and gently slowing down. Null when the glide is over.
    private fun glidePosition(glide: Glide, target: Rect, nowNanos: Long): Rect? {
        val t = (nowNanos - glide.startNanos) / (animationMillis * 1_000_000.0)
        if (t >= 1.0) return null
        val eased = 1 - (1 - t) * (1 - t) * (1 - t)
        fun mix(a: Int, b: Int) = (a + (b - a) * eased).roundToInt()
        return Rect(mix(glide.from.x, target.x), mix(glide.from.y, target.y), mix(glide.from.width, target.width), mix(glide.from.height, target.height))
    }

    // Takes a window out of tiling: it gets its floating place and size back.
    private fun untile(window: Window) {
        pending.remove(window)
        if (!window.tiled) return
        window.tiled = false
        val floating = floatingRects[window] ?: cascadeRect(window.shell.visibleRect.width, window.shell.visibleRect.height)
        moveSmoothly(window, floating.x, floating.y)
        window.configure(floating.width, floating.height)
        changed()
    }

    // The next free spot of the cascade (top left, stepping down and right).
    private fun cascadeRect(windowWidth: Int, windowHeight: Int): Rect {
        val step = 32
        val x = (40 + cascade * step).coerceAtMost((width - windowWidth).coerceAtLeast(0))
        val y = (40 + cascade * step).coerceAtMost((height - windowHeight).coerceAtLeast(0))
        cascade = (cascade + 1) % 8
        return Rect(x, y, windowWidth, windowHeight)
    }

    /** True if [window] floats (placed by hand) rather than being tiled by the layout. */
    fun isFloating(window: Window): Boolean = window.tiled.not()

    /** Takes [window] out of the layout (floating) or puts it back (tiled). */
    fun setFloating(window: Window, floating: Boolean) {
        val workspace = workspaceOf(window) ?: return
        if (floating) {
            if (!window.tiled && window in workspace.floating) return
            floatingRects.getOrPut(window) { cascadeRect(window.shell.visibleRect.width, window.shell.visibleRect.height) }
            workspace.floating += window
            untile(window)
        } else {
            floatingRects[window] = currentRect(window)
            workspace.floating -= window
        }
        arrange(workspace)
        changed()
    }

    // --- windows appear and disappear ----------------------------------------------------

    // The layout decides the size before the first frame, so a new window never shows up
    // in a wrong size first (R1). Maximized/fullscreen apps get the whole desktop.
    override fun initialSize(window: Window): Pair<Int, Int> {
        if (window.maximized || window.fullscreen) return width to height
        if (floatRule(window)) return 0 to 0
        val target = arrange(activeWorkspace, extra = window)[window] ?: return 0 to 0
        window.tiled = true
        return target.width to target.height
    }

    override fun windowMapped(window: Window) {
        val workspace = activeWorkspace
        val visibleRect = window.shell.visibleRect
        // Its place when floating: its own size, or (if the layout sized it) 2/3 of the desktop.
        val floatingPlace = if (window.tiled) cascadeRect(width * 2 / 3, height * 2 / 3) else cascadeRect(visibleRect.width, visibleRect.height)
        floatingRects[window] = floatingPlace
        workspace.add(window)
        if (floatRule(window) || workspace.layout === Floating) {
            if (floatRule(window)) workspace.floating += window
            window.tiled = false
            window.x = floatingPlace.x
            window.y = floatingPlace.y
        }
        if (window.maximized || window.fullscreen) {
            // Started maximized: if it is ever restored, it gets a sensible floating place.
            floatingRects[window] = Rect(floatingPlace.x, floatingPlace.y, width * 2 / 3, height * 2 / 3)
            window.x = 0
            window.y = 0
        }
        // Its own tile was decided before the first frame: it appears there right away.
        val targets = arrange(workspace)
        targets[window]?.let { window.x = it.x; window.y = it.y }
        focus(window)
        notify(windowOpenedListeners, window, "window-opened")
    }

    override fun windowUnmapped(window: Window) {
        val workspace = workspaceOf(window)
        workspace?.remove(window)
        pending.remove(window)
        floatingRects -= window
        glides -= window
        shownRects -= window
        if (grab?.window === window) grab = null
        if (workspace != null) arrange(workspace)
        if (seat.keyboardFocus === window.surface) focus(windows.lastOrNull())
        changed()
        notify(windowClosedListeners, window, "window-closed")
    }

    override fun windowCommitted(window: Window) {
        if (window in pending) applyWhenReady()
        // While resizing from the left or top, the opposite edge stays where it was.
        val resize = grab as? Grab.Resize ?: return
        if (resize.window !== window) return
        val visibleRect = window.shell.visibleRect
        if (resize.edges and XdgToplevel.ResizeEdge.LEFT != 0) window.x = resize.start.right - visibleRect.width
        if (resize.edges and XdgToplevel.ResizeEdge.TOP != 0) window.y = resize.start.bottom - visibleRect.height
        changed()
    }

    override fun bounds(window: Window): Pair<Int, Int> = width to height

    // --- window states: maximized, fullscreen, minimized ----------------------------------

    /** The window that has the keyboard, if any. */
    val focusedWindow: Window? get() = windows.lastOrNull()?.takeIf { it.activated }

    override fun maximizeRequested(window: Window, maximized: Boolean) = setMaximized(window, maximized)
    override fun fullscreenRequested(window: Window, fullscreen: Boolean) = setFullscreen(window, fullscreen)
    override fun minimizeRequested(window: Window) = minimize(window)

    /** Makes [window] fill the desktop (or brings it back to its earlier place and size). */
    fun setMaximized(window: Window, maximized: Boolean) {
        if (!window.shell.configured) {
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
        if (!window.shell.configured) {
            window.fullscreen = fullscreen
            return
        }
        if (window.fullscreen == fullscreen) {
            window.configure()
            return
        }
        window.fullscreen = fullscreen
        applyState(window)
        if (fullscreen && window.shell.mapped) focus(window)
    }

    // Puts a window where its states say: full desktop, its tile, or its floating place.
    private fun applyState(window: Window) {
        pending.remove(window)
        val workspace = workspaceOf(window)
        if (window.maximized || window.fullscreen) {
            if (!window.tiled && window.shell.mapped) floatingRects[window] = currentRect(window)
            moveSmoothly(window, 0, 0)
            window.configure(width, height)
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
        val visibleRect = window.shell.visibleRect
        return Rect(window.x, window.y, visibleRect.width, visibleRect.height)
    }

    /** Hides [window]; it comes back with [focus] or [cycleFocus] (Alt+Tab). */
    fun minimize(window: Window) {
        val workspace = workspaceOf(window) ?: return
        if (window !in workspace.windows) return
        workspace.windows -= window
        workspace.minimized += window
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
     * to the right in a scrolling layout. Scrolling layouts then bring it into view.
     */
    fun focusDirection(dx: Int, dy: Int) {
        val from = focusedWindow ?: return focus(windows.lastOrNull())
        val origin = currentRect(from)
        val candidates = windows.filter { it !== from }.map { it to currentRect(it) }.filter { (_, rect) ->
            (dx > 0 && rect.x >= origin.right - 1) || (dx < 0 && rect.right <= origin.x + 1) ||
                (dy > 0 && rect.y >= origin.bottom - 1) || (dy < 0 && rect.bottom <= origin.y + 1)
        }
        val nearest = candidates.minByOrNull { (_, rect) ->
            val distanceX = (rect.x + rect.width / 2) - (origin.x + origin.width / 2)
            val distanceY = (rect.y + rect.height / 2) - (origin.y + origin.height / 2)
            distanceX.toLong() * distanceX + distanceY.toLong() * distanceY
        }
        nearest?.let { focus(it.first) }
    }

    /** Asks the focused window to close. */
    fun closeFocused() {
        focusedWindow?.close()
    }

    /** The window under the desktop point ([x], [y]), if any. */
    fun windowAt(x: Double, y: Double): Window? = surfaceAt(x, y)?.root?.roleObject as? Window

    // Windows bottom to top as drawn: tiled ones, floating ones above them, fullscreen on top.
    private fun stackingOrder(): List<Window> {
        val (fullscreen, others) = windows.partition { it.fullscreen }
        return others.filter { it.tiled } + others.filter { !it.tiled } + fullscreen
    }

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

    override fun moveRequested(window: Window, serial: Int) = beginMove(window)

    override fun resizeRequested(window: Window, serial: Int, edges: Int) = beginResize(window, edges)

    /**
     * Moves [window] with the pointer until the button is released. Only while a button is
     * held (an app cannot grab the pointer on its own). A tiled window starts floating.
     */
    fun beginMove(window: Window) {
        if (buttonsHeld == 0 || window !in windows || window.fullscreen) return
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
        if (buttonsHeld == 0 || window !in windows || window.maximized || window.fullscreen) return
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
        if (ended is Grab.Move) floatingRects[ended.window] = currentRect(ended.window)
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
        grabs += popup
        seat.focusKeyboard(popup.surface)
    }

    /**
     * Raises [window], marks it active and gives it the keyboard (null = nothing focused).
     * A minimized window comes back; a window on another workspace brings that workspace.
     */
    fun focus(window: Window?) {
        if (window != null) {
            val workspace = workspaceOf(window)
            if (workspace != null && workspace !== activeWorkspace) {
                activeWorkspace = workspace
                arrange(workspace)
                notify(workspaceListeners, workspace, "workspace")
            }
            if (activeWorkspace.minimized.remove(window)) activeWorkspace.windows += window
        }
        val list = activeWorkspace.windows
        for (other in list) if (other !== window) other.setActivated(false)
        if (window != null) {
            list -= window
            list += window
            window.setActivated(true)
        }
        seat.focusKeyboard(window?.surface)
        if (window !== lastFocused) {
            lastFocused = window
            notify(focusListeners, window, "focus")
        }
        // Layouts that follow the focus (scrolling) move their windows.
        if (window != null && window.tiled && activeWorkspace.layout !== Floating) arrange(activeWorkspace)
        changed()
    }

    // --- input ------------------------------------------------------------------------

    fun pointerMoved(timeMillis: Int, x: Double, y: Double) {
        pointerX = x.coerceIn(0.0, width - 1.0)
        pointerY = y.coerceIn(0.0, height - 1.0)
        grab?.let { current ->
            continueGrab(current)
            onChange()
            return
        }
        val hit = surfaceAt(pointerX, pointerY)
        seat.pointerMotion(timeMillis, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
        onChange() // only the cursor moved: the rest of the scene stays as it is
    }

    /**
     * A mouse button changed. With [passToApps] false it only counts as held (for a move or
     * resize the window manager starts itself, e.g. Super+drag) and no program sees it.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean, passToApps: Boolean = true) {
        buttonsHeld = (buttonsHeld + if (pressed) 1 else -1).coerceAtLeast(0)
        if (!passToApps) return
        if (grab != null) {
            // The button that started a move or resize ends it; the app does not see it.
            if (!pressed && buttonsHeld == 0) {
                endGrab()
                pointerMoved(timeMillis, pointerX, pointerY) // the app under the pointer gets it back
            }
            return
        }
        if (pressed) {
            val hit = surfaceAt(pointerX, pointerY)
            // A click outside the open menus closes them (and is not passed to the window below).
            if (grabs.isNotEmpty() && (hit == null || popups.none { it.surface === hit.root })) {
                grabs.toList().asReversed().forEach { it.dismiss() }
                grabs.clear()
                return
            }
            val window = hit?.root?.roleObject as? Window
            if (window != null && window !== windows.lastOrNull()) focus(window)
        }
        seat.pointerButton(timeMillis, button, pressed)
    }

    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) =
        seat.pointerAxis(timeMillis, vertical, value, value120)

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) = seat.key(timeMillis, evdevCode, pressed)

    /** What is under a point: the surface, the point inside it, and the window/popup surface it belongs to. */
    class Hit(val surface: Surface, val localX: Double, val localY: Double, val root: Surface)

    private fun surfaceAt(x: Double, y: Double): Hit? {
        for (popup in popups.asReversed()) {
            val (px, py) = popupOrigin(popup)
            hitTree(popup.surface, x - px, y - py, popup.surface)?.let { return it }
        }
        for (window in stackingOrder().asReversed()) {
            val visible = window.shell.visibleRect
            hitTree(window.surface, x - (window.x - visible.x), y - (window.y - visible.y), window.surface)?.let { return it }
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
        val (parentX, parentY) = visibleOrigin(popup.parent)
        val visible = popup.shell.visibleRect
        return (parentX + popup.x - visible.x) to (parentY + popup.y - visible.y)
    }

    // Where the visible top left corner of a window or popup is on the output.
    private fun visibleOrigin(shell: ShellSurface): Pair<Int, Int> = when (val role = shell.role) {
        is Window -> role.x to role.y
        is Popup -> {
            val (parentX, parentY) = visibleOrigin(role.parent)
            (parentX + role.x) to (parentY + role.y)
        }
        else -> 0 to 0
    }

    // --- drawing ----------------------------------------------------------------------

    // A program drew something: mark those parts as changed where the surface is shown.
    private fun surfaceCommitted(surface: Surface) {
        val changes = surface.takeChanges()
        if (changes.isNotEmpty()) {
            for (view in views) {
                val shownAt = view.shown.firstOrNull { it is DrawItem.Picture && it.surface === surface }?.rect ?: continue
                for (change in changes) view.damage(change.translate(shownAt.x, shownAt.y))
            }
        }
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
        if (glides.isNotEmpty()) {
            // Windows are gliding: the scene changes every frame until they arrive.
            version++
            onChange()
        }
        val draw = force || needsRedraw(view)
        val direct = if (draw && !force && presentDirect != null) directCandidate() else null
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
        return draw
    }

    /**
     * The frame last drawn for [view] reached the screen at [nanos] (monotonic clock).
     * Programs that asked are told (presentation-time).
     */
    fun presented(view: DesktopView, nanos: Long, refreshNanos: Long, sequence: Long, flags: Int) {
        Presentation.presented(view.inFlight, nanos, refreshNanos, sequence, flags)
        view.inFlight.clear()
    }

    /**
     * True if [view] needs a new frame: compares what would be drawn now with what it
     * showed last time (windows that moved, opened, closed, the cursor, ...).
     */
    fun needsRedraw(view: DesktopView): Boolean {
        compare(view, items())
        return view.hasChanges
    }

    /**
     * Draws the changed parts into [target], whose picture is [age] frames old (1 = the
     * previous frame; 0 = unknown, then everything is drawn).
     */
    fun render(view: DesktopView, target: GpuTarget, age: Int) {
        val items = items()
        compare(view, items)
        takeFeedbacks(view)
        val repaint = view.takeRepaint(age, Rect(0, 0, target.width, target.height)) ?: return
        renderer.begin(target, BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], clip = repaint)
        for (item in items) {
            if (item.rect.intersect(repaint) == null) continue
            val r = item.rect
            when (item) {
                is DrawItem.Picture -> renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, item.opaque)
                is DrawItem.Color -> renderer.drawColor(r.x, r.y, r.width, r.height, item.red, item.green, item.blue)
            }
        }
        renderer.end()
    }

    /**
     * The buffer of a fullscreen window that is the only thing on screen (no menus, no
     * cursor drawn by us, nothing above it), if it is a GPU buffer exactly as big as the
     * screen. Such a picture can go to the display as it is.
     */
    private fun directCandidate(): DmabufBuffer? {
        val all = items()
        val only = all.singleOrNull() as? DrawItem.Picture ?: return null
        val window = only.surface.roleObject as? Window ?: return null
        if (!window.fullscreen || only.rect != Rect(0, 0, width, height)) return null
        val buffer = only.surface.current.buffer?.dataAs<DmabufBuffer>() ?: return null
        return buffer.takeIf { it.width == width && it.height == height && only.surface.current.scale == 1 }
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
                view.damage(old.value.rect)
            } else if (new.index != old.index || new.value.differsFrom(old.value)) {
                view.damage(old.value.rect)
                view.damage(new.value.rect)
            }
        }
        for ((key, new) in now) if (key !in before) view.damage(new.value.rect)
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

    /** Everything to draw now, bottom to top, the cursor last. */
    private fun items(): List<DrawItem> {
        if (itemsVersion == version && itemsPointerX == pointerX && itemsPointerY == pointerY) return items
        val all = ArrayList<DrawItem>(scene().size + 2)
        all += scene
        visible.clear()
        visible += sceneVisible
        val cursorStart = all.size
        addCursor(all)
        for (i in cursorStart until all.size) (all[i] as? DrawItem.Picture)?.let { visible += it.surface }
        items = all
        itemsVersion = version
        itemsPointerX = pointerX
        itemsPointerY = pointerY
        return all
    }

    /**
     * Windows and popups to draw, bottom to top. Things nobody can see are left out:
     * outside the desktop, or completely behind something opaque.
     */
    private fun scene(): List<DrawItem> {
        if (sceneVersion == version) return scene
        val all = ArrayList<DrawItem>()
        val now = System.nanoTime()
        for (window in stackingOrder()) {
            val visibleRect = window.shell.visibleRect
            val target = currentRect(window)
            val shown = glides[window]?.let { glide -> glidePosition(glide, target, now) ?: target.also { glides -= window } } ?: target
            shownRects[window] = shown
            // While gliding, the picture is stretched to the in-between size.
            val scaleX = if (target.width > 0) shown.width.toDouble() / target.width else 1.0
            val scaleY = if (target.height > 0) shown.height.toDouble() / target.height else 1.0
            addTree(all, window.surface, shown.x - (visibleRect.x * scaleX).roundToInt(), shown.y - (visibleRect.y * scaleY).roundToInt(), scaleX, scaleY)
        }
        for (popup in popups) {
            val (x, y) = popupOrigin(popup)
            addTree(all, popup.surface, x, y)
        }

        // Leave out what nobody can see; only the rest counts as visible.
        val kept = cullHidden(all, Rect(0, 0, width, height))
        sceneVisible.clear()
        for (item in kept) if (item is DrawItem.Picture) sceneVisible += item.surface
        scene = kept
        sceneVersion = version
        return kept
    }

    private fun addTree(items: MutableList<DrawItem>, surface: Surface, x: Int, y: Int, scaleX: Double = 1.0, scaleY: Double = 1.0) {
        val scaled = scaleX != 1.0 || scaleY != 1.0
        for (child in surface.stack) {
            if (child === surface) {
                val texture = surface.texture ?: continue
                val rect = Rect(x, y, (surface.width * scaleX).roundToInt(), (surface.height * scaleY).roundToInt())
                // A stretched picture hides nothing for sure (its edges move every frame).
                val covers = if (scaled) emptyList() else surface.opaqueRects().map { it.translate(x, y) }
                items += DrawItem.Picture(surface, texture, rect, surface.opaque, covers)
            } else {
                val sub = child.subsurface ?: continue
                addTree(items, child, x + (sub.x * scaleX).roundToInt(), y + (sub.y * scaleY).roundToInt(), scaleX, scaleY)
            }
        }
    }

    private fun addCursor(items: MutableList<DrawItem>) {
        val x = pointerX.toInt()
        val y = pointerY.toInt()
        val cursor = seat.cursorSurface
        when {
            cursor != null && cursor.hasContent -> addTree(items, cursor, x - seat.cursorHotspotX, y - seat.cursorHotspotY)
            seat.cursorIsDefault -> {
                // A simple default cursor until cursor themes are supported.
                items += DrawItem.Color(CURSOR_BORDER, Rect(x, y, 12, 12), 0f, 0f, 0f)
                items += DrawItem.Color(CURSOR_FILL, Rect(x + 1, y + 1, 10, 10), 1f, 1f, 1f)
            }
        }
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

    private fun isKeptUpdating(surface: Surface): Boolean {
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
        val BACKGROUND = floatArrayOf(0.16f, 0.18f, 0.22f)
        const val CURSOR_BORDER = "cursor border"
        const val CURSOR_FILL = "cursor fill"

        /**
         * How long a layout change waits for every window to draw in its new size before
         * moving them anyway (a program that does not answer must not hold up the others).
         */
        const val TRANSACTION_TIMEOUT_MILLIS = 200L

        /** How often programs nobody sees may draw a frame (they must not freeze completely). */
        const val HIDDEN_REFRESH_MILLIS = 1000L
    }
}
