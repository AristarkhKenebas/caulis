package dev.iriswm

import dev.caulis.core.Scratchpad
import dev.caulis.tiling.Side
import dev.caulis.tiling.builtInLayouts
import dev.caulis.wm.Caulis
import dev.caulis.wm.caulis
import kotlin.system.exitProcess

/** The layouts Super+E goes through, in this order (all built-in ones). */
val layouts = builtInLayouts()

/**
 * irisWM: runs inside the current Wayland session (nested) or directly on the screen.
 * See [Options.USAGE] for the command line.
 */
fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (e: Options.HelpRequested) {
        println(Options.USAGE)
        return
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n\n${Options.USAGE}")
        exitProcess(2)
    }

    caulis {
        mode = if (options.mode == Options.Mode.NESTED) Caulis.Mode.NESTED else Caulis.Mode.SCREEN
        title = "irisWM"
        appId = "dev.iriswm.Nested"
        keyboardLayouts = "us,ru"
        keyboardOptions = "grp:win_space_toggle" // Super+Space switches the layout
        terminal = options.terminal
        layout = layouts.first { it.name.equals(options.layout, ignoreCase = true) }
        stats = options.stats
        directScanout = options.directScanout
        exitAfterSeconds = options.exitAfterSeconds

        // Super+E: the next layout on this workspace (windows move there right away).
        bind("Super+E") {
            val workspace = desktop.activeWorkspace
            val next = layouts[(layouts.indexOfFirst { it.name == workspace.layout.name } + 1) % layouts.size]
            desktop.setLayout(workspace, next)
        }

        // Shown and hidden with one key, above every workspace.
        scratchpad("iris-dropdown", key = "Super+Grave", command = "${options.terminal} --app-id iris-dropdown", style = Scratchpad.Edge(Side.TOP))
        scratchpad("music", key = "Super+M", command = "spotify", style = Scratchpad.Edge(Side.RIGHT, depth = 0.45, length = 0.8, margin = 12)) {
            it.appId.equals("spotify", ignoreCase = true)
        }

        onStart { options.run.forEach(::launch) }
    }
}
