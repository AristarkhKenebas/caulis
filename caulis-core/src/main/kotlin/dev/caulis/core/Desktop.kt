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
    private val renderer: Renderer,
    private val seat: Seat,
    private val compositor: Compositor,
    width: Int,
    height: Int,
) : XdgShell.Listener, LayerShell.Listener, SessionLockManager.Listener {

    // --- size and scale ------------------------------------------------------------------------

    /**
     * How many screen pixels one desktop unit is: 1.0, 1.25, 1.5, 2.0, ... Windows, layouts,
     * the pointer and panels are in desktop units; only drawing is in pixels, so a bigger
     * scale makes everything bigger and as sharp as the screen is. Programs are told, and
     * those that can (most today) draw at exactly this scale.
     */
    var scale: Double = 1.0
        set(value) {
            require(value in 0.5..4.0) { "a scale must be between 0.5 and 4, not $value" }
            if (value == field) return
            fadeFromNow() // the old picture melts into the new one instead of jumping
            field = value
            compositor.outputScale = value
            fitToScreen()
            cursorChanged()
        }

    // --- a scale change: the last frame melts into the new layout -------------------------

    // The picture shown before the change, and when it began to fade.
    private var fade: Pair<GpuTexture, Long>? = null
    private var fadeAlpha = 1f

    // Takes the picture on screen now (as a screenshot does); it fades out over the new scale,
    // while programs draw themselves anew underneath. Without animations: no fade.
    private fun fadeFromNow() {
        if (animationMillis <= 0 || views.isEmpty() || pixelWidth <= 0 || pixelHeight <= 0) return
        fade?.first?.close()
        fade = null
        try {
            renderer.createTarget(pixelWidth, pixelHeight).use { target ->
                val view = createView()
                try {
                    render(view, target, age = 0)
                } finally {
                    removeView(view)
                }
                java.lang.foreign.Arena.ofConfined().use { arena ->
                    val pixels = arena.allocate(pixelWidth.toLong() * pixelHeight * 4)
                    target.readPixels(pixels, pixelWidth * 4)
                    val texture = renderer.createTexture(pixelWidth, pixelHeight)
                    texture.upload(pixels, 0, pixelHeight)
                    fade = texture to System.nanoTime()
                    fadeAlpha = 1f
                }
            }
        } catch (e: Exception) {
            renderLog.warn("The picture before the scale change could not be kept (it changes without fading)", e)
        }
        version++
        onChange()
    }

    // True while fading: each frame is more of the new one.
    private fun fading(): Boolean {
        val (texture, since) = fade ?: return false
        val t = (System.nanoTime() - since) / 1_000_000.0 / SCALE_FADE_MILLIS
        if (t >= 1.0) {
            texture.close()
            fade = null
            version++
            return false
        }
        fadeAlpha = (1 - t * t * (3 - 2 * t)).toFloat() // smooth at both ends
        return true
    }

    /** The screen's size in pixels (the desktop is this divided by [scale]). */
    var pixelWidth = width
        private set
    var pixelHeight = height
        private set

    /** The screen is now [pixelWidth] x [pixelHeight] pixels (a monitor's mode, the nested window's size). */
    fun resizeScreen(pixelWidth: Int, pixelHeight: Int) {
        this.pixelWidth = pixelWidth
        this.pixelHeight = pixelHeight
        fitToScreen()
    }

    // The desktop's units follow the pixels and the scale.
    private fun fitToScreen() {
        val logicalWidth = Math.round(pixelWidth / scale).toInt().coerceAtLeast(1)
        val logicalHeight = Math.round(pixelHeight / scale).toInt().coerceAtLeast(1)
        if (logicalWidth != width || logicalHeight != height) {
            width = logicalWidth
            height = logicalHeight
        } else {
            changed()
        }
        for (view in views) view.damageEverything()
    }

    /** A rectangle in desktop units as screen pixels (edges rounded the same way everywhere, so nothing overlaps or gaps). */
    fun toPixels(rect: Rect): Rect {
        val left = px(rect.x)
        val top = px(rect.y)
        return Rect(left, top, px(rect.right) - left, px(rect.bottom) - top)
    }

    private fun px(value: Int): Int = Math.round(value * scale).toInt()

    /** Size of the desktop in desktop units (the screen's pixels divided by [scale]). */
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
        lock?.surfaces?.forEach { it.configure(it.output?.width ?: width, it.output?.height ?: height) }
        arrangeLayers(refit = false)
        for (pad in shownPads) placeScratchpad(pad, appearing = emptySet())
        for (workspace in workspaces.values) {
            for (window in workspace.windows + workspace.minimized) {
                if (!window.configured) continue
                if (window.fullscreen) {
                    window.configure(width, height)
                } else if (window.maximized) {
                    val area = withoutTitle(window, usableArea)
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

    /** A view for one more screen showing this desktop. */
    fun createView(): DesktopView = DesktopView().also { views += it }

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

    override fun windowInfoChanged(window: Window) {
        windowsLog.trace { "Now called $window" }
        windowListChanged()
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

    /** A record of what a screen recording last got (see [captureChanges]). Remove it with [removeCaptureView]. */
    internal fun createCaptureView(): DesktopView = DesktopView().also { captureViews += it }

    internal fun removeCaptureView(view: DesktopView) {
        captureViews -= view
    }

    /**
     * What changed on the desktop since [view] last got a picture (everything the first
     * time), or null if nothing did. Counts as given: the next call starts from now.
     */
    internal fun captureChanges(view: DesktopView, cursor: Boolean): Rect? {
        compare(view, captureItems(cursor))
        return view.takeRepaint(1, Rect(0, 0, width, height))
    }

    // Everything on screen, with or without our cursor.
    private fun captureItems(cursor: Boolean): List<DrawItem> {
        val all = items()
        return if (cursor) all else all.subList(0, itemsCursorStart.coerceAtMost(all.size))
    }

    /**
     * Draws the whole desktop as it is on screen into [target] (as big as the desktop), with
     * or without the [cursor]. For screenshots and screen sharing: what is on screen does not
     * change, and programs are not told about this picture.
     */
    fun capture(target: GpuTarget, cursor: Boolean) {
        val items = captureItems(cursor)
        renderer.begin(target, BACKGROUND[0], BACKGROUND[1], BACKGROUND[2])
        draw(items, null)
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
        renderer.begin(target, 0f, 0f, 0f, alpha = 0f)
        draw(items, null)
        renderer.end()
    }

    /** The size [captureWindow] draws [window] at, in pixels. */
    fun windowPixelSize(window: Window): Pair<Int, Int> {
        val visible = window.visibleRect
        return px(visible.width).coerceAtLeast(1) to px(visible.height).coerceAtLeast(1)
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
                } catch (e: Exception) {
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
    private fun <T> notify(listeners: List<(T) -> Unit>, value: T, event: String) {
        for (listener in listeners.toList()) {
            try {
                listener(value)
            } catch (e: Exception) {
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
        layoutLog.info { "Workspace ${activeWorkspace.number} (${activeWorkspace.layout.name})" }
        notify(workspaceListeners, activeWorkspace, "workspace")
    }

    /** Moves [window] to workspace [number] (it stays where it is on screen if floating). */
    fun moveToWorkspace(window: Window, number: Int) {
        val from = workspaceOf(window) ?: return
        val to = workspace(number)
        if (from === to) return
        layoutLog.info { "$window moves from workspace ${from.number} to $number" }
        val wasFloating = window in from.floating
        from.remove(window)
        from.model.forget(window) // it lives on the other workspace now
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
     * The part of the desktop that panels and docks leave free (their "exclusive zones"):
     * windows are tiled and maximized in here.
     */
    var usableArea = Rect(0, 0, width, height)
        private set

    // The panel with the keyboard (a launcher, or one clicked that wants it), if any.
    private var layerFocus: LayerSurface? = null

    override fun layerCommitted(layer: LayerSurface) {
        if (layer !in layerSurfaces) {
            layersLog.info { "Panel \"${layer.namespace}\" (${layer.surface.resource.client.name}) on the ${layer.current.layer} layer, anchors ${layer.current.anchor}, exclusive zone ${layer.current.exclusiveZone}" }
            layerSurfaces += layer
        }
        arrangeLayers()
        updateLayerKeyboard()
        changed()
    }

    override fun layerUnmapped(layer: LayerSurface) {
        layersLog.info { "Panel \"${layer.namespace}\" is gone" }
        layerSurfaces -= layer
        popups.filter { it.layerParent === layer }.forEach { it.dismiss() }
        if (layerFocus === layer) {
            // It had the keyboard: back to the window (or the next launcher that wants it).
            layerFocus = null
            seat.focusKeyboard(focusedWindow?.surface)
        }
        arrangeLayers()
        updateLayerKeyboard()
        changed()
    }

    // Places all layer surfaces, the way wlroots-based compositors do: first those that
    // reserve space (each takes it from its edge), then the others in what is left.
    private fun arrangeLayers(refit: Boolean = true) {
        val full = Rect(0, 0, width, height)
        var usable = full
        val ordered = layerSurfaces.sortedByDescending { it.current.layer } // overlay first
        for (layer in ordered.filter { it.current.exclusiveZone > 0 }) {
            placeLayer(layer, full)
            usable = reserve(usable, layer)
        }
        for (layer in ordered.filter { it.current.exclusiveZone <= 0 }) {
            placeLayer(layer, if (layer.current.exclusiveZone < 0) full else usable)
        }
        if (usable != usableArea) {
            usableArea = usable
            if (refit) refitWindows()
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
    fun onLockChanged(listener: (Boolean) -> Unit) {
        lockListeners += listener
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
        scratchpads.values.firstOrNull { pad -> try { pad.match(window) } catch (e: Exception) { false } }

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

    private fun showScratchpad(pad: Scratchpad, appearing: Set<Window>) {
        pad.shown = true
        pad.leaving = false
        shownPads -= pad
        shownPads += pad
        placeScratchpad(pad, appearing)
        focus(pad.windows.last())
    }

    private fun hideScratchpad(pad: Scratchpad) {
        pad.leaving = true
        val now = System.nanoTime()
        for (window in pad.windows) {
            val from = shownRects[window] ?: currentRect(window)
            glides[window] = Glide(from, now, to = pad.hiddenRect(from, width, height))
        }
        compositor.display.loop.after(animationMillis) {
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
            val rects = pad.workspace.model.arrange(windows, Rect(0, 0, width, height), windows.lastOrNull())
            return windows.zip(rects).associate { (window, rect) -> window to fit(window, rect) }
        }
        val rect = pad.shownRect(width, height)
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
                glides[window] = Glide(pad.hiddenRect(rect, width, height), now)
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
        val rects = workspace.model.arrange(tiled, usableArea, top)
        val targets = tiled.zip(rects).associate { (window, rect) -> window to fit(window, rect) }
        layoutLog.trace { "Arrange workspace ${workspace.number} (${workspace.layout.name}) in $usableArea: " + targets.entries.joinToString { (w, r) -> "${w.title.take(24)}=$r" } }
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
        if (!done) return false
        arrange(activeWorkspace)
        changed()
        return true
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

    private val cameraMillis: Long get() = (animationMillis * 3 / 2).coerceAtLeast(1)

    private fun cameraAt(now: Long): Camera {
        val t = (now - cameraStart) / (cameraMillis * 1_000_000.0)
        if (t >= 1.0 || animationMillis == 0L) return cameraTo
        val eased = 1 - (1 - t) * (1 - t) * (1 - t)
        return cameraFrom.mix(cameraTo, eased)
    }

    private fun cameraMoving(now: Long) = cameraTo !== cameraFrom && (now - cameraStart) < cameraMillis * 1_000_000

    // Fits all windows of the workspace (and the current screen) into 90% of the screen.
    private fun overviewCamera(): Camera {
        var box = Rect(0, 0, width, height)
        for (window in activeWorkspace.windows) box = box.union(currentRect(window))
        val scale = minOf(1.0, width * 0.9 / box.width, height * 0.9 / box.height)
        return Camera(scale, width / 2.0 - (box.x + box.width / 2.0) * scale, height / 2.0 - (box.y + box.height / 2.0) * scale)
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
        val screen = Rect(0, 0, width, height)
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

    /** Who draws [window]'s title bar now. */
    fun titleBarsOf(window: Window): TitleBars = titleBarsFor?.invoke(window) ?: titleBars

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
        val pixels = toPixels(Rect(0, 0, bar.width, titleBarStyle.height))
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
        } catch (e: Exception) {
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
            val b = RESIZE_BORDER
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
                    if (now - lastBarClick < DOUBLE_CLICK_NANOS) {
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

    // How one window or panel is drawn: its picture clipped and faded, effects below it.
    private class Look(val clip: Rect?, val radius: Float, val alpha: Float) {
        companion object {
            val PLAIN = Look(null, 0f, 1f)
        }
    }

    // Adds the effects that go below [owner]'s picture at [rect] (on screen, zoomed by [scale]),
    // and returns how the picture itself is drawn.
    private fun addEffects(all: MutableList<DrawItem>, owner: Any, surface: Surface, local: Rect, rect: Rect, scale: Double, fx: Effects, focused: Boolean): Look {
        if (fx == Effects.NONE || rect.isEmpty) return Look.PLAIN
        val radius = (fx.cornerRadius * scale).toFloat()
        val alpha = if (focused) fx.opacity else fx.inactiveOpacity
        fx.shadow?.let { shadow ->
            val box = rect.translate((shadow.offsetX * scale).roundToInt(), (shadow.offsetY * scale).roundToInt())
            all += DrawItem.Shadow(EffectKey("shadow", owner), box, radius, (shadow.softness * scale).toFloat().coerceAtLeast(1f), shadow.color, rect, radius)
        }
        fx.blur?.let { blur ->
            // Only worth it where one can see through the window.
            val seeThrough = alpha < 1f || surface.opaqueRects().none { it.contains(local) }
            if (seeThrough) all += DrawItem.Blur(EffectKey("blur", owner), rect, radius, (blur.size * scale).roundToInt().coerceAtLeast(1), blur.passes)
        }
        fx.border?.let { border ->
            val width = (border.width * scale).roundToInt().coerceAtLeast(1)
            val outer = Rect(rect.x - width, rect.y - width, rect.width + 2 * width, rect.height + 2 * width)
            val color = if (focused) border.focused else border.unfocused
            // One pixel more, under the window's edge: its soft edge then blends into the border, not the background.
            all += DrawItem.Border(EffectKey("border", owner), outer, if (radius > 0f) radius + width else 0f, width + 1f, color)
        }
        return Look(if (radius > 0f) rect else null, radius, alpha)
    }

    // --- smooth movement -------------------------------------------------------------------

    /**
     * How long windows take to glide to a new place (a layout change, maximize, a
     * neighbour closing), in milliseconds. 0 = they jump.
     */
    var animationMillis = 220L

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
        if (animationMillis > 0 && shown != null && (window in activeWorkspace.windows || scratchpadOf(window) != null)) {
            glides[window] = Glide(shown, System.nanoTime())
        }
    }

    // Where a gliding window is drawn now: from its start towards its current place, fast
    // at first and gently slowing down. Null when the glide is over.
    private fun glidePosition(glide: Glide, current: Rect, nowNanos: Long): Rect? {
        val target = glide.to ?: current
        val t = (nowNanos - glide.startNanos) / (animationMillis.coerceAtLeast(1) * 1_000_000.0)
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
        val floating = floatingRects[window] ?: cascadeRect(window.visibleRect.width, window.visibleRect.height)
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
        layoutLog.info { "$window: ${if (floating) "floating" else "tiled"}" }
        val workspace = workspaceOf(window) ?: return
        if (floating) {
            if (!window.tiled && window in workspace.floating) return
            floatingRects.getOrPut(window) { cascadeRect(window.visibleRect.width, window.visibleRect.height) }
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
        if (window.maximized && !window.fullscreen) return withoutTitle(window, usableArea).let { it.width to it.height }
        scratchpadFor(window)?.let { pad ->
            window.tiled = pad.style is Scratchpad.Overlay // told in the very first configure
            return padTargets(pad, extra = window).getValue(window).let { it.width to it.height }
        }
        if (window.maximized || window.fullscreen) return width to height
        if (floatRule(window)) return 0 to 0
        val target = arrange(activeWorkspace, extra = window)[window] ?: return 0 to 0
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
        val workspace = activeWorkspace
        val visibleRect = window.visibleRect
        // Its place when floating: its own size, or (if the layout sized it) 2/3 of the desktop.
        val floatingPlace = (if (window.tiled) cascadeRect(width * 2 / 3, height * 2 / 3) else cascadeRect(visibleRect.width, visibleRect.height))
            .let { it.translate(0, titleHeight(window)) } // its title bar stays on screen
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
            window.x = if (window.fullscreen) 0 else usableArea.x
            window.y = if (window.fullscreen) 0 else usableArea.y + titleHeight(window)
        }
        // Its own tile was decided before the first frame: it appears there right away.
        val targets = arrange(workspace)
        targets[window]?.let { window.x = it.x; window.y = it.y }
        focus(window)
        windowsLog.info { "Opened $window (workspace ${workspaceOf(window)?.number ?: "-"}, ${if (window.tiled) "tiled" else "floating"})" }
        notify(windowOpenedListeners, window, "window-opened")
    }

    override fun windowUnmapped(window: Window) {
        windowsLog.info { "Closed $window" }
        titleTextures.remove(window)?.second?.close()
        if (titleHover?.first === window) titleHover = null
        if (titlePressed?.first === window) titlePressed = null
        minimizeTargets -= window
        windowListChanged()
        scratchpadOf(window)?.let { pad ->
            pad.windows -= window
            glides -= window
            shownRects -= window
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
        pending.remove(window)
        floatingRects -= window
        glides -= window
        shownRects -= window
        screenRects -= window
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
        val visibleRect = window.visibleRect
        if (resize.edges and XdgToplevel.ResizeEdge.LEFT != 0) window.x = resize.start.right - visibleRect.width
        if (resize.edges and XdgToplevel.ResizeEdge.TOP != 0) window.y = resize.start.bottom - visibleRect.height
        changed()
    }

    override fun bounds(window: Window): Pair<Int, Int> = width to height

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

    override fun maximizeRequested(window: Window, maximized: Boolean) {
        // A scratchpad window already has its place; it only gets an answer.
        if (scratchpadOf(window) != null) window.configure() else setMaximized(window, maximized)
    }

    override fun fullscreenRequested(window: Window, fullscreen: Boolean) {
        if (scratchpadOf(window) != null) window.configure() else setFullscreen(window, fullscreen)
    }

    // Minimizing a scratchpad window hides its scratchpad.
    override fun minimizeRequested(window: Window) {
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
            // Fullscreen covers everything; maximized leaves panels and docks free.
            val area = if (window.fullscreen) Rect(0, 0, width, height) else withoutTitle(window, usableArea)
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
        // It flies into its dock icon (or sinks down), shrinking, then is gone.
        val shown = shownRects[window]
        if (animationMillis > 0 && shown != null && workspace === activeWorkspace) {
            glides[window] = Glide(shown, System.nanoTime(), to = minimizedRect(window, shown))
        }
        workspace.windows -= window
        workspace.minimized += window
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

    /** The windows on screen bottom to top, as they are drawn (X11 windows are stacked the same way). */
    val stacking: List<Window> get() = stackingOrder()

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
                activeWorkspace = workspace
                arrange(workspace)
                layoutLog.info { "Workspace ${workspace.number} (${workspace.layout.name})" }
                notify(workspaceListeners, workspace, "workspace")
            }
            if (activeWorkspace.minimized.remove(window)) {
                activeWorkspace.windows += window
                window.setMinimized(false)
                // Back out of its dock icon (or up from below), from wherever it is now.
                if (animationMillis > 0) {
                    val place = currentRect(window)
                    val from = if (glides[window] != null) shownRects[window] ?: place else minimizedRect(window, place)
                    glides[window] = Glide(from, System.nanoTime())
                }
            }
        }
        val list = activeWorkspace.windows
        for (other in list) if (other !== window) other.setActivated(false)
        if (window != null) {
            list -= window
            list += window
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

    fun pointerMoved(timeMillis: Int, x: Double, y: Double) {
        inputLog.trace { "Pointer at ${String.format("%.1f, %.1f", x, y)}" }
        cursorActive()
        pointerX = x.coerceIn(0.0, width - 1.0)
        pointerY = y.coerceIn(0.0, height - 1.0)
        grab?.let { current ->
            continueGrab(current)
            onChange()
            return
        }
        if (inOverview) {
            onChange() // only our cursor moves; programs do not see it
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
        if (decoration != null) {
            seat.pointerMotion(timeMillis, null, 0.0, 0.0)
            onChange()
            return
        }
        val hit = surfaceAt(pointerX, pointerY)
        seat.pointerMotion(timeMillis, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
        onChange() // only the cursor moved: the rest of the scene stays as it is
    }

    // Our own cursor over a window's edge (resize arrows), or null.
    private var decorationCursor: String? = null

    /**
     * A mouse button changed. With [passToApps] false it only counts as held (for a move or
     * resize the window manager starts itself, e.g. Super+drag) and no program sees it.
     */
    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean, passToApps: Boolean = true) {
        inputLog.trace { "Button $button ${if (pressed) "pressed" else "released"} at ${pointerX.toInt()},${pointerY.toInt()}${if (passToApps) "" else " (for the window manager)"}" }
        buttonsHeld = (buttonsHeld + if (pressed) 1 else -1).coerceAtLeast(0)
        cursorActive()
        if (!passToApps) return
        if (locked) {
            seat.pointerButton(timeMillis, button, pressed) // only the locker can be under the pointer
            return
        }
        if (inOverview) {
            if (pressed) overviewClick()
            return
        }
        if (grab != null) {
            // The button that started a move or resize ends it; the app does not see it.
            if (!pressed && buttonsHeld == 0) {
                endGrab()
                pointerMoved(timeMillis, pointerX, pointerY) // the app under the pointer gets it back
            }
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
            if (window != null && (window !== focusedWindow || layerFocus != null)) focus(window)
        }
        seat.pointerButton(timeMillis, button, pressed)
    }

    fun pointerAxis(timeMillis: Int, vertical: Boolean, value: Double, value120: Int) {
        inputLog.trace { "Scroll ${if (vertical) "vertical" else "horizontal"} $value ($value120/120)" }
        cursorActive()
        seat.pointerAxis(timeMillis, vertical, value, value120)
    }

    fun key(timeMillis: Int, evdevCode: Int, pressed: Boolean) {
        // Never what is typed into a locker (a password).
        inputLog.trace { if (locked) "A key (not logged while locked)" else "Key $evdevCode ${if (pressed) "pressed" else "released"} → ${seat.keyboardFocus}" }
        // Typing (not just holding Ctrl or Super): the cursor makes way.
        if (pressed && hideCursorWhileTyping && evdevCode !in MODIFIER_KEYS && seat.keyboardFocus != null) hideCursor()
        if (inOverview && pressed) {
            if (evdevCode == KEY_ESC) setOverview(false)
            return // new key presses are not for programs while the overview is open...
        }
        seat.key(timeMillis, evdevCode, pressed) // ...but releases are, or keys would stick
    }

    /** What is under a point: the surface, the point inside it, and the window/popup surface it belongs to. */
    class Hit(val surface: Surface, val localX: Double, val localY: Double, val root: Surface)

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
        for (window in fullscreen.asReversed()) windowHit(window, x, y)?.let { return it }
        layerAt(LayerShell.Layer.TOP, x, y)?.let { return it }
        for (window in normal.asReversed()) windowHit(window, x, y)?.let { return it }
        layerAt(LayerShell.Layer.BOTTOM, x, y)?.let { return it }
        return layerAt(LayerShell.Layer.BACKGROUND, x, y)
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
                val shownAt = view.shown.firstOrNull { it is DrawItem.Picture && it.surface === surface }?.rect ?: continue
                for (change in changes) view.damage(change.translate(shownAt.x, shownAt.y))
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
        if (glides.isNotEmpty() || cameraMoving(System.nanoTime()) || fading()) {
            // Windows are gliding (or the overview zooms): the scene changes every frame until done.
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
        val bounds = Rect(0, 0, width, height)
        view.showsLock = locked
        val repaint = withBlurReach(items, view.takeRepaint(age, bounds) ?: return, bounds)
        // In pixels, one more around it: a fractional scale must not leave a half-drawn edge.
        val clip = Rect(px(repaint.x) - 1, px(repaint.y) - 1, px(repaint.right) - px(repaint.x) + 2, px(repaint.bottom) - px(repaint.y) + 2)
            .intersect(Rect(0, 0, target.width, target.height)) ?: return
        renderLog.trace { "Frame: redraw $repaint ($clip in pixels) of ${target.width}x${target.height} (buffer age $age), ${items.size} things to draw" }
        renderer.begin(target, BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], clip = clip)
        draw(items, repaint)
        renderer.end()
    }

    // Draws [items] (only those touching [repaint]) into the frame begun.
    private fun draw(items: List<DrawItem>, repaint: Rect?) {
        val s = scale.toFloat()
        fun p(rect: Rect): Rect = toPixels(rect) // desktop units -> the target's pixels
        for (item in items) {
            if (repaint != null && item.rect.intersect(repaint) == null) continue
            val r = p(item.rect)
            when (item) {
                is DrawItem.Picture -> renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, item.opaque, item.alpha, item.clip?.let(::p), item.radius * s, item.source)
                is DrawItem.Color -> renderer.drawColor(r.x, r.y, r.width, r.height, item.red, item.green, item.blue, item.alpha)
                is DrawItem.Shadow -> renderer.drawShadow(p(item.box), item.radius * s, item.softness * s, item.color, p(item.cutOut), item.cutOutRadius * s)
                is DrawItem.Border -> renderer.drawRoundedRect(r, item.radius * s, item.color, item.width * s)
                is DrawItem.Blur -> renderer.drawBlur(r, item.radius * s, Math.round(item.size * s).coerceAtLeast(1), item.passes)
                is DrawItem.Image -> renderer.drawTexture(item.texture, r.x, r.y, r.width, r.height, opaque = false, alpha = item.alpha, clip = item.clip?.let(::p), cornerRadius = item.radius * s)
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
        fade?.let { (texture, _) -> all += DrawItem.Image(FADE_KEY, texture, Rect(0, 0, width, height), alpha = fadeAlpha) }
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
        val all = ArrayList<DrawItem>()
        if (locked) {
            // Nothing of the desktop: a plain colour and the locker's pictures.
            val full = Rect(0, 0, width, height)
            val color = if (lock?.alive == true) lockColor else abandonedLockColor
            all += DrawItem.Color(LOCK_BACKGROUND, full, color.red, color.green, color.blue, covers = listOf(full))
            for (lockSurface in lockSurfaces()) addTree(all, lockSurface.surface, 0, 0)
            sceneVisible.clear()
            for (item in all) if (item is DrawItem.Picture) sceneVisible += item.surface
            scene = all
            sceneVersion = version
            return all
        }
        val now = System.nanoTime()
        val camera = cameraAt(now)
        val (fullscreen, normal) = stackingOrder().partition { it.fullscreen }
        addLayers(all, LayerShell.Layer.BACKGROUND)
        addLayers(all, LayerShell.Layer.BOTTOM)
        for (window in normal) addWindow(all, window, now, camera = camera)
        addLayers(all, LayerShell.Layer.TOP)
        // Windows on their way into the dock (above it, so they are seen going into their icon).
        for (window in activeWorkspace.minimized) if (window in glides) addWindow(all, window, now, leaving = true, camera = camera)
        // Fullscreen windows cover panels and bars (not notifications on the overlay layer).
        for (window in fullscreen) addWindow(all, window, now, camera = camera)
        // Shown scratchpads above every workspace, the last shown on top.
        for (pad in shownPads) {
            val overlay = pad.style as? Scratchpad.Overlay
            if (overlay != null && !pad.leaving) {
                all += DrawItem.Color("dim ${pad.name}", Rect(0, 0, width, height), 0f, 0f, 0f, alpha = overlay.dim)
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

        // Leave out what nobody can see; only the rest counts as visible.
        val kept = cullHidden(all, Rect(0, 0, width, height))
        sceneVisible.clear()
        for (item in kept) if (item is DrawItem.Picture) sceneVisible += item.surface
        scene = kept
        sceneVersion = version
        return kept
    }

    private fun addLayers(all: MutableList<DrawItem>, layer: LayerShell.Layer) {
        for (layerSurface in layerSurfaces) {
            if (!layerSurface.mapped || layerSurface.current.layer != layer) continue
            val surface = layerSurface.surface
            val rect = Rect(layerSurface.x, layerSurface.y, surface.width, surface.height)
            val look = addEffects(all, layerSurface, surface, Rect(0, 0, surface.width, surface.height), rect, 1.0, layerEffects(layerSurface), focused = true)
            addTree(all, surface, layerSurface.x, layerSurface.y, look = look)
        }
    }

    // A window where it is (or on its way, see glides); a leaving one only while it moves.
    private fun addWindow(all: MutableList<DrawItem>, window: Window, now: Long, leaving: Boolean = false, camera: Camera = Camera.NONE) {
        val visibleRect = window.visibleRect
        val target = currentRect(window)
        val glide = glides[window]
        val shown = glide?.let { glidePosition(it, target, now) }
        if (glide != null && shown == null) glides -= window
        if (leaving && shown == null) return // gone out of sight
        var world = shown ?: target
        // Tiled windows follow the fingers while the view is dragged.
        if ((dragX != 0.0 || dragY != 0.0) && window.tiled && window in activeWorkspace.windows) {
            world = world.translate(dragX.roundToInt(), dragY.roundToInt())
        }
        shownRects[window] = world
        val rect = if (camera === Camera.NONE) world else camera.apply(world)
        screenRects[window] = rect
        // While gliding (or zoomed out), the picture is stretched to the in-between size.
        val scaleX = if (target.width > 0) rect.width.toDouble() / target.width else 1.0
        val scaleY = if (target.height > 0) rect.height.toDouble() / target.height else 1.0
        val fx = if (window.fullscreen) Effects.NONE else windowEffects(window)
        // The compositor's title bar over it, if it has one: effects go around both.
        val bar = titleHeight(window).let { if (it > 0) Rect(rect.x, rect.y - (it * scaleY).roundToInt(), rect.width, (it * scaleY).roundToInt()) else null }
        val frame = bar?.union(rect) ?: rect
        val look = addEffects(all, window, window.surface, visibleRect, frame, minOf(scaleX, scaleY), fx, focused = window === focusedWindow)
        if (bar != null) all += DrawItem.Image(EffectKey("title", window), titleTexture(window, bar), bar, look.clip, look.radius)
        addTree(all, window.surface, rect.x - (visibleRect.x * scaleX).roundToInt(), rect.y - (visibleRect.y * scaleY).roundToInt(), scaleX, scaleY, look)
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
                items += DrawItem.Picture(surface, texture, rect, surface.opaque, covers, look.alpha, look.clip, look.radius, surface.textureSource)
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
        // Loaded in the size it has on screen, so it is sharp at any scale.
        val pixels = Math.round(cursorTheme.size * scale).toInt()
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
            inOverview -> "default"
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
        val BACKGROUND = floatArrayOf(0.16f, 0.18f, 0.22f)
        const val FADE_KEY = "the frame before the scale change"
        const val SCALE_FADE_MILLIS = 250.0
        const val CURSOR_BORDER = "cursor border"
        const val CURSOR_FILL = "cursor fill"
        const val LOCK_BACKGROUND = "lock background"
        const val CURSOR_IMAGE = "cursor"
        const val RESIZE_BORDER = 6 // around floating windows with our title bar: grab to resize
        const val DOUBLE_CLICK_NANOS = 400_000_000L
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
