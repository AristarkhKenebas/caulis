import dev.caulis.core.Effects
import dev.caulis.tiling.Gaps
import dev.caulis.tiling.ScrollingColumns
import dev.caulis.wm.caulis

// A scrolling compositor, as niri: each window opens as a new column on an endless strip, and
// nothing else gets squeezed; the view scrolls to show the focused one.
// Start it with ./gradlew run, then change anything and save: the change is taken on at once.
// The guide: docs/getting-started.md in the Caulis folder.
fun main() = caulis {
    title = "My scrolling compositor"
    configName = "my-compositor"            // your folder of files: ~/.config/my-compositor

    layout = ScrollingColumns(
        gaps = Gaps(inner = 10, outer = 10),
        widths = listOf(1.0 / 3, 0.5, 2.0 / 3, 1.0),   // what Super+W goes through, shares of the screen
        newWidth = 0.5,                     // how wide a new column is
        centerFocused = false,              // true: the focused column always in the middle
    )

    effects = Effects.MODERN                // round corners, a border, soft shadows; Effects.NONE: none
    terminal = "foot"                       // what Super+Return opens
    keyboardLayouts = "us"                  // as for setxkbmap: "us,de", "us,ru" ...

    keys {
        // Usual keys that come with Caulis: Super+Left/Right go along the strip, Super+Shift+arrows
        // move the column, Super+Ctrl+arrows change its size, Super+Alt+arrows take a window
        // into the next column or out of it. Up and down go within a column instead (below):
        maximize = null
        minimize = null
        columnWidth = "Super+W"             // the column's next width
    }
    bind("Super+Up") { run("focus up") }
    bind("Super+Down") { run("focus down") }
    bind("Super+C") { run("layout-message center") }   // the focused column to the middle, once
    bind("Super+D") { launch("fuzzel") }    // a launcher, any you have installed

    // The wheel with Super held: along the strip.
    bind("Super+WheelDown") { run("focus right") }
    bind("Super+WheelUp") { run("focus left") }

    onStart { openTerminal() }
}
