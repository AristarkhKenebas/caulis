import dev.caulis.core.Effects
import dev.caulis.core.TitleBars
import dev.caulis.tiling.Gaps
import dev.caulis.tiling.ManualTiling
import dev.caulis.wm.BindOptions
import dev.caulis.wm.caulis

// An i3-like compositor: you decide where the next window goes (beside or below the focused one),
// everything is done from the keyboard, and Super+R is a mode for resizing.
// Start it with ./gradlew run, then change anything and save: the change is taken on at once.
// The guide: docs/getting-started.md in the Caulis folder.
fun main() = caulis {
    title = "My tiling compositor"
    configName = "my-compositor"            // your folder of files: ~/.config/my-compositor

    // Containers like i3's. For windows placed by themselves instead: Dwindle(), MasterStack().
    layout = ManualTiling(Gaps(inner = 6, outer = 6))

    effects = Effects.MODERN                // a border shows which window has the focus; also round corners, shadows
    titleBars = TitleBars.NONE              // no title bars
    terminal = "foot"                       // what Super+Return opens
    keyboardLayouts = "us"                  // as for setxkbmap: "us,de", "us,ru" ...

    // The usual keys that come with Caulis, changed to i3's places: Super+1..9 go to a workspace
    // and Super+Shift+1..9 move the window there, as they are.
    keys {
        close = "Super+Shift+Q"
        lock = "Super+Shift+X"
        float = "Super+Shift+Space"
        splitBeside = "Super+B"             // the next window opens beside the focused one
        splitBelow = "Super+V"              // ... or below it
        maximize = null                     // the arrows go around instead (below)
        minimize = null
        focusLeft = null
        focusRight = null
        rotate = null                       // Super+R is the resize mode here
        balance = "Super+Shift+B"           // all sizes even
    }

    // Going around and moving the window: the arrows or H, J, K, L, with Shift to move.
    for ((key, way) in listOf("Left" to "left", "Down" to "down", "Up" to "up", "Right" to "right", "H" to "left", "J" to "down", "K" to "up", "L" to "right")) {
        bind("Super+$key") { run("focus $way") }
        bind("Super+Shift+$key") { run("move $way") }
    }

    bind("Super+D") { launch("fuzzel") }    // a launcher, any you have installed

    // The resize mode: arrows change the size, Escape or Return leaves it.
    bind("Super+R") { enterKeyMode("resize") }
    keyMode("resize") {
        for (way in listOf("left", "down", "up", "right")) {
            bind(way.replaceFirstChar { it.uppercase() }, BindOptions(repeat = true)) { run("resize $way") }
        }
        bind("Escape") { enterKeyMode(null) }
        bind("Return") { enterKeyMode(null) }
    }

    // Some windows float above the tiles.
    rule { program("pavucontrol"); floating = true; size(700, 500); center() }

    onStart { openTerminal() }
}
