package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * Where and how a window is shown at one moment, as your compositor decides it (see
 * [WindowDirector]): [x], [y], [width], [height] on the desktop, in units — fractions too.
 * When that is not the window's own size its picture is stretched into it (zoomed out, half
 * way through an animation); the size the program draws at is yours to ask for with
 * [Window.configure].
 */
data class WindowPlace @JvmOverloads constructor(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    /** 0 (invisible) to 1. */
    val alpha: Float = 1f,
    /** Round corners in units, or null: as the window's effects say. */
    val cornerRadius: Float? = null,
    /** Only this part of the desktop shows it (units), or null: all of it. */
    val clip: Rect? = null,
    /** Higher is above; equal ones keep the desktop's order. */
    val z: Double = 0.0,
    /** The pointer, clicks and touches reach it here (false: they go to what is below). */
    val takesInput: Boolean = true,
) {
    init {
        require(width >= 0 && height >= 0) { "a window place cannot have a negative size ($width x $height)" }
    }

    /** The rectangle in whole units (as it is drawn). */
    val rect: Rect
        get() = Rect(Math.round(x).toInt(), Math.round(y).toInt(), Math.round(width).toInt().coerceAtLeast(1), Math.round(height).toInt().coerceAtLeast(1))
}

/**
 * A compositor that places windows itself, every frame (as wlroots compositors do with their
 * own scene): [Desktop.director]. The windows it [manages] are left out of the layouts,
 * get no animations of the desktop's own and are drawn where [place] says — stretched,
 * faded, clipped, in their own order. Everything else stays the desktop's: focus and keys,
 * menus (popups follow their window), panels, the lock screen, X11 programs, effects
 * (borders, shadows, blur) and frame timing.
 *
 * ```
 * desktop.director = object : WindowDirector {
 *     override fun place(window: Window, now: Long): WindowPlace? =
 *         WindowPlace(100.0, 80.0, 640.0, 400.0, cornerRadius = 12f)
 * }
 * ```
 *
 * All of it runs on the compositor's thread, [place] once per frame for every window: keep it
 * quick. If it fails, that is logged and the window stays where it was shown last.
 */
interface WindowDirector {

    /** True if this director places [window] (asked when it opens and each frame); false: the desktop does. */
    fun manages(window: Window): Boolean = true

    /** The size [window] is asked to draw at first, before it shows anything (0, 0: its own choice). */
    fun initialSize(window: Window): Pair<Int, Int> = 0 to 0

    /** Where [window] is shown now ([now]: `System.nanoTime()`), or null: not at all (hidden, and no input). */
    fun place(window: Window, now: Long): WindowPlace?

    /**
     * Pictures of your own with [window] at [place]: below it ([above] false: a blur of what is
     * behind it, a shadow) or above it (a border, a title bar of your own) — between it and
     * the windows above, so they stay in order.
     */
    fun decorate(window: Window, place: WindowPlace, canvas: OverlayCanvas, above: Boolean) {}

    /** True while the places change by themselves (an animation, a gesture's glide): frames keep coming. */
    fun animating(now: Long): Boolean = false

    /**
     * [window] asks to be fullscreen, maximized or minimized ([on]: or not any more). True:
     * handled here (answer it with [Window.configure]); false: the desktop does as usual.
     */
    fun requested(window: Window, request: WindowRequest, on: Boolean): Boolean = false

    /** [window] asks to be moved with the pointer (its title bar dragged). True: handled here. */
    fun moveRequested(window: Window): Boolean = false

    /** [window] asks to be resized with the pointer by [edges] (XdgToplevel.ResizeEdge). True: handled here. */
    fun resizeRequested(window: Window, edges: Int): Boolean = false
}
