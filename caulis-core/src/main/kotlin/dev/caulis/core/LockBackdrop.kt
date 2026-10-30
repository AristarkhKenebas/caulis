package dev.caulis.core

/**
 * What shows behind the compositor's own lock screen when nothing of the desktop may
 * ([Desktop.lockBackdrop]): the wallpaper (or, false, only [Desktop.lockColor]), [blur]red and
 * dimmed by [dim] (0: not, 1: black). Windows, panels and programs' pictures never show.
 */
data class LockBackdrop @JvmOverloads constructor(
    val wallpaper: Boolean = true,
    val blur: Blur? = null,
    val dim: Float = 0f,
) {
    init {
        require(dim in 0f..1f) { "dim is 0 to 1, not $dim" }
    }
}
