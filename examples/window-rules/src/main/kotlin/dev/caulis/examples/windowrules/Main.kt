package dev.caulis.examples.windowrules

import dev.caulis.core.layout.Dwindle
import dev.caulis.wm.caulis

fun main() = caulis {
    layout = Dwindle()

    // These programs float above the tiled windows instead of taking a tile.
    floatWhen { window -> window.appId == "pavucontrol" || window.title == "Picture-in-Picture" }

    // Every new Firefox window goes to workspace 2.
    onWindowOpened { window ->
        if (window.appId == "firefox") desktop.moveToWorkspace(window, 2)
    }

    // Log what happens (handy while learning).
    onWindowOpened { window -> println("Opened: ${window.title} (${window.appId})") }
    onWindowClosed { window -> println("Closed: ${window.title}") }
    onWorkspaceChanged { number -> println("Now on workspace $number") }
}
