package dev.caulis.xwayland

import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.Surface
import dev.caulis.core.Window
import dev.caulis.event.EventLoop
import dev.caulis.ffi.c.xcb.Xcb
import dev.caulis.ffi.c.xcb.xcb_client_message_event_t
import dev.caulis.ffi.c.xcb.xcb_configure_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_configure_request_event_t
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
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT

/**
 * The window manager of X11 programs: their windows become [XWindow]s the [desktop]
 * arranges like any other. Talks to Xwayland over [x]; [surfaceFor] finds the Wayland
 * surface Xwayland made for an X window (xwayland-shell serials).
 */
class Xwm internal constructor(
    private val x: XConnection,
    private val desktop: Desktop,
    private val loop: EventLoop,
    private val surfaceFor: (serial: Long) -> Surface?,
    clipboard: DataDeviceManager? = null,
    /** How many of X's pixels one desktop unit is: the screen's scale, or a whole number (a world of its own). */
    private val pixelScale: () -> Double = { desktop.scale },
) : AutoCloseable {

    private val log = Logger("xwayland")
    private val windows = HashMap<Int, XWindow>()

    // X's stacking order as it reports it (kept in step with the desktop's, see restack).
    private val stack = XStack()
    private val checkWindow: Int
    private var xfixesEvent = -1

    // The X window with the keyboard (as far as we decided), and the number of our last
    // request that gave it: focus changes X reports from before it are out of date.
    private var focused: XWindow? = null
    private var lastFocusRequest = 0

    /** The X windows managed now. */
    val all: Collection<XWindow> get() = windows.values

    internal val clipboard: XClipboard

    /** Window types that place themselves above the desktop (menus, tooltips, notifications). */
    internal val popupTypes: Set<Int>

    /** Window types that float (dialogs, tool palettes, splash screens). */
    internal val floatingTypes: Set<Int>

    private var watching = true
    private var focusWatch: AutoCloseable? = null

    private var warnedOld = false

    // A program taking the keyboard again and again is given up on for a while (no endless tug of war).
    private val refusals = HashMap<Int, LongArray>() // window -> (count, since, given up until)

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

        // Announce ourselves as the window manager (ICCCM): Xwayland only lets X11 programs in after this.
        java.lang.foreign.Arena.ofConfined().use { arena ->
            for (selection in listOf("WM_S0", "_NET_WM_CM_S0")) {
                Xcb.xcb_set_selection_owner(arena, x.c, checkWindow, atom(selection), Xcb.XCB_CURRENT_TIME().toInt())
            }
        }

        xfixesEvent = x.xfixesFirstEvent()
        this.clipboard = XClipboard(x, loop, clipboard) { it in windows }
        // Our own connection does not keep Xwayland running once the programs are gone.
        if (!x.letServerTerminate()) log.trace { "This Xwayland cannot stop by itself (XFixes older than 6)" }
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
            Xcb.XCB_MAP_REQUEST() -> windows[xcb_map_request_event_t.window(event)]?.let(::mapRequested)
            Xcb.XCB_MAP_NOTIFY() -> windows[xcb_map_notify_event_t.window(event)]?.let { window ->
                window.overrideRedirect = xcb_map_notify_event_t.override_redirect(event).toInt() != 0
                window.xMapped = true
                show(window)
            }
            Xcb.XCB_UNMAP_NOTIFY() -> windows[xcb_unmap_notify_event_t.window(event)]?.let(::unmapped)
            Xcb.XCB_CONFIGURE_REQUEST() -> configureRequested(event)
            Xcb.XCB_CONFIGURE_NOTIFY() -> configured(event)
            Xcb.XCB_PROPERTY_NOTIFY() -> if (!clipboard.propertyChanged(event)) {
                windows[xcb_property_notify_event_t.window(event)]?.let { window -> propertyChanged(window, xcb_property_notify_event_t.atom(event)) }
            }
            Xcb.XCB_FOCUS_IN() -> focusIn(event)
            Xcb.XCB_CLIENT_MESSAGE() -> clientMessage(event)
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
        if (id == checkWindow || clipboard.owns(id)) return
        val window = XWindow(this, id, xcb_create_notify_event_t.override_redirect(event).toInt() != 0)
        window.xGeometry = Rect(
            xcb_create_notify_event_t.x(event).toInt(), xcb_create_notify_event_t.y(event).toInt(),
            xcb_create_notify_event_t.width(event).toInt() and 0xffff, xcb_create_notify_event_t.height(event).toInt() and 0xffff,
        )
        windows[id] = window
        // Its properties change later (title, class, ...) and it may take the keyboard: we want to hear it.
        x.changeAttributes(id, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE() or Xcb.XCB_EVENT_MASK_FOCUS_CHANGE()))
        readProperties(window)
        log.trace { "Created $window at ${window.xGeometry}${if (window.overrideRedirect) " (places itself)" else ""}" }
    }

    private fun mapRequested(window: XWindow) {
        readProperties(window) {
            readEverything(it)
            startingState(it)
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
            restack()
            return
        }
        // The layout may want another size than the program's own (tiles).
        val (width, height) = desktop.initialSize(window)
        if (width > 0 && height > 0) window.configure(width, height)
        window.inDesktop = true
        desktop.windowMapped(window)
        updateClientList()
        restack()
    }

    private fun hide(window: XWindow) {
        if (!window.inDesktop) return
        window.inDesktop = false
        val surface = window.xSurface
        if (window.unmanaged) {
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

    private fun associate(window: XWindow, surface: Surface) {
        if (window.xSurface === surface) return
        window.xSurface = surface
        surface.roleObject = window
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

    // --- properties ------------------------------------------------------------------------

    // Properties fetched together for readProperties (one round trip), else read one by one.
    private var fetched: Map<Int, XConnection.Property?>? = null

    private fun prop(window: XWindow, atom: Int): XConnection.Property? =
        fetched?.let { if (atom in it) return it[atom] }.let { x.property(window.id, atom) }

    private val windowProperties: List<Int> by lazy {
        listOf(
            atom("_NET_WM_NAME"), Xcb.XCB_ATOM_WM_NAME(), Xcb.XCB_ATOM_WM_CLASS(), Xcb.XCB_ATOM_WM_TRANSIENT_FOR(),
            Xcb.XCB_ATOM_WM_NORMAL_HINTS(), Xcb.XCB_ATOM_WM_HINTS(), atom("WM_PROTOCOLS"), atom("_NET_WM_WINDOW_TYPE"),
            atom("_MOTIF_WM_HINTS"), atom("_NET_WM_PID"), atom("_NET_WM_STATE"),
        )
    }

    // Fetches all the window's properties together, then reads them ([read]: everything).
    private fun readProperties(window: XWindow, read: (XWindow) -> Unit = ::readEverything) {
        fetched = x.properties(window.id, windowProperties)
        try {
            read(window)
        } finally {
            fetched = null
        }
    }

    private fun readEverything(window: XWindow) {
        readTitle(window)
        readClass(window)
        readParent(window)
        readSizeHints(window)
        readHints(window)
        readProtocols(window)
        readTypes(window)
        readMotif(window)
        window.netWmPid = prop(window, atom("_NET_WM_PID"))?.ints()?.firstOrNull() ?: 0
    }

    // Only what changed is read again (programs change _NET_WM_USER_TIME with every key).
    private fun propertyChanged(window: XWindow, atom: Int) {
        when (atom) {
            atom("_NET_WM_NAME"), Xcb.XCB_ATOM_WM_NAME() -> readTitle(window).also { infoChanged(window) }
            Xcb.XCB_ATOM_WM_CLASS() -> readClass(window).also { infoChanged(window) }
            Xcb.XCB_ATOM_WM_TRANSIENT_FOR() -> readParent(window)
            Xcb.XCB_ATOM_WM_NORMAL_HINTS() -> readSizeHints(window)
            Xcb.XCB_ATOM_WM_HINTS() -> readHints(window)
            atom("WM_PROTOCOLS") -> readProtocols(window)
            atom("_NET_WM_WINDOW_TYPE") -> readTypes(window)
            atom("_MOTIF_WM_HINTS") -> readMotif(window).also { infoChanged(window) }
        }
    }

    private fun infoChanged(window: XWindow) {
        if (window.inDesktop && !window.unmanaged) desktop.windowInfoChanged(window)
    }

    private fun readTitle(window: XWindow) {
        val value = prop(window, atom("_NET_WM_NAME")) ?: prop(window, Xcb.XCB_ATOM_WM_NAME()) ?: return
        // Old programs give WM_NAME as STRING: Latin-1, not UTF-8.
        val title = if (value.type == Xcb.XCB_ATOM_STRING()) String(value.bytes, Charsets.ISO_8859_1).trimEnd('\u0000') else value.text()
        window.describe(title)
    }

    private fun readClass(window: XWindow) {
        // WM_CLASS is "instance\0class\0": the class names the program (as app ids do).
        val wmClass = prop(window, Xcb.XCB_ATOM_WM_CLASS())?.text()?.split('\u0000')?.filter { it.isNotEmpty() }
        window.wmClass = wmClass?.lastOrNull() ?: ""
        if (window.wmClass.isNotEmpty()) window.describeClass(window.wmClass.lowercase())
    }

    // WM_TRANSIENT_FOR: the window a dialog belongs to (none once removed; never itself through others).
    private fun readParent(window: XWindow) {
        var parent: Window? = prop(window, Xcb.XCB_ATOM_WM_TRANSIENT_FOR())?.ints()?.firstOrNull()?.let { windows[it] }
        var up = parent
        while (up != null) {
            if (up === window) {
                log.trace { "$window: transient for itself through ${window.parent}, ignored" }
                parent = null
                break
            }
            up = up.parent
        }
        window.describeParent(parent)
    }

    // WM_NORMAL_HINTS: flags, (x, y, w, h,) min w/h at 5/6, max at 7/8, ..., base at 15/16 (pixels).
    private fun readSizeHints(window: XWindow) {
        val hints = prop(window, Xcb.XCB_ATOM_WM_NORMAL_HINTS())?.ints()
        var minW = 0
        var minH = 0
        var maxW = 0
        var maxH = 0
        if (hints != null && hints.size >= 9) {
            val flags = hints[0]
            if (flags and P_MIN_SIZE != 0) { minW = toUnits(hints[5]); minH = toUnits(hints[6]) }
            // Without a minimum the base size is one (ICCCM).
            else if (flags and P_BASE_SIZE != 0 && hints.size >= 17) { minW = toUnits(hints[15]); minH = toUnits(hints[16]) }
            if (flags and P_MAX_SIZE != 0) { maxW = toUnits(hints[7]); maxH = toUnits(hints[8]) }
        }
        window.describeSize(minW, minH, maxW, maxH)
    }

    // WM_HINTS: flags, input, ...: "input = false" programs take the keyboard themselves (WM_TAKE_FOCUS).
    private fun readHints(window: XWindow) {
        val hints = prop(window, Xcb.XCB_ATOM_WM_HINTS())?.ints()
        window.acceptsFocus = !(hints != null && hints.size >= 2 && hints[0] and INPUT_HINT != 0 && hints[1] == 0)
    }

    private fun readProtocols(window: XWindow) {
        window.protocols = prop(window, atom("WM_PROTOCOLS"))?.ints()?.toSet() ?: emptySet()
    }

    private fun readTypes(window: XWindow) {
        window.windowTypes = prop(window, atom("_NET_WM_WINDOW_TYPE"))?.ints()?.toList() ?: emptyList()
    }

    // _MOTIF_WM_HINTS: flags, functions, decorations, ...: decorations say what the window manager draws.
    private fun readMotif(window: XWindow) {
        val motif = prop(window, atom("_MOTIF_WM_HINTS"))?.ints()
        window.wantsDecorations = if (motif != null && motif.size >= 3 && motif[0] and MOTIF_DECORATIONS != 0) {
            val decorations = motif[2]
            // With "all" set the others are the ones left out; without, the ones wanted.
            if (decorations and DECOR_ALL != 0) decorations and DECOR_TITLE == 0 else decorations and DECOR_TITLE != 0
        } else {
            true
        }
    }

    // _NET_WM_STATE before the window is shown: programs that start fullscreen or maximized.
    private fun startingState(window: XWindow) {
        val states = prop(window, atom("_NET_WM_STATE"))?.ints()?.toSet() ?: return
        if (atom("_NET_WM_STATE_FULLSCREEN") in states) window.fullscreen = true
        if (atom("_NET_WM_STATE_MAXIMIZED_VERT") in states && atom("_NET_WM_STATE_MAXIMIZED_HORZ") in states) window.maximized = true
    }

    // Xwayland tells which surface shows a window: the serial it also gave the surface
    // (xwayland-shell), sent as a message (low half, high half). Either may come first.
    private fun serialReceived(window: XWindow, low: Int, high: Int) {
        window.serial = (low.toLong() and 0xffffffffL) or (high.toLong() shl 32)
        log.trace { "$window has serial ${window.serial}" }
        surfaceFor(window.serial)?.let { associate(window, it) }
    }

    // --- requests of the programs ----------------------------------------------------------

    private fun configureRequested(event: MemorySegment) {
        val id = xcb_configure_request_event_t.window(event)
        val window = windows[id]
        val mask = xcb_configure_request_event_t.value_mask(event).toInt() and 0xffff
        val rx = xcb_configure_request_event_t.x(event).toInt()
        val ry = xcb_configure_request_event_t.y(event).toInt()
        val rw = xcb_configure_request_event_t.width(event).toInt() and 0xffff
        val rh = xcb_configure_request_event_t.height(event).toInt() and 0xffff
        val geometry = Xcb.XCB_CONFIG_WINDOW_X() or Xcb.XCB_CONFIG_WINDOW_Y() or Xcb.XCB_CONFIG_WINDOW_WIDTH() or Xcb.XCB_CONFIG_WINDOW_HEIGHT()
        if (mask and geometry == 0) return // only stacking: the window manager stacks
        if (window == null || !window.inDesktop || window.unmanaged || (!window.tiled && !window.maximized && !window.fullscreen)) {
            // Not ours to decide (yet), placing itself, or floating: as the program asks.
            val values = ArrayList<Int>()
            var applied = 0
            fun take(bit: Int, value: Int) {
                if (mask and bit != 0) {
                    applied = applied or bit
                    values += value
                }
            }
            take(Xcb.XCB_CONFIG_WINDOW_X(), rx)
            take(Xcb.XCB_CONFIG_WINDOW_Y(), ry)
            take(Xcb.XCB_CONFIG_WINDOW_WIDTH(), rw)
            take(Xcb.XCB_CONFIG_WINDOW_HEIGHT(), rh)
            x.configure(id, applied, values.toIntArray())
            if (window != null && window.inDesktop && !window.unmanaged) {
                // The desktop keeps the new place and size (or moving it would undo them).
                if (mask and (Xcb.XCB_CONFIG_WINDOW_WIDTH() or Xcb.XCB_CONFIG_WINDOW_HEIGHT()) != 0) {
                    window.resizedItself(
                        toUnits(if (mask and Xcb.XCB_CONFIG_WINDOW_WIDTH() != 0) rw else window.xGeometry.width),
                        toUnits(if (mask and Xcb.XCB_CONFIG_WINDOW_HEIGHT() != 0) rh else window.xGeometry.height),
                    )
                }
                if (mask and (Xcb.XCB_CONFIG_WINDOW_X() or Xcb.XCB_CONFIG_WINDOW_Y()) != 0) {
                    window.x = toUnits(if (mask and Xcb.XCB_CONFIG_WINDOW_X() != 0) rx else window.xGeometry.x)
                    window.y = toUnits(if (mask and Xcb.XCB_CONFIG_WINDOW_Y() != 0) ry else window.xGeometry.y)
                }
            }
            return
        }
        // Tiled, maximized or fullscreen: it stays as the window manager has it (and is told so).
        place(window)
    }

    private fun clientMessage(event: MemorySegment) {
        val window = windows[xcb_client_message_event_t.window(event)]
        val type = xcb_client_message_event_t.type(event)
        val format = event.get(JAVA_BYTE, 1).toInt() and 0xff
        val data = IntArray(5) { event.get(JAVA_INT, 12L + it * 4) }
        when (type) {
            atom("WL_SURFACE_SERIAL") -> window?.let { serialReceived(it, data[0], data[1]) }
            atom("WL_SURFACE_ID") -> if (!warnedOld) {
                warnedOld = true
                log.warn("This Xwayland is too old (before 23.1): its windows cannot be shown; please update it")
            }
            atom("_NET_WM_STATE") -> if (format == 32) window?.let { stateRequest(it, data) }
            atom("_NET_ACTIVE_WINDOW") -> window?.takeIf { it.inDesktop && !it.unmanaged && !desktop.locked }?.let(desktop::focus)
            atom("_NET_CLOSE_WINDOW") -> window?.let(::close)
            atom("WM_CHANGE_STATE") -> window?.takeIf { it.inDesktop && !it.unmanaged }?.let { w ->
                when (data[0]) {
                    ICONIC_STATE -> desktop.minimizeRequested(w)
                    NORMAL_STATE -> if (desktop.isMinimized(w)) desktop.focus(w)
                }
            }
            atom("_NET_WM_MOVERESIZE") -> window?.takeIf { it.inDesktop && !it.unmanaged }?.let { w ->
                when (val direction = data[2]) {
                    MOVERESIZE_MOVE -> desktop.beginMove(w)
                    in 0..7 -> desktop.beginResize(w, RESIZE_EDGES[direction])
                    else -> log.trace { "Move/resize $direction of $w ignored" }
                }
            }
            else -> log.trace { "Client message ${x.atomName(type)} for $window" }
        }
    }

    // _NET_WM_STATE: action (0 remove, 1 add, 2 toggle), two properties.
    private fun stateRequest(window: XWindow, data: IntArray) {
        val action = data[0]
        val properties = setOf(data[1], data[2])
        fun wanted(now: Boolean) = when (action) {
            0 -> false
            1 -> true
            else -> !now
        }
        if (atom("_NET_WM_STATE_FULLSCREEN") in properties) {
            if (window.inDesktop) desktop.fullscreenRequested(window, wanted(window.fullscreen)) else window.fullscreen = wanted(window.fullscreen)
        }
        if (atom("_NET_WM_STATE_MAXIMIZED_VERT") in properties || atom("_NET_WM_STATE_MAXIMIZED_HORZ") in properties) {
            if (window.inDesktop) desktop.maximizeRequested(window, wanted(window.maximized)) else window.maximized = wanted(window.maximized)
        }
        if (atom("_NET_WM_STATE_HIDDEN") in properties && window.inDesktop) {
            val minimized = desktop.isMinimized(window)
            if (wanted(minimized) && !minimized) desktop.minimizeRequested(window) else if (!wanted(minimized) && minimized) desktop.focus(window)
        }
    }

    // X's keyboard focus follows the desktop's, and only matters while an X window has the
    // keyboard: a program moving it while a Wayland window is focused changes nothing, so there
    // is nothing to fight over. Within one program it may move freely (to its dialog); another
    // program may not take it. Self-placing windows (launchers, games, menus that grab the
    // keyboard) get the keyboard when they take it.
    private fun focusIn(event: MemorySegment) {
        val detail = event.get(JAVA_BYTE, 1).toInt()
        val sequence = event.get(JAVA_SHORT, 2).toInt() and 0xffff
        val window = windows[event.get(JAVA_INT, 4)]
        val mode = event.get(JAVA_BYTE, 8).toInt()
        if (detail == Xcb.XCB_NOTIFY_DETAIL_POINTER() || mode == Xcb.XCB_NOTIFY_MODE_UNGRAB()) return
        if (window != null && window.unmanaged) {
            if (window.inDesktop) window.xSurface?.let(desktop::focusUnmanaged)
            return
        }
        if (mode == Xcb.XCB_NOTIFY_MODE_GRAB()) return // a grab of the window with the keyboard
        // Older than our last focus change: X tells of what was, not what is.
        if ((sequence - lastFocusRequest) and 0xffff >= 0x8000) return
        val current = focused ?: return // a Wayland window has the keyboard
        if (window == null || window === current) return
        if (sameProgram(window, current)) {
            focused = window
            if (window.inDesktop) desktop.focus(window)
            return
        }
        if (insists(window)) return
        log.trace { "$window took the keyboard from $current: given back" }
        giveFocus(current)
    }


    private fun insists(window: XWindow): Boolean {
        val now = System.nanoTime()
        val record = refusals.getOrPut(window.id) { longArrayOf(0, now, 0) }
        if (now < record[2]) return true
        if (now - record[1] > 1_000_000_000) {
            record[0] = 0
            record[1] = now
        }
        record[0]++
        if (record[0] <= MAX_REFUSALS) return false
        record[2] = now + 5_000_000_000
        log.warn("$window keeps taking the keyboard from other programs; left alone for 5 seconds")
        return true
    }

    // Windows of one X connection share the high bits of their ids.
    private fun sameProgram(a: XWindow, b: XWindow) = (a.id xor b.id) and x.resourceIdMask.inv() == 0

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

    private fun giveFocus(window: XWindow?) {
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
            readSizeHints(window)
        }
        updateCursor()
        tellResources()
    }

    override fun close() {
        focusWatch?.close() // the desktop no longer tells a window manager that is gone
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

    private companion object {
        const val NORMAL_STATE = 1
        const val ICONIC_STATE = 3
        const val WITHDRAWN_STATE = 0
        const val P_MIN_SIZE = 16
        const val P_MAX_SIZE = 32
        const val P_BASE_SIZE = 256
        const val INPUT_HINT = 1
        const val MOTIF_DECORATIONS = 2
        const val DECOR_ALL = 1
        const val DECOR_TITLE = 8
        const val MOVERESIZE_MOVE = 8
        const val MAX_REFUSALS = 3

        // _NET_WM_MOVERESIZE directions 0..7 (top-left, top, ... left) as xdg resize edges.
        val RESIZE_EDGES = intArrayOf(5, 1, 9, 8, 10, 2, 6, 4)

        val POPUP_TYPES = listOf("MENU", "DROPDOWN_MENU", "POPUP_MENU", "TOOLTIP", "NOTIFICATION", "COMBO", "DND")
        val FLOATING_TYPES = listOf("DIALOG", "UTILITY", "SPLASH", "TOOLBAR")

        val SUPPORTED = listOf(
            "_NET_SUPPORTED", "_NET_SUPPORTING_WM_CHECK", "_NET_WM_NAME", "_NET_ACTIVE_WINDOW", "_NET_CLIENT_LIST",
            "_NET_CLIENT_LIST_STACKING", "_NET_WM_STATE", "_NET_WM_STATE_FULLSCREEN", "_NET_WM_STATE_MAXIMIZED_VERT",
            "_NET_WM_STATE_MAXIMIZED_HORZ", "_NET_WM_STATE_HIDDEN", "_NET_WM_STATE_FOCUSED", "_NET_WM_MOVERESIZE",
            "_NET_CLOSE_WINDOW", "_NET_WM_WINDOW_TYPE",
        )

        val ATOMS = SUPPORTED + listOf(
            "WM_S0", "_NET_WM_CM_S0", "UTF8_STRING", "WM_PROTOCOLS", "WM_DELETE_WINDOW", "WM_TAKE_FOCUS", "WM_STATE", "WM_CHANGE_STATE",
            "WL_SURFACE_SERIAL", "WL_SURFACE_ID", "_MOTIF_WM_HINTS", "_NET_WM_PID", "CLIPBOARD", "TARGETS", "TEXT", "INCR",
        ) + (POPUP_TYPES + FLOATING_TYPES + "NORMAL").distinct().map { "_NET_WM_WINDOW_TYPE_$it" }
    }
}
