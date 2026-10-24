package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.tiling.LayoutWindow

/**
 * A program's window, as the window manager sees it — a Wayland one ([XdgWindow]) or an X11
 * one (from caulis-xwayland). Layouts, focus, title bars, effects, docks and screen sharing
 * treat both the same.
 */
abstract class Window : LayoutWindow {

    /** The surface with the window's picture. */
    abstract val surface: Surface

    override var title = ""
        protected set
    override var appId = ""
        protected set

    /** The window this one belongs to (dialogs), or null. */
    var parent: Window? = null
        protected set

    /** Size limits the program asked for (0 = no limit). */
    override var minWidth = 0
        protected set
    override var minHeight = 0
        protected set
    override var maxWidth = 0
        protected set
    override var maxHeight = 0
        protected set

    /** True if the program cannot be resized (minimum = maximum): such windows float. */
    val fixedSize: Boolean get() = minWidth > 0 && minWidth == maxWidth && minHeight > 0 && minHeight == maxHeight

    /** The process of the program (0 if not known — X11 programs that do not say). */
    open val pid: Int get() = 0

    /** True if the program says it is a dialog, a tool palette or a splash screen (X11 window types): these float. */
    open val prefersFloating: Boolean get() = false

    /** Where the window's visible top left corner is on the screen; set by the window manager. */
    var x = 0
        set(value) {
            if (field == value) return
            field = value
            moved()
        }
    var y = 0
        set(value) {
            if (field == value) return
            field = value
            moved()
        }

    /** Called when [x] or [y] changed (X11 programs must be told where their window is). */
    protected open fun moved() {}

    /**
     * True if the program cannot draw a title bar of its own and expects one from the window
     * manager (X11 programs that do not say otherwise): it gets the compositor's even with
     * [TitleBars.PROGRAM], unless [TitleBars.NONE].
     */
    open val needsTitleBar: Boolean get() = false

    /** True while the window has the keyboard focus (shown as the "active" window). */
    var activated = false
        @JvmName("markActivated") // not to clash with setActivated() below on the JVM
        protected set

    /**
     * States the window manager gives the window; the program learns about them with the
     * next [configure] (and then draws itself accordingly, e.g. without rounded corners).
     */
    var maximized = false
    var fullscreen = false
    var resizing = false

    /** Placed by a tiling layout (programs then draw without shadows and rounded corners). */
    var tiled = false

    /**
     * True if the program agreed not to draw its own title bar (xdg-decoration, the KDE
     * protocol, or an X11 program asking the window manager for one): the compositor draws
     * one, or none.
     */
    var serverDecorated = false

    /** The part of [surface] that is the window (without client-side shadows), in surface coordinates. */
    abstract val visibleRect: Rect

    /** True once the program was told its first size. */
    abstract val configured: Boolean

    /** True while the window shows a picture. */
    abstract val mapped: Boolean

    /** The size last asked for with [configure] (0 = the program's choice). */
    abstract val configuredSize: Pair<Int, Int>

    /** Asks the program to use this size (0 = its own choice) and tells it its states. */
    abstract fun configure(width: Int = configuredSize.first, height: Int = configuredSize.second)

    /** Marks the window as active or not (programs draw active windows a bit differently). */
    open fun setActivated(active: Boolean) {
        if (activated == active) return
        activated = active
        if (mapped) configure()
    }

    /** Tells the program it was minimized or came back (X11 programs pause, players keep playing). */
    open fun setMinimized(minimized: Boolean) {}

    /** Asks the program to close the window (it may show "save changes?" first). */
    abstract fun close()

    override fun toString() = "Window(\"$title\", $appId)"
}
