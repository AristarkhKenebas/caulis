package dev.caulis.wm

import dev.caulis.core.Monitor
import dev.caulis.core.OverlayCanvas
import dev.caulis.core.Widget
import dev.caulis.core.WidgetKey
import dev.caulis.geometry.Rect
import dev.caulis.render.Color

/**
 * The compositor's own plain lock screen, for when the screen locker did not show
 * ([LockFailure.OWN_LOCK]): every monitor in [color], nothing of the desktop, no window gets
 * a key. It checks no password: Enter (or [retry] otherwise) starts the locker again, and when
 * that locker (a program the compositor started for [Caulis.lock]) locks and then unlocks,
 * this one ends with it. Any other program's lock and unlock leave it up: they asked for no
 * password the compositor knows of.
 */
internal class FallbackLock(private val color: () -> Color, private val retry: () -> Unit) : Widget {

    override fun area(monitor: Monitor): Rect = monitor.area

    override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) {
        val c = color()
        canvas.fill(area, c.red, c.green, c.blue)
    }

    override val takesKeyboard: Boolean get() = true

    override fun key(key: WidgetKey) {
        if (key.pressed && (key.evdevCode == KEY_ENTER || key.evdevCode == KEY_KP_ENTER)) retry()
    }

    override fun toString() = "the compositor's own lock screen (the locker did not show)"

    private companion object {
        const val KEY_ENTER = 28
        const val KEY_KP_ENTER = 96
    }
}

/** What happens when a lock that was asked for (a key, idle, logind, before sleep) does not show. */
enum class LockFailure {
    /** A notice says the screen is not locked; before sleep, the computer sleeps unlocked. */
    NOTICE,

    /**
     * The notice, and the compositor locks with its own plain lock screen (see
     * [Caulis.lockFailure]): the screen stays locked until the locker the compositor starts
     * (Enter on it) unlocks it.
     */
    OWN_LOCK,
}
