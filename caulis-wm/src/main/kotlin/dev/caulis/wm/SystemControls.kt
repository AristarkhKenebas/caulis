package dev.caulis.wm

import dev.caulis.log.Logger
import java.util.concurrent.TimeUnit

/**
 * What a laptop's keys do: the volume and the microphone (PipeWire's wpctl), the screen's
 * brightness (brightnessctl), the touchpad, and music players (playerctl) — each shown on the
 * on-screen display. Steps, limits and the programs are the configuration's [SystemKeys]. The programs are run on a thread of their own: the desktop never waits.
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
    fun volume(percent: Int? = null, mute: Boolean = false) {
        val keys = settings()
        work {
        val sink = "@DEFAULT_AUDIO_SINK@"
        when {
            mute -> run(keys.wpctl, "set-mute", sink, "toggle")
            percent != null -> {
                if (keys.unmuteOnVolumeChange) run(keys.wpctl, "set-mute", sink, "0")
                run(keys.wpctl, "set-volume", "-l", keys.maxVolume.toString(), sink, "${Math.abs(percent)}%${if (percent >= 0) "+" else "-"}")
            }
        }
        val (level, muted) = wpctlVolume(sink) ?: return@work
        show(if (muted) "Muted" else "Volume ${Math.round(level * 100)} %", level, muted)
        }
    }

    // The configuration's settings (read on the compositor's thread, used on the worker's).
    private fun settings(): SystemKeys = running.config.systemKeys.copy().also { keys = it }

    // The settings of the key being handled.
    @Volatile
    private var keys = SystemKeys()

    /** The microphone muted or not (toggled). */
    fun microphone() {
        val keys = settings()
        work {
        val source = "@DEFAULT_AUDIO_SOURCE@"
        run(keys.wpctl, "set-mute", source, "toggle") ?: return@work
        val (_, muted) = wpctlVolume(source) ?: return@work
        show(if (muted) "Microphone off" else "Microphone on")
        }
    }

    /** The screen's brightness up or down by [percent] (through logind, else brightnessctl). */
    fun brightness(percent: Int) {
        val keys = settings()
        work {
        val logind = running.logind
        val backlight = backlight(keys.backlight)
        if (logind != null && backlight != null) {
            val (device, now, max) = backlight
            // Steps of the scale shown, never below the configuration's least (a black screen looks broken).
            val least = if (keys.minBrightnessPercent == 0) 0 else maxOf(1, max * keys.minBrightnessPercent / 100)
            val next = (now + Math.round(max * percent / 100.0).toInt()).coerceIn(least, max)
            running.loop.post { logind.setBrightness(device, next) }
            show("Brightness ${Math.round(next * 100.0 / max)} %", next.toDouble() / max)
            return@work
        }
        val device = keys.backlight?.let { listOf("--device", it) }.orEmpty()
        run(*(listOf(keys.brightnessctl) + device + listOf("--quiet", "--min-value=${keys.minBrightnessPercent}%", "set", "${Math.abs(percent)}%${if (percent >= 0) "+" else "-"}")).toTypedArray()) ?: return@work
        // "intel_backlight,backlight,412,40%,1030": the fourth is the percentage.
        val info = run(*(listOf(keys.brightnessctl) + device + listOf("--machine-readable", "info")).toTypedArray()) ?: return@work
        val value = info.trim().split(',').getOrNull(3)?.removeSuffix("%")?.toIntOrNull() ?: return@work
        show("Brightness $value %", value / 100.0)
        }
    }

    // The screen's backlight: its name, brightness and greatest brightness (sysfs), or null —
    // [name] (SystemKeys.backlight), else the first there.
    private fun backlight(name: String?): Triple<String, Int, Int>? {
        val folder = java.nio.file.Path.of("/sys/class/backlight")
        val all = runCatching { java.nio.file.Files.list(folder).use { it.toList() } }.getOrNull().orEmpty()
        val device = (if (name != null) all.firstOrNull { it.fileName.toString() == name } else all.minByOrNull { it.fileName.toString() }) ?: return null
        fun number(name: String) = runCatching { java.nio.file.Files.readString(device.resolve(name)).trim().toInt() }.getOrNull()
        val max = number("max_brightness")?.takeIf { it > 0 } ?: return null
        val now = number("brightness") ?: return null
        return Triple(device.fileName.toString(), now, max)
    }

    /** A music player: "play-pause", "next", "previous", "stop". */
    fun media(command: String) {
        val keys = settings()
        work { run(keys.playerctl, command) }
    }

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

    fun close() {
        worker.shutdownNow()
        readers.shutdownNow()
    }

    // Reads the programs' output.
    private val readers = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task, "caulis-system-output").apply { isDaemon = true } }

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

    // Runs a program and returns what it printed (null if it is not there, failed, or took
    // longer than the settings allow: then it is stopped, and the next key goes on).
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
        process.outputStream.close()
        // (Read on a thread of its own: a program that never ends its output is not waited for.)
        val reading = java.util.concurrent.CompletableFuture.supplyAsync({ process.inputStream.bufferedReader().readText() }, readers)
        val timeout = keys.timeoutMillis
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            log.warn("${command.joinToString(" ")} did not finish in $timeout ms: stopped")
            process.destroyForcibly()
            reading.cancel(true)
            return null
        }
        val output = try {
            reading.get(timeout, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            log.warn("${command.joinToString(" ")}: its output could not be read ($e)")
            return null
        }
        if (process.exitValue() != 0) {
            log.info { "${command.joinToString(" ")}: ${output.trim().take(200)}" }
            return null
        }
        return output
    }
}
