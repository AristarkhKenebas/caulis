package dev.caulis.wm

import dev.caulis.log.Logger
import java.util.concurrent.TimeUnit

/**
 * What a laptop's keys do: the volume and the microphone (PipeWire's wpctl), the screen's
 * brightness (brightnessctl), the touchpad, and music players (playerctl) — each shown on the
 * on-screen display. The programs are run on a thread of their own: the desktop never waits.
 * A program that is not installed is said once, on screen and in the log.
 */
internal class SystemControls(private val running: Running) {

    private val log = Logger("keys")
    private val missing = HashSet<String>()

    // One at a time, in order (keys held down come quickly).
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "caulis-system-keys").apply { isDaemon = true }
    }

    /** Volume up or down by [percent] (negative: down), or [mute] toggled. */
    fun volume(percent: Int? = null, mute: Boolean = false) = work {
        val sink = "@DEFAULT_AUDIO_SINK@"
        when {
            mute -> run("wpctl", "set-mute", sink, "toggle")
            percent != null -> {
                run("wpctl", "set-mute", sink, "0")
                run("wpctl", "set-volume", "-l", "1.0", sink, "${Math.abs(percent)}%${if (percent >= 0) "+" else "-"}")
            }
        }
        val (level, muted) = wpctlVolume(sink) ?: return@work
        show(if (muted) "Muted" else "Volume ${Math.round(level * 100)} %", level, muted)
    }

    /** The microphone muted or not (toggled). */
    fun microphone() = work {
        val source = "@DEFAULT_AUDIO_SOURCE@"
        run("wpctl", "set-mute", source, "toggle") ?: return@work
        val (_, muted) = wpctlVolume(source) ?: return@work
        show(if (muted) "Microphone off" else "Microphone on")
    }

    /** The screen's brightness up or down by [percent] (through logind, else brightnessctl). */
    fun brightness(percent: Int) = work {
        val logind = running.logind
        val backlight = backlight()
        if (logind != null && backlight != null) {
            val (device, now, max) = backlight
            // Steps of the scale shown, never quite off (a black screen looks broken).
            val next = (now + Math.round(max * percent / 100.0).toInt()).coerceIn(maxOf(1, max / 100), max)
            running.loop.post { logind.setBrightness(device, next) }
            show("Brightness ${Math.round(next * 100.0 / max)} %", next.toDouble() / max)
            return@work
        }
        run("brightnessctl", "--quiet", "set", "${Math.abs(percent)}%${if (percent >= 0) "+" else "-"}") ?: return@work
        // "intel_backlight,backlight,412,40%,1030": the fourth is the percentage.
        val info = run("brightnessctl", "--machine-readable", "info") ?: return@work
        val value = info.trim().split(',').getOrNull(3)?.removeSuffix("%")?.toIntOrNull() ?: return@work
        show("Brightness $value %", value / 100.0)
    }

    // The screen's backlight: its name, brightness and greatest brightness (sysfs), or null.
    private fun backlight(): Triple<String, Int, Int>? {
        val folder = java.nio.file.Path.of("/sys/class/backlight")
        val device = runCatching { java.nio.file.Files.list(folder).use { it.toList() } }.getOrNull()?.minByOrNull { it.fileName.toString() } ?: return null
        fun number(name: String) = runCatching { java.nio.file.Files.readString(device.resolve(name)).trim().toInt() }.getOrNull()
        val max = number("max_brightness")?.takeIf { it > 0 } ?: return null
        val now = number("brightness") ?: return null
        return Triple(device.fileName.toString(), now, max)
    }

    /** A music player: "play-pause", "next", "previous", "stop". */
    fun media(command: String) = work { run("playerctl", command) }

    /** The touchpad on, off, or the other way ([on] null). */
    fun touchpad(on: Boolean?) {
        val devices = running.inputDevices
        if (devices == null || !devices.hasTouchpad) {
            running.desktop.showLevel("No touchpad here")
            return
        }
        devices.touchpadsOn = on ?: !devices.touchpadsOn
        running.desktop.showLevel(if (devices.touchpadsOn) "Touchpad on" else "Touchpad off")
    }

    /**
     * The touchpad off while typing (a palm on it moves nothing), on, off or the other way
     * ([on] null); the setting is [Caulis.input]'s, taken on at once.
     */
    fun touchpadWhileTyping(on: Boolean?) {
        val devices = running.inputDevices
        if (devices == null || !devices.hasTouchpad) {
            running.desktop.showLevel("No touchpad here")
            return
        }
        val settings = running.config.input
        // (Unset: the touchpad's own default, which is off while typing nearly everywhere.)
        val guard = on ?: (settings.touchpads.disableWhileTyping == false)
        settings.touchpads.disableWhileTyping = guard
        devices.settings = settings
        log.info { "Touchpad while typing: ${if (guard) "off" else "on"}" }
        running.desktop.showLevel(if (guard) "Touchpad off while typing" else "Touchpad works while typing")
    }

    fun close() = worker.shutdownNow()

    // --- running the programs ---------------------------------------------------------------

    private fun work(task: () -> Unit) {
        worker.execute {
            try {
                task()
            } catch (e: Exception) {
                log.warn("A system key failed: $e")
            }
        }
    }

    private fun show(title: String, level: Double? = null, off: Boolean = false) =
        running.loop.post { running.desktop.showLevel(title, level, off) }

    // "Volume: 0.45 [MUTED]"
    private fun wpctlVolume(target: String): Pair<Double, Boolean>? {
        val text = run("wpctl", "get-volume", target) ?: return null
        val level = Regex("""Volume:\s*([0-9.]+)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return level to ("MUTED" in text)
    }

    // Runs a program and returns what it printed (null if it is not there or failed).
    private fun run(vararg command: String): String? {
        val process = try {
            ProcessBuilder(*command).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            if (missing.add(command[0])) {
                log.warn("${command[0]} is not installed: the key needs it")
                running.loop.post { running.desktop.showLevel("${command[0]} is not installed") }
            }
            return null
        }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) {
            log.info { "${command.joinToString(" ")}: ${output.trim().take(200)}" }
            return null
        }
        return output
    }
}
