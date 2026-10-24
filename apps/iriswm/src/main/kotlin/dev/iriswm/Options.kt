package dev.iriswm

/** What irisWM was asked to do on the command line. */
data class Options(
    /** Where to run: inside another session or directly on the screen. */
    val mode: Mode,
    /** Quit after this many seconds (a safety net while testing on real hardware). */
    val exitAfterSeconds: Long? = null,
    /** Programs to start once irisWM is running. */
    val run: List<String> = emptyList(),
    /** The terminal Super+Enter opens. */
    val terminal: String = "foot",
    /** Print frame timing every few seconds (frames per second, stutters, GC pauses). */
    val stats: Boolean = false,
    /** Show fullscreen programs' pictures directly on the monitor (no copying, less delay). */
    val directScanout: Boolean = true,
    /** How windows are arranged at start (a built-in layout's name, e.g. Dwindle). */
    val layout: String = "Floating",
) {
    enum class Mode { NESTED, SCREEN }

    companion object {
        const val USAGE = """Usage: iriswm [options]

  --nested            run as a window inside the current Wayland session
                      (the default when WAYLAND_DISPLAY is set)
  --screen            run directly on the screen, from a text console
                      (the default otherwise)
  --exit-after SEC    quit by itself after SEC seconds
  --run COMMAND       start COMMAND inside irisWM (may be given several times)
  --terminal COMMAND  terminal for Super+Enter (default: foot)
  --stats             print frame timing every 5 seconds (fps, late frames, GC pauses)
  --layout NAME       how windows are arranged at start: Floating (default), Master,
                      Dwindle, Spiral, Grid, Strips, Centered, Columns, Rows, Monocle,
                      Scrolling (Super+E goes through all of them while running)
  --no-direct-scanout always draw fullscreen programs instead of showing their own
                      picture directly (only for finding display problems)
  --help              show this text

Keys:
  Super+Enter             open a terminal
  Super+Q                 close the window
  Super+Up                maximize / restore the window
  Super+F                 fullscreen on / off
  Super+Down              minimize (Alt+Tab brings it back)
  Super+V                 take the window out of the layout (floating) / back
  Super+E                 next layout (windows glide to their new places)
  Super+Left / Right      focus the window to the left / right
  Super+1 ... 9           go to workspace 1 ... 9
  Super+Shift+1 ... 9     move the window to workspace 1 ... 9
  Alt+Tab                 switch between windows
  Super + left drag       move a window
  Super + right drag      resize a window
  Ctrl+Alt+F1 ... F12     switch to another text console (screen mode)
  Ctrl+Alt+Backspace      quit irisWM immediately"""

        /** Reads the arguments; throws [IllegalArgumentException] with a readable message. */
        fun parse(args: Array<String>, environment: Map<String, String> = System.getenv()): Options {
            var mode = if (environment["WAYLAND_DISPLAY"].isNullOrEmpty()) Mode.SCREEN else Mode.NESTED
            var exitAfter: Long? = null
            val run = ArrayList<String>()
            var terminal = "foot"
            var stats = false
            var directScanout = true
            var layout = "Floating"
            var i = 0
            fun value(name: String): String = args.getOrNull(++i) ?: throw IllegalArgumentException("$name needs a value")
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--nested" -> mode = Mode.NESTED
                    "--screen" -> mode = Mode.SCREEN
                    "--exit-after" -> exitAfter = value(arg).toLongOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("--exit-after needs a number of seconds")
                    "--run" -> run += value(arg)
                    "--terminal" -> terminal = value(arg)
                    "--stats" -> stats = true
                    "--no-direct-scanout" -> directScanout = false
                    "--layout" -> layout = value(arg).also { name ->
                        require(layouts.any { it.name.equals(name, ignoreCase = true) }) {
                            "Unknown layout \"$name\"; choose from: ${layouts.joinToString { it.name }}"
                        }
                    }
                    "--help", "-h" -> throw HelpRequested()
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
                i++
            }
            return Options(mode, exitAfter, run, terminal, stats, directScanout, layout)
        }
    }

    class HelpRequested : RuntimeException()
}
