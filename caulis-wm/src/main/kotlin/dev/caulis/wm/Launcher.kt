package dev.caulis.wm

import dev.caulis.log.Logger

/**
 * Starts programs the Wayland way first: toolkits that can do both (GTK, Qt, Electron,
 * Firefox) are told to prefer Wayland and fall back to X11 themselves. A program that still
 * fails — it quits with an error soon after starting, before it opened any window — is
 * started again through X11 (Xwayland), and from then on started that way right away.
 *
 * [environment] fills in what every program gets (WAYLAND_DISPLAY, DISPLAY, the cursor, ...);
 * [x11Available] tells whether X11 programs can run now; results of programs arrive on
 * other threads and are handed to [post] (the compositor's thread).
 */
internal class Launcher(
    private val environment: (MutableMap<String, String>) -> Unit,
    private val x11Available: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val settings: () -> Settings,
) {
    /**
     * What the user chose: prefer Wayland (and fall back), programs that always go through
     * X11; the screen's [scale], and whether whole-scale programs get an X server of their own
     * ([sharpWholeScales]: then they draw at the next whole scale up and are shown smaller).
     */
    class Settings(val waylandFirst: Boolean, val x11Programs: Set<String>, val scale: Double = 1.0, val sharpWholeScales: Boolean = false)

    private val log = Logger("clients")

    // Programs that needed X11 this session (by program name).
    private val neededX11 = HashSet<String>()

    // Programs started the Wayland way, until they showed a window or their time to fail passed.
    private class Started(val command: String, val pid: Long, val startedNanos: Long) {
        var showedWindow = false
    }

    private val watched = ArrayList<Started>()

    /** Starts [command] (run by /bin/sh); [x11] forces X11. */
    fun launch(command: String, x11: Boolean = false) {
        val program = programName(command)
        val settings = settings()
        val throughX = x11Available() && (x11 || program in settings.x11Programs || program in neededX11)
        start(command, throughX, watch = !throughX && settings.waylandFirst && x11Available())
    }

    /** A window was opened by process [pid] (or one of its children): its program started fine. */
    fun windowOpened(pid: Int) {
        if (pid <= 0 || watched.isEmpty()) return
        val ancestors = generateSequence(ProcessHandle.of(pid.toLong()).orElse(null)) { it.parent().orElse(null) }
            .take(MAX_DEPTH).map { it.pid() }.toSet()
        for (started in watched) if (started.pid in ancestors) started.showedWindow = true
        watched.removeAll { it.showedWindow }
    }

    private fun start(command: String, throughX: Boolean, watch: Boolean) {
        log.info { "Starting: $command${if (throughX) " (through X11)" else ""}" }
        try {
            val builder = ProcessBuilder("/bin/sh", "-c", command)
            builder.environment().apply {
                environment(this)
                put("_JAVA_AWT_WM_NONREPARENTING", "1") // Java on X11: windows are not inside frames here
                javaScale(this)
                if (throughX) {
                    remove("WAYLAND_DISPLAY")
                    remove("WAYLAND_SOCKET")
                    remove("NIXOS_OZONE_WL")
                    putAll(X11)
                } else if (settings().waylandFirst) {
                    // The user's own choices (set before the compositor started) stay.
                    for ((name, value) in WAYLAND_FIRST) if (System.getenv(name) == null) put(name, value)
                }
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            val process = builder.start()
            val started = Started(command, process.pid(), System.nanoTime())
            if (watch) watched += started
            process.onExit().thenAccept { ended -> post { ended(started, ended.exitValue(), throughX) } }
        } catch (e: Exception) {
            log.warn("\"$command\" could not be started: ${e.message}")
        }
    }

    // Java (Swing, AWT on X11) reads neither Xft.dpi nor the X cursor size, only its own
    // scale — and in whole numbers only (on X11 it has no fractional scale at all, not even
    // with -Dsun.java2d.uiScale). With an X server of its own it draws at the next whole one
    // up (1.5 -> 2) and is shown smaller, sharp and the right size; else at the nearest.
    private fun javaScale(env: MutableMap<String, String>) {
        if (System.getenv("J2D_UISCALE") != null) return
        val settings = settings()
        val whole = if (settings.sharpWholeScales) Math.ceil(settings.scale - 1e-9).toInt() else Math.round(settings.scale).toInt()
        if (whole > 1) env["J2D_UISCALE"] = whole.toString() else env.remove("J2D_UISCALE")
    }

    private fun ended(started: Started, status: Int, throughX: Boolean) {
        val wasWatched = watched.remove(started)
        when {
            status == NOT_FOUND -> log.warn("\"${started.command}\" could not be started: the program was not found (is it installed?)")
            status == NOT_EXECUTABLE -> log.warn("\"${started.command}\" could not be started: it is not a program one can run")
            throughX || !wasWatched || started.showedWindow -> log.trace { "\"${started.command}\" ended (status $status)" }
            status == 0 || status in ENDED_BY_USER -> log.trace { "\"${started.command}\" ended (status $status)" }
            System.nanoTime() - started.startedNanos > FAIL_WITHIN_NANOS -> log.trace { "\"${started.command}\" ended (status $status)" }
            !x11Available() -> log.info { "\"${started.command}\" quit at once (status $status); X11 is off, so it is not tried that way" }
            else -> {
                val program = programName(started.command)
                neededX11 += program
                log.info {
                    "\"${started.command}\" quit at once on Wayland (status $status): starting it through X11. " +
                        "To start it that way right away: x11Programs += \"$program\""
                }
                start(started.command, throughX = true, watch = false)
            }
        }
    }

    companion object {
        // A program that fails this soon, without a window, did not manage to start.
        private const val FAIL_WITHIN_NANOS = 20_000_000_000L
        private const val MAX_DEPTH = 16
        private const val NOT_FOUND = 127
        private const val NOT_EXECUTABLE = 126
        private val ENDED_BY_USER = setOf(130, 137, 143) // Ctrl+C, killed, terminated

        /** Toolkits prefer Wayland and fall back to X11 by themselves where they can. */
        val WAYLAND_FIRST = mapOf(
            "GDK_BACKEND" to "wayland,x11",
            "QT_QPA_PLATFORM" to "wayland;xcb",
            "ELECTRON_OZONE_PLATFORM_HINT" to "auto",
            "MOZ_ENABLE_WAYLAND" to "1",
            "NIXOS_OZONE_WL" to "1",
            "XDG_SESSION_TYPE" to "wayland",
        )

        /** Everything through X11. */
        val X11 = mapOf(
            "GDK_BACKEND" to "x11",
            "QT_QPA_PLATFORM" to "xcb",
            "ELECTRON_OZONE_PLATFORM_HINT" to "x11",
            "MOZ_ENABLE_WAYLAND" to "0",
            "SDL_VIDEODRIVER" to "x11",
            "XDG_SESSION_TYPE" to "x11",
        )

        /** The program a command starts: "env FOO=1 /usr/bin/steam -silent" → "steam". */
        fun programName(command: String): String {
            val words = command.trim().split(Regex("\\s+")).dropWhile { it == "env" || it == "exec" || it == "nohup" || '=' in it }
            return words.firstOrNull()?.substringAfterLast('/').orEmpty()
        }
    }
}
