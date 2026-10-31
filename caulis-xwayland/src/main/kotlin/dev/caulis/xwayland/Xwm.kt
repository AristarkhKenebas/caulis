package dev.caulis.xwayland

import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.Surface
import dev.caulis.core.Window
import dev.caulis.event.EventLoop
import dev.caulis.ffi.c.xcb.Xcb
import dev.caulis.ffi.c.xcb.xcb_configure_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_create_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_destroy_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_map_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_map_request_event_t
import dev.caulis.ffi.c.xcb.xcb_property_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_unmap_notify_event_t
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE

/**
 * The window manager of X11 programs: their windows become [XWindow]s the [desktop]
 * arranges like any other. Talks to Xwayland over [x]; [surfaceFor] finds the Wayland
 * surface Xwayland made for an X window (xwayland-shell serials).
 */
class Xwm internal constructor(
    internal val x: XConnection,
    internal val desktop: Desktop,
    private val loop: EventLoop,
    internal val surfaceFor: (serial: Long) -> Surface?,
    clipboard: DataDeviceManager? = null,
    /** How many of X's pixels one desktop unit is: the screen's scale, or a whole number (a world of its own). */
    private val pixelScale: () -> Double = { desktop.largestScale },
    /** Cuts a title or class to what other programs can take (Display.capForwarded). */
    internal val capText: (String) -> String = { it },
) : AutoCloseable {
    internal val requests = XwmRequests(this)
    internal val props = XwmProperties(this)

    internal val log = Logger("xwayland")
    internal val windows = HashMap<Int, XWindow>()

    // X's stacking order as it reports it (kept in step with the desktop's, see restack).
    private val stack = XStack()
    private val checkWindow: Int
    private var xfixesEvent = -1

    // The X window with the keyboard (as far as we decided), and the number of our last
    // request that gave it: focus changes X reports from before it are out of date.
    internal var focused: XWindow? = null
    internal var lastFocusRequest = 0

    /** The X windows managed now. */
    val all: Collection<XWindow> get() = windows.values

    internal val clipboard: XClipboard

    /** Window types that place themselves above the desktop (menus, tooltips, notifications). */
    internal val popupTypes: Set<Int>

    /** Window types that float (dialogs, tool palettes, splash screens). */
    internal val floatingTypes: Set<Int>

    private var watching = true
    private var focusWatch: AutoCloseable? = null

    internal var warnedOld = false

    // A program taking the keyboard again and again is given up on for a while (no endless tug of war).
    internal val refusals = HashMap<Int, LongArray>() // window -> (count, since, given up until)

    // Events that came in while we waited for a reply outside dispatch() are handled next turn.
    private var drainPosted = false

    // What _NET_CLIENT_LIST_STACKING says now.
    private var listedStacking: List<Int> = emptyList()

    init {
        x.intern(ATOMS)
        popupTypes = POPUP_TYPES.map { atom("_NET_WM_WINDOW_TYPE_$it") }.toSet()
        floatingTypes = FLOATING_TYPES.map { atom("_NET_WM_WINDOW_TYPE_$it") }.toSet()
        // Become the window manager: be told about every window, place them ourselves.
        val mask = Xcb.XCB_EVENT_MASK_SUBSTRUCTURE_REDIRECT() or Xcb.XCB_EVENT_MASK_SUBSTRUCTURE_NOTIFY() or Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE()
        check(x.changeAttributesChecked(x.root, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(mask))) { "another X window manager is running" }
        // Xwayland without a root window needs every window to draw into its own buffer.
        java.lang.foreign.Arena.ofConfined().use { Xcb.xcb_composite_redirect_subwindows(it, x.c, x.root, Xcb.XCB_COMPOSITE_REDIRECT_MANUAL().toByte()) }

        checkWindow = x.createHelperWindow()
        x.setProperty(checkWindow, atom("_NET_SUPPORTING_WM_CHECK"), Xcb.XCB_ATOM_WINDOW(), intArrayOf(checkWindow))
        x.setProperty(x.root, atom("_NET_SUPPORTING_WM_CHECK"), Xcb.XCB_ATOM_WINDOW(), intArrayOf(checkWindow))
        x.setText(checkWindow, atom("_NET_WM_NAME"), atom("UTF8_STRING"), "Caulis")
        x.setProperty(x.root, atom("_NET_SUPPORTED"), Xcb.XCB_ATOM_ATOM(), SUPPORTED.map(::atom).toIntArray())
        x.setProperty(x.root, atom("_NET_ACTIVE_WINDOW"), Xcb.XCB_ATOM_WINDOW(), intArrayOf(XConnection.NONE))
        x.setProperty(x.root, atom("_NET_CLIENT_LIST"), Xcb.XCB_ATOM_WINDOW(), intArrayOf())
        x.setProperty(x.root, atom("_NET_CLIENT_LIST_STACKING"), Xcb.XCB_ATOM_WINDOW(), intArrayOf())

        // Our own connection must not keep Xwayland running once the programs are gone — said
        // before any program can come in (one that comes and goes at once, like xrdb, would
        // otherwise leave Xwayland running for good).
        if (!x.letServerTerminate()) log.trace { "This Xwayland cannot stop by itself (XFixes older than 6)" }

        // Announce ourselves as the window manager (ICCCM): Xwayland only lets X11 programs in after this.
        java.lang.foreign.Arena.ofConfined().use { arena ->
            for (selection in listOf("WM_S0", "_NET_WM_CM_S0")) {
                Xcb.xcb_set_selection_owner(arena, x.c, checkWindow, atom(selection), Xcb.XCB_CURRENT_TIME().toInt())
            }
        }

        xfixesEvent = x.xfixesFirstEvent()
        this.clipboard = XClipboard(x, loop, clipboard, hearsPropertiesOf = { it in windows }, locked = { desktop.locked })
        updateCursor()
        tellResources()
        x.flush()

    }

    internal fun atom(name: String) = x.atom(name)

    /** Desktop units -> X pixels and back (X works in the screen's real pixels). */
    internal fun toPixels(units: Int): Int = Math.round(units * pixelScale()).toInt()
    internal fun toUnits(pixels: Int): Int = Math.round(pixels / pixelScale()).toInt()

    // --- events ----------------------------------------------------------------------------

    private fun dispatch() {
        while (true) {
            val event = x.poll() ?: break
            try {
                handle(event)
            } catch (e: Exception) {
                log.error("Handling an X event failed", e)
            } finally {
                x.free(event)
            }
        }
        x.flush()
        if (x.broken && watching) {
            // Xwayland is gone (it is restarted with the next program): stop listening to a dead socket.
            log.warn("The connection to Xwayland broke")
            watching = false
            loop.remove(dev.caulis.ffi.Fd.borrow(x.fd))
        }
    }

    private fun handle(event: MemorySegment) {
        val type = event.get(JAVA_BYTE, 0).toInt() and 0x7f
        when (type) {
            0 -> log.trace { "X error ${event.get(JAVA_BYTE, 1).toInt() and 0xff} for request ${event.get(JAVA_BYTE, 10).toInt() and 0xff}" }
            Xcb.XCB_CREATE_NOTIFY() -> created(event)
            Xcb.XCB_DESTROY_NOTIFY() -> {
                val id = xcb_destroy_notify_event_t.window(event)
                stack.destroyed(id)
                clipboard.windowGone(id)
                windows.remove(id)?.let(::gone)
            }
            // (A window we never saw created — reparented to the root, as tray icons and some
            // Wine and Java windows are — is taken on now, instead of waiting for ever.)
            Xcb.XCB_MAP_REQUEST() -> xcb_map_request_event_t.window(event).let { id ->
                (windows[id] ?: track(id, overrideRedirect = false, Rect(0, 0, 1, 1)))?.let(::mapRequested)
            }
            REPARENT_NOTIFY -> reparented(event)
            Xcb.XCB_MAP_NOTIFY() -> windows[xcb_map_notify_event_t.window(event)]?.let { window ->
                window.overrideRedirect = xcb_map_notify_event_t.override_redirect(event).toInt() != 0
                window.xMapped = true
                show(window)
            }
            Xcb.XCB_UNMAP_NOTIFY() -> windows[xcb_unmap_notify_event_t.window(event)]?.let(::unmapped)
            Xcb.XCB_CONFIGURE_REQUEST() -> requests.configureRequested(event)
            Xcb.XCB_CONFIGURE_NOTIFY() -> configured(event)
            Xcb.XCB_PROPERTY_NOTIFY() -> if (!clipboard.propertyChanged(event)) {
                windows[xcb_property_notify_event_t.window(event)]?.let { window -> props.propertyChanged(window, xcb_property_notify_event_t.atom(event)) }
            }
            Xcb.XCB_FOCUS_IN() -> requests.focusIn(event)
            Xcb.XCB_CLIENT_MESSAGE() -> requests.clientMessage(event)
            Xcb.XCB_SELECTION_REQUEST(), Xcb.XCB_SELECTION_NOTIFY(), Xcb.XCB_SELECTION_CLEAR() -> clipboard.handle(type, event)
            else -> if (xfixesEvent >= 0 && type == xfixesEvent + Xcb.XCB_XFIXES_SELECTION_NOTIFY()) clipboard.handle(type, event)
        }
    }

    private fun configured(event: MemorySegment) {
        stack.placedAbove(xcb_configure_notify_event_t.window(event), xcb_configure_notify_event_t.above_sibling(event))
        val window = windows[xcb_configure_notify_event_t.window(event)] ?: return
        window.xGeometry = Rect(
            xcb_configure_notify_event_t.x(event).toInt(), xcb_configure_notify_event_t.y(event).toInt(),
            xcb_configure_notify_event_t.width(event).toInt() and 0xffff, xcb_configure_notify_event_t.height(event).toInt() and 0xffff,
        )
        if (window.unmanaged && window.inDesktop) showUnmanaged(window)
    }

    private fun created(event: MemorySegment) {
        val id = xcb_create_notify_event_t.window(event)
        stack.created(id)
        track(
            id, xcb_create_notify_event_t.override_redirect(event).toInt() != 0,
            Rect(
                xcb_create_notify_event_t.x(event).toInt(), xcb_create_notify_event_t.y(event).toInt(),
                xcb_create_notify_event_t.width(event).toInt() and 0xffff, xcb_create_notify_event_t.height(event).toInt() and 0xffff,
            ),
        )
    }

    // ReparentNotify (no generated struct: window at byte 8, parent at 12, override-redirect at 20).
    // A window of ours that moved into another one is no longer a top-level window; one that
    // moved into the root is one now.
    private fun reparented(event: MemorySegment) {
        val id = event.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 8)
        val parent = event.get(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, 12)
        val overrideRedirect = event.get(JAVA_BYTE, 20).toInt() != 0
        if (parent == x.root) {
            if (id !in windows) track(id, overrideRedirect, Rect(0, 0, 1, 1))
        } else {
            windows.remove(id)?.let(::gone)
        }
    }

    // A top-level window from now on (null: one of ours, not a program's).
    private fun track(id: Int, overrideRedirect: Boolean, geometry: Rect): XWindow? {
        if (id == checkWindow || clipboard.owns(id)) return null
        val window = XWindow(this, id, overrideRedirect)
        window.xGeometry = geometry
        windows[id] = window
        // Its properties change later (title, class, ...) and it may take the keyboard: we want to hear it.
        x.changeAttributes(id, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE() or Xcb.XCB_EVENT_MASK_FOCUS_CHANGE()))
        props.readProperties(window)
        log.trace { "Created $window at ${window.xGeometry}${if (window.overrideRedirect) " (places itself)" else ""}" }
        return window
    }

    private fun mapRequested(window: XWindow) {
        props.readProperties(window) {
            props.readEverything(it)
            props.startingState(it)
        }
        x.setProperty(window.id, atom("WM_STATE"), atom("WM_STATE"), intArrayOf(NORMAL_STATE, XConnection.NONE))
        x.map(window.id)
        window.xMapped = true
        log.trace { "Maps $window${if (window.unmanaged) " (places itself)" else ""}" }
        show(window)
    }

    private fun unmapped(window: XWindow) {
        log.trace { "Unmapped $window" }
        window.xMapped = false
        hide(window)
        if (!window.overrideRedirect) {
            x.setProperty(window.id, atom("WM_STATE"), atom("WM_STATE"), intArrayOf(WITHDRAWN_STATE, XConnection.NONE))
            x.deleteProperty(window.id, atom("_NET_WM_STATE"))
        }
    }

    private fun gone(window: XWindow) {
        refusals.remove(window.id)
        hide(window)
        if (focused === window) focused = null
        updateClientList()
    }

    // A window is shown once X mapped it and its surface has a picture (both may come in any order).
    private fun show(window: XWindow) {
        if (!window.xMapped || window.inDesktop) return
        val surface = window.xSurface ?: return log.trace { "$window waits for its surface" }
        if (!surface.hasContent) return log.trace { "$window waits for its picture" }
        if (window.unmanaged) {
            showUnmanaged(window)
            window.inDesktop = true
            window.shownUnmanaged = true
            restack()
            return
        }
        // The layout may want another size than the program's own (tiles).
        val (width, height) = desktop.initialSize(window)
        if (width > 0 && height > 0) window.configure(width, height)
        window.inDesktop = true
        window.shownUnmanaged = false
        desktop.windowMapped(window)
        updateClientList()
        restack()
    }

    private fun hide(window: XWindow) {
        if (!window.inDesktop) return
        window.inDesktop = false
        val surface = window.xSurface
        // As it was shown (its type may have changed since: a window shown as one must go as one).
        if (window.shownUnmanaged) {
            surface?.let(desktop::hideUnmanaged) // gives the keyboard back if it had it
        } else {
            desktop.windowUnmapped(window)
        }
    }

    private fun showUnmanaged(window: XWindow) {
        val surface = window.xSurface ?: return
        desktop.showUnmanaged(surface, toUnits(window.xGeometry.x), toUnits(window.xGeometry.y))
    }

    /** Xwayland made [surface] for the window that has (or will get) [serial]. */
    internal fun surfaceAssociated(serial: Long, surface: Surface) {
        val window = windows.values.firstOrNull { it.serial == serial } ?: return
        associate(window, surface)
    }

    internal fun associate(window: XWindow, surface: Surface) {
        if (window.xSurface === surface) return
        window.xSurface = surface
        surface.claimXwaylandRole(window)
        surface.nativeScale = pixelScale() // its pictures are in X's pixels
        surface.onCommit {
            if (window.xSurface !== surface) return@onCommit
            if (!window.inDesktop) show(window) else if (!window.unmanaged) desktop.windowCommitted(window)
        }
        // Xwayland makes a new surface each time a window is shown again: this one is done.
        surface.resource.onDestroy {
            if (window.xSurface !== surface) return@onDestroy
            log.trace { "$window lost its surface" }
            hide(window)
            window.xSurface = null
        }
        log.trace { "$window is surface ${surface.resource}" }
        show(window)
        drainSoon()
    }

    // Windows of one X connection share the high bits of their ids.
    internal fun sameProgram(a: XWindow, b: XWindow) = (a.id xor b.id) and x.resourceIdMask.inv() == 0

    // --- what the window manager tells X ---------------------------------------------------

    private fun drainSoon() {
        if (drainPosted) return
        drainPosted = true
        loop.post {
            drainPosted = false
            dispatch()
        }
    }

    /** Puts the X window where (and as big as) the desktop has it, in X's pixels. */
    internal fun place(window: XWindow) {
        if (window.unmanaged) return
        val (width, height) = window.configuredSize
        val px = toPixels(window.x)
        val py = toPixels(window.y)
        val pw = toPixels(width).coerceAtLeast(1)
        val ph = toPixels(height).coerceAtLeast(1)
        val mask = Xcb.XCB_CONFIG_WINDOW_X() or Xcb.XCB_CONFIG_WINDOW_Y() or Xcb.XCB_CONFIG_WINDOW_WIDTH() or Xcb.XCB_CONFIG_WINDOW_HEIGHT() or
            Xcb.XCB_CONFIG_WINDOW_BORDER_WIDTH()
        x.configure(window.id, mask, intArrayOf(px, py, pw, ph, 0))
        // Programs that get no real ConfigureNotify (nothing changed) still expect one.
        x.sendConfigureNotify(window.id, px, py, pw, ph)
        x.flush()
    }

    /** Tells the program its states (_NET_WM_STATE). */
    internal fun updateState(window: XWindow) {
        if (window.unmanaged) return
        val states = buildList {
            if (window.fullscreen) add(atom("_NET_WM_STATE_FULLSCREEN"))
            if (window.maximized) {
                add(atom("_NET_WM_STATE_MAXIMIZED_VERT"))
                add(atom("_NET_WM_STATE_MAXIMIZED_HORZ"))
            }
            if (window.activated) add(atom("_NET_WM_STATE_FOCUSED"))
            if (window.modal) add(atom("_NET_WM_STATE_MODAL"))
            if (window.inDesktop && desktop.isMinimized(window)) add(atom("_NET_WM_STATE_HIDDEN"))
        }
        x.setProperty(window.id, atom("_NET_WM_STATE"), Xcb.XCB_ATOM_ATOM(), states.toIntArray())
        x.flush()
    }

    /** The desktop minimized [window] or brought it back: the program hears it (ICCCM iconic state). */
    internal fun minimized(window: XWindow, minimized: Boolean) {
        if (window.unmanaged || !window.xMapped) return
        x.setProperty(window.id, atom("WM_STATE"), atom("WM_STATE"), intArrayOf(if (minimized) ICONIC_STATE else NORMAL_STATE, XConnection.NONE))
        updateState(window)
    }

    /** Gives the keyboard to [window] in X (or to nobody), and raises it. */
    internal fun focus(window: XWindow?) {
        if (window === focused) return
        focused = window
        giveFocus(window)
    }

    internal fun giveFocus(window: XWindow?) {
        if (window == null) {
            // Not "None": that turns X's keyboard off, and menus' keyboard grabs with it.
            lastFocusRequest = x.focus(XConnection.POINTER_ROOT)
            x.setProperty(x.root, atom("_NET_ACTIVE_WINDOW"), Xcb.XCB_ATOM_WINDOW(), intArrayOf(XConnection.NONE))
        } else {
            if (atom("WM_TAKE_FOCUS") in window.protocols) {
                x.sendClientMessage(window.id, atom("WM_PROTOCOLS"), intArrayOf(atom("WM_TAKE_FOCUS"), Xcb.XCB_CURRENT_TIME().toInt()))
            }
            // "input = false" programs take it themselves when asked.
            if (window.acceptsFocus) lastFocusRequest = x.focus(window.id)
            x.setProperty(x.root, atom("_NET_ACTIVE_WINDOW"), Xcb.XCB_ATOM_WINDOW(), intArrayOf(window.id))
        }
        x.flush()
    }

    // X stacks its windows as the desktop draws them (X decides by it which window a click is
    // for), with the self-placing ones (menus) on top; only windows out of place are moved.
    private fun restack() {
        val managed = desktop.stacking.filterIsInstance<XWindow>().filter { it.inDesktop && it.xMapped }.map { it.id }
        // Menus stay on top, in the order they put themselves.
        val above = stack.windows.filter { id -> windows[id]?.let { it.unmanaged && it.inDesktop } == true }
        val moves = stack.moves(managed + above)
        for (move in moves) {
            val mode = if (move.above) Xcb.XCB_STACK_MODE_ABOVE() else Xcb.XCB_STACK_MODE_BELOW()
            x.configure(move.window, Xcb.XCB_CONFIG_WINDOW_SIBLING() or Xcb.XCB_CONFIG_WINDOW_STACK_MODE(), intArrayOf(move.sibling, mode))
        }
        if (moves.isNotEmpty()) log.trace { "Restacked ${moves.size} of ${managed.size + above.size} windows" }
        if (managed != listedStacking) {
            listedStacking = managed
            x.setProperty(x.root, atom("_NET_CLIENT_LIST_STACKING"), Xcb.XCB_ATOM_WINDOW(), managed.toIntArray())
        }
    }

    /** Asks the program to close [window] (WM_DELETE_WINDOW), or ends it if it cannot be asked. */
    internal fun close(window: XWindow) {
        if (atom("WM_DELETE_WINDOW") in window.protocols) {
            x.sendClientMessage(window.id, atom("WM_PROTOCOLS"), intArrayOf(atom("WM_DELETE_WINDOW"), Xcb.XCB_CURRENT_TIME().toInt()))
        } else {
            x.kill(window.id)
        }
        x.flush()
    }

    private fun updateClientList() {
        x.setProperty(x.root, atom("_NET_CLIENT_LIST"), Xcb.XCB_ATOM_WINDOW(), windows.values.filter { it.inDesktop && !it.unmanaged }.map { it.id }.toIntArray())
    }

    /**
     * The cursor over X windows that choose none: the compositor's, at the screen's scale
     * (otherwise X shows its old black cross).
     */
    internal fun updateCursor() {
        val theme = desktop.cursorTheme
        val pixels = Math.round(theme.size * pixelScale()).toInt()
        val image = (theme.cursor("default", pixels) ?: theme.cursor("left_ptr", pixels))?.frames?.firstOrNull()
            ?: return log.trace { "No default cursor in ${theme.name} for X" }
        if (!x.setRootCursor(image.width, image.height, image.hotspotX, image.hotspotY, image.pixels)) {
            log.trace { "X cannot show colour cursors: it keeps its own" }
        }
        x.flush()
    }

    /**
     * Tells X11 programs the scale in the X resources (what `xrdb` would set): the DPI and the
     * cursor's theme and size in X's pixels. Programs read them when they start.
     */
    internal fun tellResources() {
        val theme = desktop.cursorTheme
        val resources = buildString {
            appendLine("Xft.dpi:\t${Math.round(96 * pixelScale())}")
            appendLine("Xcursor.size:\t${Math.round(theme.size * pixelScale())}")
            appendLine("Xcursor.theme:\t${theme.name}")
        }
        x.setText(x.root, Xcb.XCB_ATOM_RESOURCE_MANAGER(), Xcb.XCB_ATOM_STRING(), resources)
        x.flush()
        log.trace { "X resources: ${resources.trim().replace("\n", ", ").replace("\t", " ")}" }
    }

    /** The scale changed: X surfaces are in pixels of the new scale, windows go to their new places. */
    fun rescaled() {
        for (window in windows.values) {
            window.xSurface?.nativeScale = pixelScale()
            if (window.inDesktop) place(window)
            if (window.unmanaged && window.inDesktop) showUnmanaged(window)
            props.readSizeHints(window)
        }
        updateCursor()
        tellResources()
    }

    override fun close() {
        focusWatch?.close() // the desktop no longer tells a window manager that is gone
        // Its windows leave the desktop now, while the X connection still works (the desktop
        // arranging and focusing the others may ask X windows to move).
        for (window in windows.values.toList()) {
            try {
                hide(window)
            } catch (e: Exception) {
                log.trace { "Hiding $window at the end failed: $e" }
            }
        }
        windows.clear()
        clipboard.close()
        if (watching) loop.remove(dev.caulis.ffi.Fd.borrow(x.fd))
        watching = false
        x.close()
    }

    // Last in the class: from here on events are handled, and they use all the state above.
    init {
        loop.add(dev.caulis.ffi.Fd.borrow(x.fd)) { dispatch() }
        // Waiting for replies above may have read events into XCB's queue already; the socket
        // will not say so again: handle them now.
        dispatch()
        // Focus moving to a Wayland window takes it away from the X ones (their menus close).
        focusWatch = desktop.onFocusChanged { window ->
            if (window !is XWindow || windows[window.id] !== window) focus(null) // a Wayland window, or one of another X server
            restack() // the focused window may have come to the front
            x.flush()
        }
        log.info("Window manager of X11 programs ready")
    }

}

