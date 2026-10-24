package dev.caulis.examples.minimal

import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Floating
import dev.caulis.tiling.Gaps
import dev.caulis.wm.caulis

// A complete Wayland compositor: tiling with gaps, a few keys of its own, and the usual
// ones (Super+1..9 workspaces, Super+Q close, Alt+Tab, ...) from Caulis.
// Run it in your session (it opens as a window) or from a text console (full screen).
fun main() = caulis {
    title = "Minimal"
    layout = Dwindle(Gaps(inner = 8, outer = 12))
    keyboardLayouts = "us,ru"
    keyboardOptions = "grp:win_space_toggle"

    bind("Super+Return") { launch("foot") }
    bind("Super+D") { launch("fuzzel") }
    bind("Super+T") { layout = if (layout === Floating) Dwindle() else Floating }

    onStart { launch("foot") }
}
