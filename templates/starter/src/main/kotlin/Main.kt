import dev.caulis.core.Effects
import dev.caulis.core.TitleBars
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Gaps
import dev.caulis.wm.caulis

// Your compositor. Start it with ./gradlew run, then change anything and save:
// the change is taken on while it runs (your windows stay).
// The guide: docs/getting-started.md in the Caulis folder.
fun main() = caulis {
    title = "My compositor"

    // Your folder of shaders and pictures: ~/.config/my-compositor (see file("...") in the guide).
    configName = "my-compositor"

    // How windows are placed: Floating, Dwindle(), MasterStack(), BinaryTree(),
    // ScrollingColumns(), Canvas() ... — or your own.
    layout = Dwindle(Gaps(inner = 8, outer = 8))

    // How windows look: round corners, a border around the focused one, soft shadows.
    // Effects.NONE turns it all off; blur and transparency: see the guide.
    effects = Effects.MODERN

    // Title bars drawn by the compositor, the same for all windows (TitleBars.PROGRAM: each
    // program its own; TitleBars.NONE: none). Their look: titleBarStyle, see the guide.
    titleBars = TitleBars.COMPOSITOR

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

    // Rules for some windows: where they open and how (see the guide, "Rules and events").
    rule { appId("pavucontrol"); floating = true; size(700, 500); center() }

    // What to start when the compositor is ready.
    onStart {
        openTerminal()
    }
}
