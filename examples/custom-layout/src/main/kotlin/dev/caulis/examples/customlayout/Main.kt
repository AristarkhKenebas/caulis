package dev.caulis.examples.customlayout

import dev.caulis.tiling.tiling
import dev.caulis.wm.caulis

// Your own way of placing windows, described with building blocks:
// the first window big on the left, the next two stacked in the middle,
// and everything else as a grid on the right.
val ThreeParts = tiling("Three parts") {
    row {
        window(weight = 50)
        column(weight = 25) {
            window()
            window()
        }
        column(weight = 25) { grid() }
    }
}

fun main() = caulis {
    layout = ThreeParts
    onStart {
        repeat(5) { launch("foot") }
    }
}
