package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.tiling.Layout
import kotlin.math.roundToInt

/**
 * How special workspaces look and behave ([Desktop.specialWorkspaces]) — Hyprland's
 * `special` workspaces: named workspaces that appear over the current one on the focused
 * monitor, everything behind them dimmed (and blurred), with tiling inside.
 *
 * ```
 * desktop.toggleSpecialWorkspace()                         // "special", Hyprland's togglespecialworkspace
 * desktop.toggleSpecialWorkspace("music")
 * desktop.moveToSpecialWorkspace(window, "music", silent = true)
 * desktop.moveOutOfSpecialWorkspace(window)                // back to the workspace on screen
 * desktop.specialWorkspaces = SpecialWorkspaces(dim = 0.5f, blur = Blur(6, 3), gap = 60)
 * ```
 *
 * Special workspaces and scratchpads ([Desktop.addScratchpad]) share their machinery: a
 * scratchpad is made for one app summoned by a key (its rule says which windows are its own,
 * its key starts the program); a special workspace is a whole workspace layer — any window
 * can be moved in and out, it tiles them with the compositor's layout, it is made the first
 * time it is named. Only one special workspace is shown on a monitor at a time (showing
 * another one hides it), as in Hyprland.
 */
data class SpecialWorkspaces @JvmOverloads constructor(
    /** How much darker everything behind it is (0: not at all, 1: black). */
    val dim: Float = 0.4f,
    /** What is behind it blurred, or null: only dimmed. */
    val blur: Blur? = null,
    /** Space between it and the monitor's edges (desktop units), on top of the layout's own gaps. */
    val gap: Int = 40,
    /** How much of the monitor (inside [gap]) it takes, 0.2 to 1, in the middle. */
    val size: Double = 1.0,
    /**
     * The animation (by name, see [AnimationTree.CAULIS]) its windows come and go with: off
     * unless animations are on ([Animations]); "scratchpads" by default.
     */
    val animation: String = "scratchpads",
    /** True: it hides when its last window closes or leaves. */
    val hideWhenEmpty: Boolean = true,
    /** True: windows opening while it is shown on the focused monitor go into it (as in Hyprland). */
    val newWindowsJoin: Boolean = true,
    /** True: shown ones hide when the screen locks (they are behind the lock either way). */
    val hideOnLock: Boolean = true,
    /** True: a click on the dimmed area around it hides it. */
    val clickOutsideHides: Boolean = true,
    /** How its windows are tiled, or null: as on the normal workspaces ([Desktop.layout]). */
    val layout: Layout? = null,
    /** How it looks coming and going (with [animation] on; the dim behind it always fades along). */
    val look: SpecialLook = SpecialLook(),
) {
    init {
        require(dim in 0f..1f) { "dim is 0 to 1, not $dim" }
        require(gap in 0..1000) { "gap is 0 to 1000, not $gap" }
        require(size in 0.2..1.0) { "size is 0.2 to 1, not $size" }
        require(animation in AnimationTree.CAULIS) { AnimationTree.CAULIS.unknown(animation) }
    }

    // For Java, where named arguments do not exist.
    fun withDim(dim: Float) = copy(dim = dim)
    fun withBlur(blur: Blur?) = copy(blur = blur)
    fun withGap(gap: Int) = copy(gap = gap)
    fun withSize(size: Double) = copy(size = size)
    fun withAnimation(name: String) = copy(animation = name)
    fun withLayout(layout: Layout?) = copy(layout = layout)
    fun withHideWhenEmpty(hide: Boolean) = copy(hideWhenEmpty = hide)
    fun withNewWindowsJoin(join: Boolean) = copy(newWindowsJoin = join)
    fun withHideOnLock(hide: Boolean) = copy(hideOnLock = hide)
    fun withClickOutsideHides(hides: Boolean) = copy(clickOutsideHides = hides)
    fun withLook(look: SpecialLook) = copy(look = look)

    companion object {
        /** The name of the special workspace that has none: "special" (Hyprland's). */
        const val DEFAULT_NAME = "special"

        /** The usual settings. */
        @JvmField val USUAL = SpecialWorkspaces()
    }
}

/**
 * How a special workspace's windows come and go: [fade]d, from [scale] of their size (around
 * their middle), [rise] units below their place (negative: above).
 */
data class SpecialLook @JvmOverloads constructor(val fade: Boolean = false, val scale: Double = 0.2, val rise: Int = 0) {
    init {
        require(scale in 0.01..1.0) { "scale is 0.01 to 1, not $scale" }
    }

    internal fun hidden(shown: Rect): Rect {
        val w = (shown.width * scale).roundToInt()
        val h = (shown.height * scale).roundToInt()
        return Rect(shown.x + (shown.width - w) / 2, shown.y + (shown.height - h) / 2 + rise, w, h)
    }
}
