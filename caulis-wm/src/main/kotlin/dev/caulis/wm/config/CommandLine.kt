package dev.caulis.wm.config

import dev.caulis.ipc.CaulisIpc
import dev.caulis.ipc.IpcException
import dev.caulis.ipc.v1.EventKind
import dev.caulis.wm.Caulis
import java.nio.file.Path

/**
 * What a compositor built with Caulis answers on the command line instead of starting:
 *
 * ```
 * my-compositor --check-config [file]     the file's mistakes (exit 1), or "ok" (exit 0)
 * my-compositor --print-json-schema       the JSON Schema of config.json (for editors)
 * my-compositor --print-nix-options       the Nix options of config.json (for the Nix module)
 *
 * And, to a running one (its IPC socket, see caulis.proto), with JSON for scripts:
 *
 * my-compositor --do <action> [--window <id>]   runs an action ("exec firefox", "workspace 2", ...)
 * my-compositor --state                          what is on screen
 * my-compositor --events [kind ...]              what changes, a line each (kinds: windows,
 *                                                focus, workspaces, monitors, notices, config, lock)
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
            "--do", "--state", "--events" -> talk(caulis, arguments)
            else -> null
        }
    }

    // Talks to the running compositor (as a program would, through its IPC socket).
    private fun talk(caulis: Caulis, arguments: List<String>): Int {
        val ipc = try {
            CaulisIpc.connect(CaulisIpc.findSocket(name = caulis.configName))
        } catch (e: java.io.IOException) {
            System.err.println("${caulis.configName} does not seem to run: ${e.message}")
            return 2
        }
        ipc.use {
            try {
                when (arguments[0]) {
                    "--do" -> {
                        val words = arguments.drop(1)
                        val at = words.indexOf("--window")
                        val window = if (at >= 0) words.getOrNull(at + 1)?.toLongOrNull() ?: return usage("--window needs a window id (see --state)") else 0L
                        val action = (if (at >= 0) words.take(at) + words.drop(at + 2) else words).joinToString(" ")
                        if (action.isBlank()) return usage("--do needs an action, e.g. --do workspace 2")
                        ipc.run(action, window)
                    }
                    "--state" -> println(Json.write(IpcJson.state(ipc.state())))
                    "--events" -> {
                        val kinds = arguments.drop(1).map { name ->
                            EventKind.entries.firstOrNull { it.name == "EVENT_KIND_" + name.uppercase().replace('-', '_') && it != EventKind.EVENT_KIND_UNSPECIFIED }
                                ?: return usage("\"$name\" is not a kind of event (windows, focus, workspaces, monitors, notices, config, lock)")
                        }
                        ipc.subscribe(*kinds.toTypedArray())
                        while (true) {
                            val event = ipc.nextEvent() ?: break
                            println(Json.writeLine(IpcJson.event(event)))
                            System.out.flush()
                        }
                    }
                }
            } catch (e: IpcException) {
                System.err.println(e.message)
                return 1
            }
        }
        return 0
    }

    private fun usage(message: String): Int {
        System.err.println(message)
        return 2
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
