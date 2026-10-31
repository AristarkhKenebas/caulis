package dev.caulis.xwayland

import dev.caulis.core.WindowRequest
import dev.caulis.ffi.c.xcb.Xcb
import dev.caulis.ffi.c.xcb.xcb_client_message_event_t
import dev.caulis.ffi.c.xcb.xcb_configure_request_event_t
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT

/**
 * What X programs ask for: configuring, messages, states, the focus.
 */
internal class XwmRequests(private val xwm: Xwm) {

    // Xwayland tells which surface shows a window: the serial it also gave the surface
    // (xwayland-shell), sent as a message (low half, high half). Either may come first.
    private fun serialReceived(window: XWindow, low: Int, high: Int) {
        window.serial = (low.toLong() and 0xffffffffL) or (high.toLong() shl 32)
        xwm.log.trace { "$window has serial ${window.serial}" }
        xwm.surfaceFor(window.serial)?.let { xwm.associate(window, it) }
    }

    internal fun configureRequested(event: MemorySegment) {
        val id = xcb_configure_request_event_t.window(event)
        val window = xwm.windows[id]
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
            xwm.x.configure(id, applied, values.toIntArray())
            if (window != null && window.inDesktop && !window.unmanaged) {
                // The desktop keeps the new place and size (or moving it would undo them).
                if (mask and (Xcb.XCB_CONFIG_WINDOW_WIDTH() or Xcb.XCB_CONFIG_WINDOW_HEIGHT()) != 0) {
                    window.resizedItself(
                        xwm.toUnits(if (mask and Xcb.XCB_CONFIG_WINDOW_WIDTH() != 0) rw else window.xGeometry.width),
                        xwm.toUnits(if (mask and Xcb.XCB_CONFIG_WINDOW_HEIGHT() != 0) rh else window.xGeometry.height),
                    )
                }
                if (mask and (Xcb.XCB_CONFIG_WINDOW_X() or Xcb.XCB_CONFIG_WINDOW_Y()) != 0) {
                    window.x = xwm.toUnits(if (mask and Xcb.XCB_CONFIG_WINDOW_X() != 0) rx else window.xGeometry.x)
                    window.y = xwm.toUnits(if (mask and Xcb.XCB_CONFIG_WINDOW_Y() != 0) ry else window.xGeometry.y)
                }
            }
            return
        }
        // Tiled, maximized or fullscreen: it stays as the window manager has it (and is told so).
        xwm.place(window)
    }

    internal fun clientMessage(event: MemorySegment) {
        val window = xwm.windows[xcb_client_message_event_t.window(event)]
        val type = xcb_client_message_event_t.type(event)
        val format = event.get(JAVA_BYTE, 1).toInt() and 0xff
        val data = IntArray(5) { event.get(JAVA_INT, 12L + it * 4) }
        when (type) {
            xwm.atom("WL_SURFACE_SERIAL") -> window?.let { serialReceived(it, data[0], data[1]) }
            xwm.atom("WL_SURFACE_ID") -> if (!xwm.warnedOld) {
                xwm.warnedOld = true
                xwm.log.warn("This Xwayland is too old (before 23.1): its windows cannot be shown; please update it")
            }
            xwm.atom("_NET_WM_STATE") -> if (format == 32) window?.let { stateRequest(it, data) }
            // (Only as the rule against focus stealing says: any X program may send this for any window.)
            xwm.atom("_NET_ACTIVE_WINDOW") -> window?.takeIf { it.inDesktop && !it.unmanaged }?.let { xwm.desktop.focusRequested(it, WindowRequest.ACTIVATE) }
            xwm.atom("_NET_CLOSE_WINDOW") -> window?.let(xwm::close)
            xwm.atom("WM_CHANGE_STATE") -> window?.takeIf { it.inDesktop && !it.unmanaged }?.let { w ->
                when (data[0]) {
                    ICONIC_STATE -> xwm.desktop.minimizeRequested(w)
                    NORMAL_STATE -> if (xwm.desktop.isMinimized(w)) xwm.desktop.focusRequested(w, WindowRequest.RESTORE)
                }
            }
            xwm.atom("_NET_WM_MOVERESIZE") -> window?.takeIf { it.inDesktop && !it.unmanaged }?.let { w ->
                when (val direction = data[2]) {
                    // (As a Wayland program's request: the button held in its window, the director asked.)
                    MOVERESIZE_MOVE -> xwm.desktop.x11DragRequested(w, null)
                    in 0..7 -> xwm.desktop.x11DragRequested(w, RESIZE_EDGES[direction])
                    else -> xwm.log.trace { "Move/resize $direction of $w ignored" }
                }
            }
            else -> xwm.log.trace { "Client message ${xwm.x.atomName(type)} for $window" }
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
        if (xwm.atom("_NET_WM_STATE_FULLSCREEN") in properties) {
            if (window.inDesktop) xwm.desktop.fullscreenRequested(window, wanted(window.fullscreen)) else window.fullscreen = wanted(window.fullscreen)
        }
        if (xwm.atom("_NET_WM_STATE_MAXIMIZED_VERT") in properties || xwm.atom("_NET_WM_STATE_MAXIMIZED_HORZ") in properties) {
            if (window.inDesktop) xwm.desktop.maximizeRequested(window, wanted(window.maximized)) else window.maximized = wanted(window.maximized)
        }
        if (xwm.atom("_NET_WM_STATE_MODAL") in properties) window.describeModal(wanted(window.modal))
        if (xwm.atom("_NET_WM_STATE_HIDDEN") in properties && window.inDesktop) {
            val minimized = xwm.desktop.isMinimized(window)
            if (wanted(minimized) && !minimized) xwm.desktop.minimizeRequested(window) else if (!wanted(minimized) && minimized) xwm.desktop.focusRequested(window, WindowRequest.RESTORE)
        }
    }

    // X's keyboard focus follows the desktop's, and only matters while an X window has the
    // keyboard: a program moving it while a Wayland window is focused changes nothing, so there
    // is nothing to fight over. Within one program it may move freely (to its dialog); another
    // program may not take it. Self-placing windows (launchers, games, menus that grab the
    // keyboard) get the keyboard when they take it.
    internal fun focusIn(event: MemorySegment) {
        val detail = event.get(JAVA_BYTE, 1).toInt()
        val sequence = event.get(JAVA_SHORT, 2).toInt() and 0xffff
        val window = xwm.windows[event.get(JAVA_INT, 4)]
        val mode = event.get(JAVA_BYTE, 8).toInt()
        if (detail == Xcb.XCB_NOTIFY_DETAIL_POINTER() || mode == Xcb.XCB_NOTIFY_MODE_UNGRAB()) return
        if (window != null && window.unmanaged) {
            if (window.inDesktop) window.xSurface?.let(xwm.desktop::focusUnmanaged)
            return
        }
        if (mode == Xcb.XCB_NOTIFY_MODE_GRAB()) return // a grab of the window with the keyboard
        // Older than our last focus change: X tells of what was, not what is.
        if ((sequence - xwm.lastFocusRequest) and 0xffff >= 0x8000) return
        val current = xwm.focused ?: return // a Wayland window has the keyboard
        if (window == null || window === current) return
        if (xwm.sameProgram(window, current)) {
            xwm.focused = window
            if (window.inDesktop) xwm.desktop.focus(window)
            return
        }
        if (insists(window)) return
        xwm.log.trace { "$window took the keyboard from $current: given back" }
        xwm.giveFocus(current)
    }

    private fun insists(window: XWindow): Boolean {
        val now = System.nanoTime()
        val record = xwm.refusals.getOrPut(window.id) { longArrayOf(0, now, 0) }
        if (now < record[2]) return true
        if (now - record[1] > 1_000_000_000) {
            record[0] = 0
            record[1] = now
        }
        record[0]++
        if (record[0] <= MAX_REFUSALS) return false
        record[2] = now + 5_000_000_000
        xwm.log.warn("$window keeps taking the keyboard from other programs; left alone for 5 seconds")
        return true
    }
}
