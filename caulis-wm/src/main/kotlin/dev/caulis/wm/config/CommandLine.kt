package dev.caulis.wm.config

import dev.caulis.wm.Caulis
import java.nio.file.Path

/**
 * What a compositor built with Caulis answers on the command line instead of starting:
 *
 * ```
 * my-compositor --check-config [file]     the file's mistakes (exit 1), or "ok" (exit 0)
 * my-compositor --print-json-schema       the JSON Schema of config.json (for editors)
 * my-compositor --print-nix-options       the Nix options of config.json (for the Nix module)
 * ```
 */
object CommandLine {

    /** The exit status if the command line asked for one of these; null: start as usual. */
    fun handle(caulis: Caulis, arguments: List<String> = programArguments()): Int? {
        return when (arguments.firstOrNull()) {
            "--check-config" -> {
                val path = arguments.getOrNull(1)?.let { Path.of(it) } ?: ConfigFile.location(caulis) ?: caulis.file("config.json")
                val problems = ConfigFile.check(path)
                if (problems.isEmpty()) {
                    println("$path: ok")
                    0
                } else {
                    System.err.println("$path:")
                    problems.forEach { System.err.println("  $it") }
                    1
                }
            }
            "--print-json-schema" -> {
                println(jsonSchema())
                0
            }
            "--print-nix-options" -> {
                println(nixOptions())
                0
            }
            else -> null
        }
    }

    /** The JSON Schema of config.json. */
    fun jsonSchema(): String =
        Json.write(Json.parse("""{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","title":"Caulis configuration",""" + Settings.root.jsonSchema().drop(1)))

    /**
     * The Nix options of config.json, as a function of `lib`: one option per setting, typed
     * with the same limits the compositor checks.
     */
    fun nixOptions(): String = buildString {
        appendLine("# Generated from the compositor's own description of its settings (--print-nix-options).")
        appendLine("# Every option is null (not set) by default; set ones end up in config.json.")
        appendLine("{ lib }:")
        appendLine("let")
        appendLine("  settingsType = ${Settings.root.nix().replace("\n", "\n  ")};")
        appendLine("in")
        appendLine("settingsType")
    }

    // The words after the main class on the java command line.
    private fun programArguments(): List<String> =
        System.getProperty("sun.java.command")?.trim()?.split(Regex("\\s+"))?.drop(1).orEmpty()
}
