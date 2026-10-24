import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Gaps
import dev.caulis.wm.caulis

// Your compositor. Change anything, then run it again with ./gradlew run
// The guide: docs/getting-started.md in the Caulis folder.
fun main() = caulis {
    title = "My compositor"

    // How windows are placed: Floating, Dwindle(), MasterStack(), BinaryTree(),
    // ScrollingColumns(), Canvas() ... — or your own.
    layout = Dwindle(Gaps(inner = 8, outer = 8))

    // Keyboard layouts, as for setxkbmap: "us", "us,de", "us,ru" ...
    keyboardLayouts = "us"

    // The terminal Super+Return opens.
    terminal = "foot"

    // Your own keys (the usual ones — Super+Q, Super+1..9, Alt+Tab ... — are already there).
    bind("Super+T") { layout = if (layout === Floating) Dwindle() else Floating }

    // Programs that should float instead of taking a tile.
    floatWhen { window -> window.appId == "pavucontrol" }

    // What to start when the compositor is ready.
    onStart {
        openTerminal()
    }
}
