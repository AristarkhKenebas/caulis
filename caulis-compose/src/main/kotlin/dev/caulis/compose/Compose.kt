package dev.caulis.compose

import androidx.compose.runtime.Composable
import dev.caulis.core.DrawingLevel
import dev.caulis.core.Monitor
import dev.caulis.core.Reserve
import dev.caulis.geometry.Rect
import dev.caulis.wm.Caulis

/**
 * A part of this compositor's own interface in Compose ([ComposeWidget]):
 *
 * - [place]: where it is on each monitor (desktop units; null: not there),
 * - [level]: among the windows: on top of everything (the default), above or below them,
 * - [reserve]: room it keeps at an edge (a bar; windows make way),
 * - [takesKeyboard]: a click gives it the keyboard (text fields, a launcher),
 * - [sceneSize]: the scene's size, when it is not its area's (an area that grows and shrinks
 *   without the scene being made anew: see [ComposeWidget]),
 * - [appearance]: how its picture is shown (see-through, bigger, moved, unfolding — see
 *   [WidgetLook]), done by the compositor without drawing it again,
 * - [moving]: true while [appearance] changes by itself (frames keep coming),
 * - [onPressOutside]: a press elsewhere (x, y, on another widget): true takes it (a menu closed),
 * - [recovery]: what happens when its content throws (logged, started over: [ComposeRecovery]),
 * - [showsWhileLocked]: shown and used while the session is locked too (an on-screen keyboard),
 * - [content]: what it shows on a monitor.
 *
 * A lock screen of your own: `desktop.lockInternally(handle.widget!!)` (once the compositor
 * runs; see [dev.caulis.core.Desktop.lockInternally]).
 *
 * Copying and pasting in its text fields use the compositor's clipboard. Close the result to
 * take it away (a reload of the configuration does too); it also has the widget once the
 * compositor runs.
 *
 * ```
 * compose(place = { m -> Rect(m.area.x, m.area.y, m.area.width, 32) }, reserve = { Reserve(Edge.TOP, 32) }) { monitor ->
 *     Row(Modifier.fillMaxSize().background(Color(0xE01E1E2E)), verticalAlignment = Alignment.CenterVertically) {
 *         Text("  ${monitor.name}", color = Color.White)
 *     }
 * }
 * ```
 */
fun Caulis.compose(
    place: (Monitor) -> Rect?,
    level: DrawingLevel = DrawingLevel.ON_TOP,
    reserve: (Monitor) -> Reserve? = { null },
    takesKeyboard: Boolean = false,
    sceneSize: (Monitor) -> SceneSize? = { null },
    appearance: (Monitor) -> WidgetLook = { WidgetLook.SHOWN },
    moving: () -> Boolean = { false },
    onPressOutside: (x: Double, y: Double, onAnother: Boolean) -> Boolean = { _, _, _ -> false },
    recovery: ComposeRecovery = ComposeRecovery.DEFAULT,
    showsWhileLocked: Boolean = false,
    content: @Composable (monitor: Monitor) -> Unit,
): ComposeHandle {
    val caulis = this
    val handle = ComposeHandle(caulis)
    // Made when the compositor runs (it needs the desktop's renderer).
    handle.start = after(0) {
        val widget = ComposeWidget(desktop, post = { task -> caulis.post { task.run() } }, place, level, reserve, takesKeyboard, sceneSize, appearance, moving, onPressOutside, caulis.clipboard, recovery, showsWhileLocked, content)
        handle.widget = widget
        handle.added = addWidget(widget)
    }
    return handle
}

/** As in Caulis 0.1.1 (kept for programs built against it): see the [compose] with more settings. */
@Deprecated("Use compose(...) returning a ComposeHandle", level = DeprecationLevel.HIDDEN)
fun Caulis.compose(
    place: (Monitor) -> Rect?,
    level: DrawingLevel = DrawingLevel.ON_TOP,
    reserve: (Monitor) -> Reserve? = { null },
    takesKeyboard: Boolean = false,
    content: @Composable (monitor: Monitor) -> Unit,
): AutoCloseable = compose(place, level, reserve, takesKeyboard, content = content)

/**
 * A Compose widget made with [compose]: its [widget] (once the compositor runs), what can be
 * done with it, and [close] to take it away. Call its functions on the compositor's thread.
 */
class ComposeHandle internal constructor(private val caulis: Caulis) : AutoCloseable {

    /** The widget, once the compositor runs (null before, and after [close]). */
    @Volatile
    var widget: ComposeWidget? = null
        internal set

    internal var start: AutoCloseable? = null
    internal var added: AutoCloseable? = null

    /** Draws it once ahead, off screen (see [ComposeWidget.prewarm]). */
    fun prewarm(monitor: Monitor, width: Int, height: Int) {
        widget?.prewarm(monitor, width, height)
    }

    /** Makes its scene for [monitor] on the calling thread, a thread of your own (see [ComposeWidget.prepareAhead]). */
    fun prepareAhead(monitor: Monitor, width: Int, height: Int) {
        widget?.prepareAhead(monitor, width, height)
    }

    /** Starts its scenes over (see [ComposeWidget.restart]). */
    fun restart() {
        widget?.restart()
    }

    /** Gives it the keyboard (true; programs lose it meanwhile) or gives it back to the focused window (false). */
    @JvmOverloads
    fun focus(take: Boolean = true) {
        val own = widget ?: return
        if (take) caulis.desktop.focusWidget(own) else if (own.hasKeyboard) caulis.desktop.focusWidget(null)
    }

    override fun close() {
        start?.close()
        added?.close()
        widget = null
    }
}
