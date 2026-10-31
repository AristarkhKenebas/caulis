package dev.caulis.wm

import dev.caulis.log.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Plays sounds on a thread of its own, through the first of the players that works (see
 * [StartupSound.players]); the log area "sound" says which one did, or why none did.
 */
internal object SoundPlayer {

    private val log = Logger("sound")

    private val thread = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "caulis-sound").apply { isDaemon = true }
    }

    /** Plays what [sound] says (the preset made first if needed), with [env] as the player's environment; never blocks. */
    fun playLater(sound: StartupSound, cacheFolder: Path, resolve: (String) -> Path, env: Map<String, String>, presetOverride: String? = null) {
        val settings = sound.copy()
        thread.execute {
            try {
                val file = fileOf(settings, cacheFolder, resolve, presetOverride) ?: return@execute
                if (settings.delayMillis > 0) Thread.sleep(settings.delayMillis)
                val custom = settings.play
                if (custom != null) {
                    val played = try {
                        custom(file, settings.volume)
                    } catch (e: Exception) {
                        log.warn("Your way to play the startup sound failed: $e")
                        false
                    }
                    if (played) log.info { "Played $file (your own way)" } else log.warn("Your way to play the startup sound did not play $file")
                    return@execute
                }
                play(file, settings.volume, settings.player?.let { listOf(it) } ?: settings.players.map(::words), env, settings.playerTimeoutMillis)
            } catch (e: Exception) {
                log.warn("The startup sound could not be played: $e")
            }
        }
    }

    /** Makes the preset's file ahead (on the sound thread), so it plays at once later. */
    fun prepare(sound: StartupSound, cacheFolder: Path) {
        if (sound.file != null) return
        val preset = sound.preset
        thread.execute {
            runCatching { StartupChimes.file(preset, cacheFolder) }.onFailure { log.warn("The sound $preset could not be made: $it") }
        }
    }

    // The file to play: the user's, or the preset's (made if needed).
    private fun fileOf(sound: StartupSound, cacheFolder: Path, resolve: (String) -> Path, presetOverride: String?): Path? {
        val own = sound.file?.takeIf { presetOverride == null }
        if (own != null) {
            val path = resolve(own)
            if (!Files.isReadable(path)) {
                log.warn("The startup sound $path is not there (or cannot be read)")
                return null
            }
            return path
        }
        return StartupChimes.file(presetOverride ?: sound.preset, cacheFolder)
    }

    /**
     * Plays [file] with the first of [players] (each a command's words) that is installed and
     * plays it (status 0), waiting for it — [timeoutMillis] at most: then it is stopped, and
     * counts as played (it was playing: the next one must not start the sound over). The
     * player that did (its program), or null. On the calling thread.
     */
    fun play(file: Path, volume: Double, players: List<List<String>>, env: Map<String, String> = System.getenv(), timeoutMillis: Long = 30_000): String? {
        val tried = ArrayList<String>()
        for (template in players) {
            val command = commandOf(template, file, volume)
            val program = command.firstOrNull() ?: continue
            if (!DesktopEntries.onPath(program) && !(program.startsWith("/") && Files.isExecutable(Path.of(program)))) {
                log.trace { "$program is not installed" }
                tried += "$program (not installed)"
                continue
            }
            log.trace { "Playing with: ${command.joinToString(" ")}" }
            val process = try {
                dev.caulis.ffi.Processes.start(ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(env)
                })
            } catch (e: Exception) {
                tried += "$program (${dev.caulis.ffi.Processes.reason(e)})"
                continue
            }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroy()
                log.info { "$program still played ${file.fileName} after $timeoutMillis ms: stopped (playerTimeoutMillis)" }
                return program
            }
            val status = process.exitValue()
            if (status == 0) {
                log.info { "Played ${file.fileName} with $program" }
                return program
            }
            log.info { "$program could not play ${file.fileName} (status $status); trying the next" }
            tried += "$program (status $status)"
        }
        log.warn("The startup sound was not played: ${tried.joinToString().ifEmpty { "no player" }}")
        return null
    }

    // A command written as one line: its words.
    fun words(line: String): List<String> = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    // A player's command: its words, {file} and the volume put in ({file} last if not written).
    fun commandOf(template: List<String>, file: Path, volume: Double): List<String> {
        val words = if (template.any { "{file}" in it }) template else template + "{file}"
        return words.map { word ->
            word.replace("{file}", file.toString())
                .replace("{volume}", "%.2f".format(java.util.Locale.ROOT, volume))
                .replace("{paVolume}", (volume * 65536).toInt().toString())
                .replace("{percent}", (volume * 100).toInt().toString())
        }
    }
}
