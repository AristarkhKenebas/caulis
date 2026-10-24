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
  --help              show this text

Keys:
  Super+Enter             open a terminal
  Ctrl+Alt+F1 ... F12     switch to another text console (screen mode)
  Ctrl+Alt+Backspace      quit irisWM immediately"""

        /** Reads the arguments; throws [IllegalArgumentException] with a readable message. */
        fun parse(args: Array<String>, environment: Map<String, String> = System.getenv()): Options {
            var mode = if (environment["WAYLAND_DISPLAY"].isNullOrEmpty()) Mode.SCREEN else Mode.NESTED
            var exitAfter: Long? = null
            val run = ArrayList<String>()
            var terminal = "foot"
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
                    "--help", "-h" -> throw HelpRequested()
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
                i++
            }
            return Options(mode, exitAfter, run, terminal)
        }
    }

    class HelpRequested : RuntimeException()
}
