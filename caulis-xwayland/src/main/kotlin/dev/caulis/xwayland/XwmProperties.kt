package dev.caulis.xwayland

import dev.caulis.core.Window
import dev.caulis.ffi.c.xcb.Xcb

/**
 * What X windows say about themselves: reading their properties (title, class, hints, types).
 */
internal class XwmProperties(private val xwm: Xwm) {

    // Properties fetched together for readProperties (one round trip), else read one by one.
    private var fetched: Map<Int, XConnection.Property?>? = null

    private fun prop(window: XWindow, atom: Int): XConnection.Property? =
        fetched?.let { if (atom in it) return it[atom] }.let { xwm.x.property(window.id, atom) }

    private val windowProperties: List<Int> by lazy {
        listOf(
            xwm.atom("_NET_WM_NAME"), Xcb.XCB_ATOM_WM_NAME(), Xcb.XCB_ATOM_WM_CLASS(), Xcb.XCB_ATOM_WM_TRANSIENT_FOR(),
            Xcb.XCB_ATOM_WM_NORMAL_HINTS(), Xcb.XCB_ATOM_WM_HINTS(), xwm.atom("WM_PROTOCOLS"), xwm.atom("_NET_WM_WINDOW_TYPE"),
            xwm.atom("_MOTIF_WM_HINTS"), xwm.atom("_NET_WM_PID"), xwm.atom("_NET_WM_STATE"),
        )
    }

    // Fetches all the window's properties together, then reads them ([read]: everything).
    internal fun readProperties(window: XWindow, read: (XWindow) -> Unit = ::readEverything) {
        fetched = xwm.x.properties(window.id, windowProperties)
        try {
            read(window)
        } finally {
            fetched = null
        }
    }

    internal fun readEverything(window: XWindow) {
        readTitle(window)
        readClass(window)
        readParent(window)
        readSizeHints(window)
        readHints(window)
        readProtocols(window)
        readTypes(window)
        readMotif(window)
        window.netWmPid = prop(window, xwm.atom("_NET_WM_PID"))?.ints()?.firstOrNull() ?: 0
    }

    // Only what changed is read again (programs change _NET_WM_USER_TIME with every key).
    internal fun propertyChanged(window: XWindow, atom: Int) {
        when (atom) {
            xwm.atom("_NET_WM_NAME"), Xcb.XCB_ATOM_WM_NAME() -> readTitle(window).also { infoChanged(window) }
            Xcb.XCB_ATOM_WM_CLASS() -> readClass(window).also { infoChanged(window) }
            Xcb.XCB_ATOM_WM_TRANSIENT_FOR() -> readParent(window)
            Xcb.XCB_ATOM_WM_NORMAL_HINTS() -> readSizeHints(window)
            Xcb.XCB_ATOM_WM_HINTS() -> readHints(window)
            xwm.atom("WM_PROTOCOLS") -> readProtocols(window)
            xwm.atom("_NET_WM_WINDOW_TYPE") -> readTypes(window)
            xwm.atom("_MOTIF_WM_HINTS") -> readMotif(window).also { infoChanged(window) }
        }
    }

    private fun infoChanged(window: XWindow) {
        if (window.inDesktop && !window.unmanaged) xwm.desktop.windowInfoChanged(window)
    }

    private fun readTitle(window: XWindow) {
        val value = prop(window, xwm.atom("_NET_WM_NAME")) ?: prop(window, Xcb.XCB_ATOM_WM_NAME()) ?: return
        // Old programs give WM_NAME as STRING: Latin-1, not UTF-8.
        val title = if (value.type == Xcb.XCB_ATOM_STRING()) String(value.bytes, Charsets.ISO_8859_1).trimEnd('\u0000') else value.text()
        window.describe(xwm.capText(title))
    }

    private fun readClass(window: XWindow) {
        // WM_CLASS is "instance\0class\0": the class names the program (as app ids do).
        val wmClass = prop(window, Xcb.XCB_ATOM_WM_CLASS())?.text()?.split('\u0000')?.filter { it.isNotEmpty() }
        window.wmClass = wmClass?.lastOrNull() ?: ""
        if (window.wmClass.isNotEmpty()) window.describeClass(xwm.capText(window.wmClass.lowercase()))
    }

