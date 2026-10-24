package dev.caulis.wm

import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks before starting that everything a compositor needs is there, and says in plain
 * words what is missing and how to fix it — instead of failing later with a stack trace.
 *
 * Problems are either fatal (it cannot start) or warnings (it starts, but something will
 * not work, e.g. the terminal key).
 */
internal object Preflight {

    class Problem(val fatal: Boolean, val message: String, val fix: String)

    /** Everything wrong for running [config] in the given mode ([nested] or on the screen). */
    fun check(config: Caulis, nested: Boolean): List<Problem> = buildList {
        // Talking to C libraries needs an explicit permission from Java.
        if (!Caulis::class.java.module.isNativeAccessEnabled) {
            add(
                Problem(
                    fatal = false,
                    message = "Java was started without --enable-native-access (it will print warnings).",
                    fix = "Add \"--enable-native-access=ALL-UNNAMED\" to the JVM options (applicationDefaultJvmArgs in Gradle).",
                ),
            )
        }

        val missing = REQUIRED_LIBRARIES.filter { (library, _) -> !libraryExists(library) }
        if (missing.isNotEmpty()) {
            add(
                Problem(
                    fatal = true,
                    message = "Missing system libraries: ${missing.joinToString { it.first }}.",
                    fix = "Install them, e.g. on Arch: sudo pacman -S ${missing.map { it.second }.distinct().joinToString(" ")}" +
                        " (Debian/Ubuntu: libegl1 libgles2 libgbm1 libdrm2 libxkbcommon0 libinput10 libseat1).",
                ),
            )
        }

        if (!Files.isDirectory(Path.of("/dev/dri"))) {
            add(Problem(true, "No GPU was found (/dev/dri is missing).", "Install the graphics driver for your GPU (Mesa for AMD/Intel, nvidia for NVIDIA)."))
        }

        val runtimeDir = System.getenv("XDG_RUNTIME_DIR")
        if (runtimeDir.isNullOrEmpty() && config.socketDirectory == null) {
            add(Problem(true, "XDG_RUNTIME_DIR is not set, so there is no place for the Wayland socket.", "Log in normally (through a display manager or a text console login), not with su or sudo."))
        }

        if (nested) {
            val display = System.getenv("WAYLAND_DISPLAY")
            val socket = config.nestedIn ?: display?.let { if (it.startsWith("/")) Path.of(it) else runtimeDir?.let { dir -> Path.of(dir, it) } }
            if (socket == null || !Files.exists(socket)) {
                add(Problem(true, "There is no Wayland session to open a window in (WAYLAND_DISPLAY=${display ?: "not set"}).", "Start it inside a Wayland desktop, or from a text console (Ctrl+Alt+F3) to use the whole screen."))
            }
        } else {
            if (Files.getAttribute(Path.of("/proc/self"), "unix:uid") == 0) {
                add(Problem(true, "Running as root.", "Start it as your normal user; a compositor never needs root."))
            }
            if (!libraryExists("libseat.so.1")) {
                add(Problem(true, "libseat is missing, so the screen and input devices cannot be opened.", "Install seatd/libseat (it works with systemd-logind too)."))
            }
        }

        if (config.useDefaultBindings && !commandExists(config.terminal)) {
            add(
                Problem(
                    fatal = false,
                    message = "The terminal \"${config.terminal}\" is not installed, so Super+Return will do nothing.",
                    fix = "Install it, or choose another one: terminal = \"alacritty\" (or kitty, wezterm, ...).",
                ),
            )
        }
    }

    /** Prints [problems]; returns false if any of them is fatal. */
    fun report(problems: List<Problem>, name: String): Boolean {
        for (problem in problems) {
            val kind = if (problem.fatal) "Problem" else "Note"
            System.err.println("$kind: ${problem.message}\n  → ${problem.fix}")
        }
        if (problems.any { it.fatal }) {
            System.err.println("$name cannot start until the problems above are fixed.")
            return false
        }
        return true
    }

    // Library name -> the Arch package that has it.
    private val REQUIRED_LIBRARIES = listOf(
        "libEGL.so.1" to "libglvnd",
        "libGLESv2.so.2" to "libglvnd",
        "libgbm.so.1" to "mesa",
        "libdrm.so.2" to "libdrm",
        "libxkbcommon.so.0" to "libxkbcommon",
    )

    private fun libraryExists(name: String): Boolean = try {
        Arena.ofConfined().use { arena -> SymbolLookup.libraryLookup(name, arena) }
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    // True if the first word of [command] is a program on PATH (or an existing path).
    private fun commandExists(command: String): Boolean {
        val program = command.trim().substringBefore(' ').ifEmpty { return false }
        if (program.contains('/')) return Files.isExecutable(Path.of(program))
        return System.getenv("PATH").orEmpty().split(':').filter { it.isNotEmpty() }
            .any { Files.isExecutable(Path.of(it, program)) }
    }
}
