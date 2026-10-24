package dev.iriswm

import kotlin.system.exitProcess

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

    try {
        when (options.mode) {
            Options.Mode.NESTED -> runNested(options)
            Options.Mode.SCREEN -> runOnScreen(options)
        }
    } catch (e: Exception) {
        // A readable message first (most problems can be fixed by the user), details after.
        System.err.println("irisWM stopped: ${e.message}")
        e.cause?.let { System.err.println("  (${it.message})") }
        System.err.println("\nDetails for a bug report:")
        e.printStackTrace()
        exitProcess(1)
    }
}
