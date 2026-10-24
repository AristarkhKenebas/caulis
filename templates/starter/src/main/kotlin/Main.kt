import dev.caulis.core.Effects
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

    // How windows look: round corners, a border around the focused one, soft shadows.
    // Effects.NONE turns it all off; blur and transparency: see the guide.
    effects = Effects.MODERN

    // The cursor: Bibata comes with the project ("Bibata-Modern-Ice" is the white one,
    // "Bibata-Modern-Amber" the amber one); Caulis's own sharp ones are "Caulis" and
    // "Caulis-Black"; any installed theme works too, e.g. "Adwaita".
    cursorTheme = "Bibata-Modern-Classic"
    cursorSize = 24

    // How much the log says: 0 nearly nothing, 1 problems, 2 what happens, 3 everything.
    // (CAULIS_LOG=3 when starting does the same without changing this file.)
    logLevel = 1

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