internal const val REPARENT_NOTIFY = 21 // (XCB_REPARENT_NOTIFY)
internal const val NORMAL_STATE = 1
internal const val ICONIC_STATE = 3
internal const val WITHDRAWN_STATE = 0
internal const val P_MIN_SIZE = 16
internal const val P_MAX_SIZE = 32
internal const val P_BASE_SIZE = 256
internal const val INPUT_HINT = 1
internal const val MOTIF_DECORATIONS = 2
internal const val DECOR_ALL = 1
internal const val DECOR_TITLE = 8
internal const val MOVERESIZE_MOVE = 8
internal const val MAX_REFUSALS = 3

// _NET_WM_MOVERESIZE directions 0..7 (top-left, top, ... left) as xdg resize edges.
internal val RESIZE_EDGES = intArrayOf(5, 1, 9, 8, 10, 2, 6, 4)

internal val POPUP_TYPES = listOf("MENU", "DROPDOWN_MENU", "POPUP_MENU", "TOOLTIP", "NOTIFICATION", "COMBO", "DND")
internal val FLOATING_TYPES = listOf("DIALOG", "UTILITY", "SPLASH", "TOOLBAR")

internal val SUPPORTED = listOf(
    "_NET_SUPPORTED", "_NET_SUPPORTING_WM_CHECK", "_NET_WM_NAME", "_NET_ACTIVE_WINDOW", "_NET_CLIENT_LIST",
    "_NET_CLIENT_LIST_STACKING", "_NET_WM_STATE", "_NET_WM_STATE_FULLSCREEN", "_NET_WM_STATE_MAXIMIZED_VERT",
    "_NET_WM_STATE_MAXIMIZED_HORZ", "_NET_WM_STATE_HIDDEN", "_NET_WM_STATE_FOCUSED", "_NET_WM_MOVERESIZE",
    "_NET_CLOSE_WINDOW", "_NET_WM_WINDOW_TYPE", "_NET_WM_STATE_MODAL",
)

internal val ATOMS = SUPPORTED + listOf(
    "WM_S0", "_NET_WM_CM_S0", "UTF8_STRING", "WM_PROTOCOLS", "WM_DELETE_WINDOW", "WM_TAKE_FOCUS", "WM_STATE", "WM_CHANGE_STATE",
    "WL_SURFACE_SERIAL", "WL_SURFACE_ID", "_MOTIF_WM_HINTS", "_NET_WM_PID", "CLIPBOARD", "TARGETS", "TEXT", "INCR",
) + (POPUP_TYPES + FLOATING_TYPES + "NORMAL").distinct().map { "_NET_WM_WINDOW_TYPE_$it" }
