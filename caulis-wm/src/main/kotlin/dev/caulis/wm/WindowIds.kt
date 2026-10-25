package dev.caulis.wm

import dev.caulis.core.Desktop
import dev.caulis.core.Window
import java.util.IdentityHashMap

/**
 * Numbers for windows, for programs (the IPC socket) and scripts (Lua): the same while a
 * window is open, never used again for another one.
 */
internal class WindowIds(desktop: Desktop, private val loop: dev.caulis.event.EventLoop) {

    private val ids = IdentityHashMap<Window, Long>()
    private val byId = HashMap<Long, Window>()
    private var next = 1L

    // Closed windows: their number stays theirs until every listener of the close heard it
    // (one asking for it then gets the number it knew, not a new one).
    private val closing = IdentityHashMap<Window, Long>()

    init {
        desktop.onWindowClosed { window ->
            val id = ids.remove(window) ?: return@onWindowClosed
            byId.remove(id)
            closing[window] = id
            loop.post { closing.remove(window) }
        }
    }

    /** [window]'s number; 0 for a window long gone (it is given none any more). */
    fun of(window: Window): Long = ids[window] ?: closing[window] ?: if (!window.mapped) 0L else (next++).also { id ->
        ids[window] = id
        byId[id] = window
    }

    fun window(id: Long): Window? = byId[id]
}
