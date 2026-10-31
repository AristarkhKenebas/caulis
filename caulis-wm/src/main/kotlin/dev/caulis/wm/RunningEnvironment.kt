package dev.caulis.wm

import dev.caulis.xwayland.Xwayland
import dev.caulis.core.cursor.CursorTheme

/**
 * The environment programs get, and telling the session's services about it.
 */
internal class RunningEnvironment(private val running: Running) {

    // What every program started inside gets in its environment (on top of the compositor's
    // own) — Caulis's choices only: the user's [Caulis.environment] goes on top last (see
    // userEnvironment). [token]: a fresh activation token too (for a program started now).
    internal fun fillEnvironment(env: MutableMap<String, String>, token: Boolean = true) {
        env["WAYLAND_DISPLAY"] = running.socket
        // A good activation token: its first window may take the focus.
        if (token) env["XDG_ACTIVATION_TOKEN"] = running.activation.issue() else env.remove("XDG_ACTIVATION_TOKEN")
        running.ipc?.let { env[dev.caulis.ipc.CaulisIpc.SOCKET_VARIABLE] = it.path.toString() } ?: env.remove(dev.caulis.ipc.CaulisIpc.SOCKET_VARIABLE)
        env["XDG_CURRENT_DESKTOP"] = running.config.desktopName
        // (Started from a text console the compositor itself was "tty": its programs are Wayland's.)
        env["XDG_SESSION_TYPE"] = "wayland"
        env["XDG_SESSION_DESKTOP"] = running.config.desktopName
        // X11 programs (started now or later by these) reach Xwayland here.
        running.xwayland?.let { env["DISPLAY"] = it.displayName } ?: env.remove("DISPLAY")
        // Programs that draw their own cursor use the same theme and size.
        val theme = running.desktop.cursorTheme
        env["XCURSOR_THEME"] = theme.name
        // On a scaled screen X11 programs need the cursor size in pixels and Wayland ones in
        // units: no XCURSOR_SIZE then (it would win over both); X11 programs get it from the
        // X resources, Wayland ones from the compositor (cursor-shape) or their own default.
        if (running.desktop.scale == 1.0) env["XCURSOR_SIZE"] = theme.size.toString() else env.remove("XCURSOR_SIZE")
        running.cursorFolder?.let { folder -> env["XCURSOR_PATH"] = (listOf(folder) + CursorTheme.searchPath()).joinToString(":") }
    }

    // The user's own environment ([Caulis.environment]) on top of everything else — except what
    // must be the compositor's for programs to reach it at all.
    internal fun userEnvironment(env: MutableMap<String, String>) {
        for ((name, value) in running.config.environment) {
            if (name in OWN_VARIABLES) continue
            if (value == null) env.remove(name) else env[name] = value
        }
    }

    // systemd's user manager and D-Bus activation get the compositor's environment (the services
    // they start then reach it); both quietly do nothing where they are not.
    internal fun exportEnvironment() {
        // (No activation token: it would be handed to services that never use it.)
        val env = HashMap(System.getenv()).also { fillEnvironment(it, token = false) }.also(::userEnvironment)
        val ours = listOf("WAYLAND_DISPLAY", "DISPLAY", "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE", "XDG_SESSION_DESKTOP")
        val names = (ours + running.config.environment.filterValues { it != null }.keys.filter { it !in OWN_VARIABLES }).distinct().filter { env[it] != null }
        val pairs = names.map { "$it=${env[it]}" }
        val portals = running.config.portalsToRestart.toList()
        // (On a thread of its own: the commands are waited for, one after the other.)
        Thread({
            fun run(command: List<String>): Boolean = try {
                val process = dev.caulis.ffi.Processes.start(
                    ProcessBuilder(command).also { it.environment().putAll(env) }
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD),
                )
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS).also { if (!it) process.destroyForcibly() } && process.exitValue() == 0
            } catch (e: java.io.IOException) {
                if (dev.caulis.ffi.Processes.isNotFound(e)) running.log.trace { "${command.first()} is not there" }
                else dev.caulis.ffi.Processes.tellStartFailed(running.log, command.first(), e)
                false
            }
            run(listOf("systemctl", "--user", "import-environment") + names)
            run(listOf("dbus-update-activation-environment", "--systemd") + pairs)
            running.log.info { "The session's services know this compositor: ${pairs.joinToString()}" }
            // Portals started before (by the login, by a session before) still have the old
            // environment: a link they open went to no screen (Telegram's links did nothing).
            // Started again, only those running.
            if (portals.isNotEmpty() && staleServices(env["WAYLAND_DISPLAY"])) {
                // (One by one: a portal not installed failed the whole command, and none restarted.)
                val restarted = portals.filter { run(listOf("systemctl", "--user", "try-restart", it)) }
                running.log.info { "The portals were started again with this compositor's environment (${restarted.joinToString()})" }
            }
        }, "caulis-environment").apply { isDaemon = true }.start()
    }

    // True if xdg-desktop-portal runs with another WAYLAND_DISPLAY than [ours] (or none).
    private fun staleServices(ours: String?): Boolean = try {
        java.nio.file.Files.list(java.nio.file.Path.of("/proc")).use { procs ->
            procs.filter { it.fileName.toString().all(Char::isDigit) }.anyMatch { proc ->
                val name = runCatching { java.nio.file.Files.readString(proc.resolve("comm")).trim() }.getOrNull()
                if (name?.startsWith("xdg-desktop-po") != true) return@anyMatch false // (comm is cut to 15 characters)
                val environ = runCatching { String(java.nio.file.Files.readAllBytes(proc.resolve("environ"))) }.getOrNull() ?: return@anyMatch false
                environ.split('\u0000').none { it == "WAYLAND_DISPLAY=$ours" }
            }
        }
    } catch (_: Exception) {
        false
    }

    /** The environment programs started inside get (WAYLAND_DISPLAY, DISPLAY, the cursor, the user's own, ...). */
    fun programEnvironment(): Map<String, String> = HashMap(System.getenv()).also { fillEnvironment(it) }.also(::userEnvironment)
}
