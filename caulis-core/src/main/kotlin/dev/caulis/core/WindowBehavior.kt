package dev.caulis.core

/** What a program may ask of its window (see [WindowBehavior.ignored]). */
enum class WindowRequest {
    /** To fill its monitor (a video's fullscreen button). */
    FULLSCREEN,

    /** To be maximized (or not any more). */
    MAXIMIZE,

    /** To be minimized. */
    MINIMIZE,

    /** To be brought forward with the keyboard (xdg-activation): it is marked urgent instead. */
    ACTIVATE,
}

/**
 * How a window behaves beyond its place and looks — asked of [Desktop.behaviorFor] whenever
 * it matters, so it may change while the window is open (caulis-wm's `rule { }` sets it).
 *
 * ```
 * desktop.behaviorFor = { window ->
 *     if (window.appId == "mpv") WindowBehavior(keepsAwake = true, animated = false) else WindowBehavior.USUAL
 * }
 * ```
 */
data class WindowBehavior @JvmOverloads constructor(
    /** Smallest and largest size while floating, in desktop units (0: no limit of ours). */
    val minWidth: Int = 0,
    val minHeight: Int = 0,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    /** False: it opens, closes and moves without animations. */
    val animated: Boolean = true,
    /** True: while it is on screen, nothing counts as idle (the screen stays on). */
    val keepsAwake: Boolean = false,
    /** True: screenshots and screen sharing show a black box where it is (passwords, chats). */
    val hiddenFromCapture: Boolean = false,
    /** False: it never gets the keyboard (a picture-in-picture video, an on-screen keyboard). */
    val takesFocus: Boolean = true,
    /** What its program asks that is not done (the program is answered with its state as it is). */
    val ignored: Set<WindowRequest> = emptySet(),
) {
    init {
        require(minWidth >= 0 && minHeight >= 0 && maxWidth >= 0 && maxHeight >= 0) { "size limits are 0 (none) or more" }
    }

    /** [width] x [height] within the limits. */
    fun limit(width: Int, height: Int): Pair<Int, Int> {
        var w = width
        var h = height
        if (maxWidth > 0) w = w.coerceAtMost(maxWidth)
        if (maxHeight > 0) h = h.coerceAtMost(maxHeight)
        if (minWidth > 0) w = w.coerceAtLeast(minWidth)
        if (minHeight > 0) h = h.coerceAtLeast(minHeight)
        return w to h
    }

    companion object {
        @JvmField val USUAL = WindowBehavior()
    }
}

/**
 * How a panel (a layer surface: a bar, a launcher, notifications) behaves beyond its looks —
 * asked of [Desktop.layerBehaviorFor] whenever it is drawn (caulis-wm's `panelRule { }` sets it).
 */
data class LayerBehavior @JvmOverloads constructor(
    /** Everything behind it on its monitor this much darker while it is shown (0 to 1: a launcher in focus). */
    val dimAround: Float = 0f,
    /** True: screenshots and screen sharing show a black box where it is (notifications with messages). */
    val hiddenFromCapture: Boolean = false,
) {
    init {
        require(dimAround in 0f..1f) { "dimAround is 0 to 1, not $dimAround" }
    }

    companion object {
        @JvmField val USUAL = LayerBehavior()
    }
}
