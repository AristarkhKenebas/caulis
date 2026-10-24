package dev.caulis.core

import dev.caulis.core.input.Seat
import dev.caulis.event.EventLoop
import dev.caulis.render.GpuTarget
import dev.caulis.render.Rect
import dev.caulis.render.Renderer

/**
 * A simple floating window manager: windows open in a cascade, a click raises and focuses
 * a window, popups close when clicking elsewhere. It also draws everything — only the
 * parts that changed (see [DesktopView]).
 *
 * (Layouts — tiling, Hyprland-like, newm-like — replace this in stage 6.)
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
            changed()
        }
    var height = height
        set(value) {
            field = value
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

    /** Mapped windows, bottom to top. */
    val windows = ArrayList<Window>()

    /** Mapped popups, in the order they opened (children after parents). */
    private val popups = ArrayList<Popup>()

    /** Popups that asked for a grab: a click outside them closes them. */
    private val grabs = ArrayList<Popup>()

    private var cascade = 0

    /** Pointer position on the output. */
    var pointerX = width / 2.0
        private set
    var pointerY = height / 2.0
        private set

    // Surfaces visible right now; they get "frame done" at every refresh.
    private val visible = LinkedHashSet<Surface>()

    // --- windows and popups -----------------------------------------------------------

    override fun windowMapped(window: Window) {
        val visible = window.shell.visibleRect
        // Cascade from the top left, wrapping around before leaving the output.
        val step = 32
        window.x = (40 + cascade * step).coerceAtMost((width - visible.width).coerceAtLeast(0))
        window.y = (40 + cascade * step).coerceAtMost((height - visible.height).coerceAtLeast(0))
        cascade = (cascade + 1) % 8
        windows += window
        focus(window)
    }

    override fun windowUnmapped(window: Window) {
        windows -= window
        if (seat.keyboardFocus === window.surface) focus(windows.lastOrNull())
        changed()
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
        grabs += popup
        seat.focusKeyboard(popup.surface)
    }

    /** Raises [window], marks it active and gives it the keyboard (null = nothing focused). */
    fun focus(window: Window?) {
        for (other in windows) if (other !== window) other.setActivated(false)
        if (window != null) {
            windows -= window
            windows += window
            window.setActivated(true)
        }
        seat.focusKeyboard(window?.surface)
        changed()
    }

    // --- input ------------------------------------------------------------------------

    fun pointerMoved(timeMillis: Int, x: Double, y: Double) {
        pointerX = x.coerceIn(0.0, width - 1.0)
        pointerY = y.coerceIn(0.0, height - 1.0)
        val hit = surfaceAt(pointerX, pointerY)
        seat.pointerMotion(timeMillis, hit?.surface, hit?.localX ?: 0.0, hit?.localY ?: 0.0)
        onChange() // only the cursor moved: the rest of the scene stays as it is
    }

    fun pointerButton(timeMillis: Int, button: Int, pressed: Boolean) {
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
        for (window in windows.asReversed()) {
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
    fun refresh(view: DesktopView, present: ((GpuTarget, Int) -> Unit) -> Unit, force: Boolean = false): Boolean {
        val draw = force || needsRedraw(view)
        if (draw) {
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
        for (window in windows) {
            val visibleRect = window.shell.visibleRect
            addTree(all, window.surface, window.x - visibleRect.x, window.y - visibleRect.y)
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

    private fun addTree(items: MutableList<DrawItem>, surface: Surface, x: Int, y: Int) {
        for (child in surface.stack) {
            if (child === surface) {
                val texture = surface.texture ?: continue
                val covers = surface.opaqueRects().map { it.translate(x, y) }
                items += DrawItem.Picture(surface, texture, Rect(x, y, surface.width, surface.height), surface.opaque, covers)
            } else {
                val sub = child.subsurface ?: continue
                addTree(items, child, x + sub.x, y + sub.y)
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

        /** How often programs nobody sees may draw a frame (they must not freeze completely). */
        const val HIDDEN_REFRESH_MILLIS = 1000L
    }
}
