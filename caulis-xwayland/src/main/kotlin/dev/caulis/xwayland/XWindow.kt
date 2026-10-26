package dev.caulis.xwayland

import dev.caulis.core.Surface
import dev.caulis.core.Window
import dev.caulis.geometry.Rect

/**
 * An X11 program's window, managed like any other window. X works in the screen's pixels;
 * the desktop in its own units: positions and sizes are converted with the scale both ways.
 */
class XWindow internal constructor(private val xwm: Xwm, val id: Int, overrideRedirect: Boolean) : Window() {

    /** A menu, tooltip or drag picture that places itself (not managed, shown where it says). */
    var overrideRedirect = overrideRedirect
        internal set

    /** Where X has the window (pixels): updated from ConfigureNotify. */
    internal var xGeometry = Rect(0, 0, 1, 1)

    /** Its picture, once Xwayland told which surface it is (xwayland-shell). */
    internal var xSurface: Surface? = null

    // X mapped it (MapRequest answered / MapNotify seen) and it is in the desktop.
    internal var xMapped = false
    internal var inDesktop = false

    // Shown as a menu or tooltip (true) or as a window (false), when it was shown.
    internal var shownUnmanaged = false

    // What the program said about itself.
    internal var protocols: Set<Int> = emptySet()
    internal var wantsDecorations = true
    internal var windowTypes: List<Int> = emptyList()
    internal var wmClass = ""
    internal var serial = 0L

    /** _NET_WM_PID: the process, as the program says (most do). */
    internal var netWmPid = 0
    override val pid: Int get() = netWmPid

    /** WM_HINTS: false if the program takes the keyboard itself when asked (WM_TAKE_FOCUS only). */
    internal var acceptsFocus = true

    /**
     * Not a window of the desktop but something that places itself above it: override-redirect
     * windows, and menus, tooltips and notifications that say so by their type.
     */
    internal val unmanaged: Boolean get() = overrideRedirect || windowTypes.any { it in xwm.popupTypes }

    override val prefersFloating: Boolean get() = windowTypes.any { it in xwm.floatingTypes }

    override val surface: Surface get() = xSurface ?: error("$this has no surface yet")

    override val visibleRect: Rect
        get() = xSurface?.let { Rect(0, 0, it.width, it.height) } ?: Rect(0, 0, xwm.toUnits(xGeometry.width), xwm.toUnits(xGeometry.height))

    override val configured: Boolean get() = sizeAsked != null || inDesktop
    override val mapped: Boolean get() = inDesktop

    private var sizeAsked: Pair<Int, Int>? = null
    override val configuredSize: Pair<Int, Int> get() = sizeAsked ?: (visibleRect.width to visibleRect.height)

    override fun configure(width: Int, height: Int) {
        if (width > 0 && height > 0) sizeAsked = width to height
        xwm.place(this)
        xwm.updateState(this)
    }

    override fun moved() {
        xwm.place(this)
    }

    override fun setActivated(active: Boolean) {
        if (activated == active) return
        activated = active
        if (active) xwm.focus(this)
        xwm.updateState(this)
    }

    override fun close() = xwm.close(this)

    override val needsTitleBar: Boolean get() = wantsDecorations && !unmanaged

    override fun setMinimized(minimized: Boolean) = xwm.minimized(this, minimized)

    // Set from the X properties (title, class, size hints, transient-for).
    internal fun describe(title: String) {
        this.title = title
    }

    internal fun describeClass(appId: String) {
        this.appId = appId
    }

    internal fun describeSize(minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int) {
        this.minWidth = minWidth
        this.minHeight = minHeight
        this.maxWidth = maxWidth
        this.maxHeight = maxHeight
    }

    internal fun describeParent(parent: Window?) {
        this.parent = parent
    }

    internal fun describeModal(modal: Boolean) {
        this.modal = modal
    }

    /** The program changed its size on its own (floating windows may): the desktop keeps it. */
    internal fun resizedItself(width: Int, height: Int) {
        sizeAsked = width to height
    }

    override fun toString() = "XWindow(\"$title\", $appId, 0x${Integer.toHexString(id)})"
}
