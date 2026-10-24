package dev.caulis.examples.customlayout

import dev.caulis.core.layout.Layout
import dev.caulis.core.layout.LayoutWindow
import dev.caulis.core.layout.Split
import dev.caulis.render.Rect
import dev.caulis.wm.caulis

// A layout gets the windows and the free area, and returns one rectangle per window.
// This one puts all windows in equal columns side by side, 10 pixels apart.
object Columns : Layout {
    override val name = "Columns"

    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> =
        Split.evenly(area, windows.size, gap = 10, vertical = false)
}

fun main() = caulis {
    layout = Columns
    onStart {
        repeat(3) { launch("foot") }
    }
}
