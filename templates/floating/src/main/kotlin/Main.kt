import dev.caulis.core.Effects
import dev.caulis.core.TitleBars
import dev.caulis.tiling.Floating
import dev.caulis.wm.caulis

// An Openbox-like compositor: windows stay where you put them, one over another, with title bars.
// Start it with ./gradlew run, then change anything and save: the change is taken on at once.
// The guide: docs/getting-started.md in the Caulis folder.
fun main() = caulis {
    title = "My floating compositor"
    configName = "my-compositor"            // your folder of files: ~/.config/my-compositor

    layout = Floating                       // nothing arranged for you
    titleBars = TitleBars.COMPOSITOR        // the same title bars for all windows; TitleBars.PROGRAM: each its own
    effects = Effects.MODERN                // round corners, a border, soft shadows; Effects.NONE: none

    terminal = "foot"                       // what Super+Return opens
    keyboardLayouts = "us"                  // as for setxkbmap: "us,de", "us,ru" ...

    keys {
        windowDrag = "Alt"                  // Alt + left drag moves a window, Alt + right drag resizes it, as in Openbox
        nextWindow = "Alt+Tab"
        close = "Alt+F4"
        maximize = "Super+Up"
        minimize = "Super+Down"
        // These only mean something when windows are tiled.
        rotate = null
        balance = null
        columnWidth = null
        splitBeside = null
        splitBelow = null
    }

    bind("Super+D") { launch("fuzzel") }    // a launcher, any you have installed

    // A new window of these opens in the middle of the screen, at this size.
    rule { program("pavucontrol"); size(700, 500); center() }

    onStart { openTerminal() }
}
