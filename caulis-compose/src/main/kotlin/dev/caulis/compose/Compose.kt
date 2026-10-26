package dev.caulis.compose

import androidx.compose.runtime.Composable
import dev.caulis.core.DrawingLevel
import dev.caulis.core.Monitor
import dev.caulis.core.Reserve
import dev.caulis.geometry.Rect
import dev.caulis.wm.Caulis

/**
 * A part of this compositor's own interface in Compose ([ComposeWidget]): where it is on each
 * monitor ([place], desktop units; null: not there), room it keeps at an edge ([reserve]),
 * whether a click gives it the keyboard ([takesKeyboard]), and its [content]. Close the result
 * to take it away; a reload of the configuration does too.
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
    sceneSize: ((Monitor) -> Pair<Int, Int>?)? = null,
    appearance: (Monitor) -> Appearance = { Appearance.SHOWN },
    moving: () -> Boolean = { false },
    content: @Composable (monitor: Monitor) -> Unit,
): AutoCloseable {
    val caulis = this
    var handle: AutoCloseable? = null
    // Made when the compositor runs (it needs the desktop's renderer).
    val start = after(0) {
        handle = addWidget(ComposeWidget(desktop, post = { task -> caulis.post { task.run() } }, place, level, reserve, takesKeyboard, sceneSize, appearance, moving, content))
    }
    return AutoCloseable {
        start.close()
        handle?.close()
    }
}
