package dev.caulis.examples.windowrules

import dev.caulis.tiling.Dwindle
import dev.caulis.wm.caulis

fun main() = caulis {
    layout = Dwindle()

    // Every new Firefox window opens on workspace 2 (you stay where you are).
    rule { appId("firefox"); workspace = 2 }

    // These float above the tiled windows instead of taking a tile.
    rule { appId("pavucontrol"); floating = true; size(700, 500); center() }
    rule { title("Picture-in-Picture"); floating = true; size(480, 270); position(20, 20) }

    // Spotify sets its title after opening: the rule applies when it starts to fit.
    rule { title("Spotify"); workspace = 9; focus = false }

    // Log what happens (handy while learning).
    onWindowOpened { window -> println("Opened: ${window.title} (${window.appId})") }
    onWindowClosed { window -> println("Closed: ${window.title}") }
    onWorkspaceChanged { number -> println("Now on workspace $number") }
}