    // WM_TRANSIENT_FOR: the window a dialog belongs to (none once removed; never itself through others).
    private fun readParent(window: XWindow) {
        var parent: Window? = prop(window, Xcb.XCB_ATOM_WM_TRANSIENT_FOR())?.ints()?.firstOrNull()?.let { xwm.windows[it] }
        var up = parent
        while (up != null) {
            if (up === window) {
                xwm.log.trace { "$window: transient for itself through ${window.parent}, ignored" }
                parent = null
                break
            }
            up = up.parent
        }
        window.describeParent(parent)
    }

    // WM_NORMAL_HINTS: flags, (x, y, w, h,) min w/h at 5/6, max at 7/8, ..., base at 15/16 (pixels).
    internal fun readSizeHints(window: XWindow) {
        val hints = prop(window, Xcb.XCB_ATOM_WM_NORMAL_HINTS())?.ints()
        var minW = 0
        var minH = 0
        var maxW = 0
        var maxH = 0
        if (hints != null && hints.size >= 9) {
            val flags = hints[0]
            if (flags and P_MIN_SIZE != 0) { minW = xwm.toUnits(hints[5]); minH = xwm.toUnits(hints[6]) }
            // Without a minimum the base size is one (ICCCM).
            else if (flags and P_BASE_SIZE != 0 && hints.size >= 17) { minW = xwm.toUnits(hints[15]); minH = xwm.toUnits(hints[16]) }
            if (flags and P_MAX_SIZE != 0) { maxW = xwm.toUnits(hints[7]); maxH = xwm.toUnits(hints[8]) }
        }
        window.describeSize(minW, minH, maxW, maxH)
    }

    // WM_HINTS: flags, input, ...: "input = false" programs take the keyboard themselves (WM_TAKE_FOCUS).
    private fun readHints(window: XWindow) {
        val hints = prop(window, Xcb.XCB_ATOM_WM_HINTS())?.ints()
        window.acceptsFocus = !(hints != null && hints.size >= 2 && hints[0] and INPUT_HINT != 0 && hints[1] == 0)
    }

    private fun readProtocols(window: XWindow) {
        window.protocols = prop(window, xwm.atom("WM_PROTOCOLS"))?.ints()?.toSet() ?: emptySet()
    }

    private fun readTypes(window: XWindow) {
        window.windowTypes = prop(window, xwm.atom("_NET_WM_WINDOW_TYPE"))?.ints()?.toList() ?: emptyList()
    }

    // _MOTIF_WM_HINTS: flags, functions, decorations, ...: decorations say what the window manager draws.
    private fun readMotif(window: XWindow) {
        val motif = prop(window, xwm.atom("_MOTIF_WM_HINTS"))?.ints()
        window.wantsDecorations = if (motif != null && motif.size >= 3 && motif[0] and MOTIF_DECORATIONS != 0) {
            val decorations = motif[2]
            // With "all" set the others are the ones left out; without, the ones wanted.
            if (decorations and DECOR_ALL != 0) decorations and DECOR_TITLE == 0 else decorations and DECOR_TITLE != 0
        } else {
            true
        }
    }

    // _NET_WM_STATE before the window is shown: programs that start fullscreen or maximized.
    internal fun startingState(window: XWindow) {
        val states = prop(window, xwm.atom("_NET_WM_STATE"))?.ints()?.toSet() ?: return
        if (xwm.atom("_NET_WM_STATE_FULLSCREEN") in states) window.fullscreen = true
        if (xwm.atom("_NET_WM_STATE_MAXIMIZED_VERT") in states && xwm.atom("_NET_WM_STATE_MAXIMIZED_HORZ") in states) window.maximized = true
        // A dialog its window waits for (modal).
        if (xwm.atom("_NET_WM_STATE_MODAL") in states) window.describeModal(true)
    }
}
