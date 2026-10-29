package dev.caulis.wm

import dev.caulis.log.Logger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A sound when the session starts — off unless you turn it on:
 *
 * ```
 * startupSound {
 *     enabled = true
 *     preset = "airy"             // or "classic", "glass", "soft" (made by Caulis itself)
 *     file = "~/sounds/hello.ogg" // or a file of yours instead (ogg, wav, flac: what the player plays)
 *     volume = 0.8
 * }
 * ```
 *
 * It is played by a player program ([player], else the first of [players] that works: pw-play,
 * paplay, aplay), never on the compositor's thread, when the desktop fades in ([at]). The
 * presets are made once and kept in `~/.cache/<configName>/sounds/`. The action
 * `play-startup-sound` plays it again (to try; `play-startup-sound glass` another preset).
 */
class StartupSound : StartupSettings() {

    /** Played at all (off by default). */
    var enabled = false

    /** One of the sounds Caulis makes itself: [StartupChimes.PRESETS] ("airy", "classic", "glass", "soft"). */
    var preset = "airy"
        set(value) {
            require(value in StartupChimes.PRESETS) { "startupSound: there is no preset \"$value\" (there are ${StartupChimes.PRESETS.joinToString()})" }
            field = value
        }

    /** A sound file of yours instead of the preset (`~` is your home; relative: next to the configuration). */
    var file: String? = null

    /** How loud, 0 to 1 (where the player can say: aplay plays it as it is). */
    var volume = 0.8
        set(value) {
            require(value in 0.0..1.0) { "startupSound: volume is 0 to 1, not $value" }
            field = value
        }

    /** Waits this long more before it plays, in milliseconds. */
    var delayMillis = 0L
        set(value) {
            require(value in 0..60_000) { "startupSound: delay is 0 to 60 s, not $value ms" }
            field = value
        }

    /** [delayMillis] as a Duration: `delay = 300.milliseconds`. */
    @get:JvmSynthetic
    @set:JvmSynthetic
    var delay: Duration
        get() = delayMillis.toDuration(DurationUnit.MILLISECONDS)
        set(value) {
            delayMillis = value.inWholeMilliseconds
        }

    /** When it plays: as the desktop fades in (the default), or right at the start. */
    var at = SoundMoment.REVEAL

    /**
     * Played in the headless backend too (off by default: nobody listens there — tests,
     * virtual monitors). The action `play-startup-sound` plays it anywhere.
     */
    var whenHeadless = false

    /**
     * The player of your choice, its words: `player = listOf("mpv", "--no-video", "{file}")`
     * (`{file}` the sound's file — put last when not written —, `{volume}` 0 to 1,
     * `{paVolume}` 0 to 65536 as paplay has it, `{percent}` 0 to 100). Null: [players].
     */
    var player: List<String>? = null

    /**
     * The players tried one after another until one plays it (when [player] is not set): a
     * command each, with the same `{file}`, `{volume}`, `{paVolume}`, `{percent}`.
     */
    val players: MutableList<String> = mutableListOf(
        "pw-play --volume={volume} {file}",
        "paplay --volume={paVolume} {file}",
        "aplay -q {file}",
    )

    /**
     * Your own way to play it instead of [players] (on a thread of its own: it may take its
     * time): gets the file and the volume, true if it played.
     */
    var play: ((file: Path, volume: Double) -> Boolean)? = null

    /** Where the presets are written; null: `~/.cache/<configName>/sounds`. */
    var cacheFolder: Path? = null

    /**
     * How long a player may play, in milliseconds: then it is stopped (a long sound file is
     * cut off there). It counts as played — the next player does not start it over.
     */
    var playerTimeoutMillis = 30_000L
        set(value) {
            require(value in 1_000..600_000) { "startupSound: playerTimeout is 1 to 600 s, not $value ms" }
            field = value
        }

    // Java's chained setters.
    fun enabled(on: Boolean): StartupSound = apply { enabled = on }
    fun preset(name: String): StartupSound = apply { preset = name }
    fun file(path: String?): StartupSound = apply { file = path }
    fun volume(volume: Double): StartupSound = apply { this.volume = volume }
    fun delayMillis(millis: Long): StartupSound = apply { delayMillis = millis }
    fun at(moment: SoundMoment): StartupSound = apply { at = moment }
    fun player(command: List<String>?): StartupSound = apply { player = command }
    fun play(play: java.util.function.BiPredicate<Path, Double>): StartupSound = apply { this.play = { file, volume -> play.test(file, volume) } }

    // A copy (of a configuration).
    internal fun copy(): StartupSound = StartupSound().also { copyInto(it) }

    // Its settings into [c].
    internal fun copyInto(c: StartupSound) {
        c.enabled = enabled
        c.preset = preset
        c.file = file
        c.volume = volume
        c.delayMillis = delayMillis
        c.at = at
        c.whenHeadless = whenHeadless
        c.player = player
        c.players.clear()
        c.players += players
        c.play = play
        c.playerTimeoutMillis = playerTimeoutMillis
        c.cacheFolder = cacheFolder
    }
}

/** When [StartupSound] plays. */
enum class SoundMoment {
    /** When the desktop fades in (see [Reveal]). */
    REVEAL,

    /** Right at the start, while the screen is still dark. */
    START,
}

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

/**
 * The startup sounds Caulis makes itself: a small additive synthesizer (soft pads of a few
 * detuned partials, glassy bells, a shimmer, a hall around it), written as a WAV file —
 * the same bytes every time. Every preset's notes are our own; none is a recording.
 */
object StartupChimes {

    /** The presets by name. */
    @JvmField
    val PRESETS: List<String> = listOf("airy", "classic", "glass", "soft")

    /** Samples a second. */
    const val RATE = 48_000

    // Changes whenever the sound of a preset changes (so a file made before is made anew).
    private const val VERSION = 1

    private val log = Logger("sound")

    /**
     * The file of [preset] in [folder], made if it is not there yet: named after the preset and
     * what it is made of (`airy-v1-3fa2c1d0.wav`), so a changed recipe makes a new file.
     */
    @JvmStatic
    fun file(preset: String, folder: Path): Path {
        val key = Integer.toHexString(recipeOf(preset).hashCode() * 31 + RATE)
        val target = folder.resolve("$preset-v$VERSION-$key.wav")
        if (Files.isRegularFile(target) && Files.size(target) > 44) return target
        Files.createDirectories(folder)
        val started = System.nanoTime()
        val bytes = wav(preset)
        // Written beside it, then moved: never half a file under its name.
        val temp = Files.createTempFile(folder, ".$preset", ".wav")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
        log.info { "Made the sound $preset in ${(System.nanoTime() - started) / 1_000_000} ms: $target" }
        return target
    }

    /** [preset] as a WAV file's bytes (16-bit stereo, [RATE] Hz). */
    @JvmStatic
    fun wav(preset: String): ByteArray {
        val (left, right) = render(recipeOf(preset))
        return wavBytes(left, right)
    }

    /** One note of a part: its pitch in Hz, when it starts (s), how loud, and where (0 left, 1 right). */
    internal data class Note(val hz: Double, val start: Double, val gain: Double, val pan: Double = 0.5)

    /** What a preset is made of. */
    internal data class Recipe(
        val seconds: Double,
        val pads: List<Note> = emptyList(),
        val padAttack: Double = 0.9,
        val padHold: Double = 1.6,
        val padRelease: Double = 1.6,
        val padBrightness: Double = 2600.0,
        val bells: List<Note> = emptyList(),
        val bellDecay: Double = 1.4,
        val shimmer: List<Note> = emptyList(),
        val reverbMix: Double = 0.4,
        val roomSize: Double = 0.84,
    )

    // Note names as pitches (A4 = 440 Hz): "C#5" → 554.37.
    private fun n(name: String): Double {
        val letter = "C D EF G A B".indexOf(name[0])
        val sharp = when (name.getOrNull(1)) { '#' -> 1; 'b' -> -1; else -> 0 }
        val octave = name.substring(if (sharp != 0) 2 else 1).toInt()
        val midi = 12 * (octave + 1) + letter + sharp
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    internal fun recipeOf(preset: String): Recipe = when (preset) {
        // Bright and open: a D major 9 pad swelling up, bells rising over it, a high shimmer.
        "airy" -> Recipe(
            seconds = 4.6,
            pads = listOf(
                Note(n("D2"), 0.00, 0.10), Note(n("A2"), 0.10, 0.15), Note(n("F#3"), 0.22, 0.20),
                Note(n("E4"), 0.34, 0.17), Note(n("A4"), 0.46, 0.13),
            ),
            bells = listOf("A4", "D5", "E5", "F#5", "A5", "C#6", "E6").mapIndexed { i, name ->
                Note(n(name), 0.5 + i * 0.15, 0.19 - i * 0.012, if (i % 2 == 0) 0.3 else 0.7)
            },
            shimmer = listOf(Note(n("F#7"), 0.8, 0.022), Note(n("A7"), 0.9, 0.018), Note(n("D8"), 1.0, 0.014)),
        )
        // A mood in the spirit of mid-90s startup sounds — warm, airy, a pad chord that rises,
        // a shimmering tail — written from scratch: an original chord progression (Ebmaj7
        // rising to Fadd9, then a high Bb) and an original bell run. It is not Brian Eno's
        // "The Microsoft Sound" (1995) nor any other: not its notes, melody or recording.
        "classic" -> Recipe(
            seconds = 5.0,
            pads = listOf(
                Note(n("Eb2"), 0.00, 0.11), Note(n("Bb2"), 0.08, 0.14), Note(n("G3"), 0.16, 0.18), Note(n("D4"), 0.24, 0.14),
                Note(n("F2"), 1.10, 0.10), Note(n("C3"), 1.16, 0.13), Note(n("A3"), 1.22, 0.17), Note(n("G4"), 1.30, 0.13),
                Note(n("Bb4"), 1.80, 0.10),
            ),
            padAttack = 1.0, padHold = 1.4, padRelease = 1.9, padBrightness = 2300.0,
            bells = listOf("C5", "F5", "G5", "A5", "C6", "F6").mapIndexed { i, name ->
                Note(n(name), 1.25 + i * 0.13, 0.15 - i * 0.012, 0.25 + 0.1 * i)
            },
            bellDecay = 1.8,
            shimmer = listOf(Note(n("C8"), 1.6, 0.02), Note(n("F7"), 1.7, 0.02), Note(n("A7"), 1.8, 0.016)),
            reverbMix = 0.48, roomSize = 0.88,
        )
        // Crisp: a few glass bells falling into place over a faint pad.
        "glass" -> Recipe(
            seconds = 3.4,
            pads = listOf(Note(n("E3"), 0.0, 0.06), Note(n("B3"), 0.05, 0.06), Note(n("G#4"), 0.1, 0.05)),
            padAttack = 0.5, padHold = 0.8, padRelease = 1.0,
            bells = listOf("B5", "E6", "G#5", "C#6", "E5").mapIndexed { i, name ->
                Note(n(name), 0.05 + i * 0.11, 0.2 - i * 0.02, if (i % 2 == 0) 0.35 else 0.65)
            },
            bellDecay = 1.1, reverbMix = 0.35, roomSize = 0.8,
        )
        // Quiet: one warm chord breathing in and out.
        "soft" -> Recipe(
            seconds = 3.8,
            pads = listOf(Note(n("C3"), 0.0, 0.14), Note(n("G3"), 0.1, 0.16), Note(n("E4"), 0.2, 0.15), Note(n("B4"), 0.3, 0.10)),
            padAttack = 1.1, padHold = 0.9, padRelease = 1.2, padBrightness = 1800.0,
            reverbMix = 0.45,
        )
        else -> throw IllegalArgumentException("there is no preset \"$preset\" (there are ${PRESETS.joinToString()})")
    }

    // A sine from a table (fast, and the same everywhere).
    private const val TABLE = 1 shl 13
    private val SINE = DoubleArray(TABLE + 1) { sin(2 * PI * it / TABLE) }

    private fun sine(cycles: Double): Double {
        val at = (cycles - Math.floor(cycles)) * TABLE
        val i = at.toInt()
        return SINE[i] + (SINE[i + 1] - SINE[i]) * (at - i)
    }

    /** The two channels of [recipe], peaking at 0.7 (never clipping). */
    internal fun render(recipe: Recipe): Pair<DoubleArray, DoubleArray> {
        val length = (recipe.seconds * RATE).toInt()
        val random = java.util.Random(95)
        val left = DoubleArray(length)
        val right = DoubleArray(length)

        // Pads: three gently detuned voices of four partials each; they swell, stay, fade.
        val pad = DoubleArray(length)
        for (note in recipe.pads) {
            val phases = DoubleArray(3) { random.nextDouble() }
            for (i in (note.start * RATE).toInt() until length) {
                val x = i.toDouble() / RATE - note.start
                val env = envelope(x, recipe.padAttack, recipe.padHold, recipe.padRelease)
                if (env < 1e-5 && x > recipe.padAttack) break
                var v = 0.0
                for ((d, detune) in DETUNES.withIndex()) {
                    val c = note.hz * (1 + detune) * x + phases[d]
                    v += sine(c) + 0.32 * sine(2 * c) + 0.12 * sine(3 * c) + 0.05 * sine(4 * c)
                }
                pad[i] += note.gain * env * v
            }
        }
        lowpass(pad, recipe.padBrightness)

        // Bells: a sine bent by another at 3.5 times its pitch (FM), the bend dying fast.
        val bellsL = DoubleArray(length)
        val bellsR = DoubleArray(length)
        for (note in recipe.bells) {
            for (i in (note.start * RATE).toInt() until length) {
                val x = i.toDouble() / RATE - note.start
                val env = (1 - exp(-x / 0.004)) * exp(-x / recipe.bellDecay)
                if (env < 1e-5) break
                val index = 2.2 * exp(-x / 0.35)
                val modulator = sine(note.hz * 3.5 * x)
                val v = note.gain * env * sine(note.hz * x + index * modulator / (2 * PI))
                bellsL[i] += v * (1 - note.pan)
                bellsR[i] += v * note.pan
            }
        }

        // Shimmer: very high, quiet sines trembling slowly.
        val shimmer = DoubleArray(length)
        for ((k, note) in recipe.shimmer.withIndex()) {
            for (i in (note.start * RATE).toInt() until length) {
                val x = i.toDouble() / RATE - note.start
                val env = envelope(x, 1.4, 1.2, 1.0)
                shimmer[i] += note.gain * env * sine(note.hz * x) * (0.6 + 0.4 * sine(3.1 * x + k / (2 * PI)))
            }
        }

        for (i in 0 until length) {
            left[i] = pad[i] + 1.1 * bellsL[i] + shimmer[i]
            right[i] = 0.98 * pad[i] + 1.1 * bellsR[i] + shimmer[i]
        }
        val wetL = Hall(recipe.roomSize, 0).process(left)
        val wetR = Hall(recipe.roomSize, 23).process(right)
        val mix = recipe.reverbMix
        // The end fades out (1.2 s), and the whole is set to its loudness.
        val fadeFrom = length - (1.2 * RATE).toInt()
        var peak = 1e-9
        for (i in 0 until length) {
            val fade = if (i < fadeFrom) 1.0 else ((length - i).toDouble() / (length - fadeFrom)).pow(1.5)
            left[i] = ((1 - mix) * left[i] + mix * wetL[i]) * fade
            right[i] = ((1 - mix) * right[i] + mix * wetR[i]) * fade
            peak = maxOf(peak, abs(left[i]), abs(right[i]))
        }
        val scale = PEAK / peak
        for (i in 0 until length) {
            left[i] *= scale
            right[i] *= scale
        }
        return left to right
    }

    private val DETUNES = doubleArrayOf(-0.0035, 0.0, 0.0042)
    private const val PEAK = 0.7

    // Rises over [attack] s (softly), stays [hold] s, then dies away ([release] s to a third).
    private fun envelope(x: Double, attack: Double, hold: Double, release: Double): Double {
        if (x < 0) return 0.0
        val rise = if (x < attack) (x / attack).pow(1.6) else 1.0
        return rise * exp(-(x - attack - hold).coerceAtLeast(0.0) / release)
    }

    // Two one-pole low-pass filters at [cutoff] Hz (12 dB an octave): softer, warmer.
    private fun lowpass(x: DoubleArray, cutoff: Double) {
        val a = exp(-2 * PI * cutoff / RATE)
        repeat(2) {
            var y = 0.0
            for (i in x.indices) {
                y = (1 - a) * x[i] + a * y
                x[i] = y
            }
        }
    }

    // A hall: eight damped comb filters side by side, then four all-pass filters in a row
    // (as Freeverb does); [spread] makes the other channel's a little different.
    private class Hall(private val room: Double, private val spread: Int) {
        fun process(input: DoubleArray): DoubleArray {
            val scale = RATE / 44_100.0
            val out = DoubleArray(input.size)
            for (length in COMBS) {
                val buffer = DoubleArray(((length + spread) * scale).toInt())
                var at = 0
                var low = 0.0
                for (i in input.indices) {
                    val y = buffer[at]
                    low = y * (1 - DAMP) + low * DAMP
                    buffer[at] = input[i] * 0.015 + low * room
                    out[i] += y
                    at = (at + 1) % buffer.size
                }
            }
            for (length in ALLPASSES) {
                val buffer = DoubleArray(((length + spread) * scale).toInt())
                var at = 0
                for (i in out.indices) {
                    val delayed = buffer[at]
                    val y = -out[i] + delayed
                    buffer[at] = out[i] + delayed * 0.5
                    out[i] = y
                    at = (at + 1) % buffer.size
                }
            }
            return out
        }

        companion object {
            val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            val ALLPASSES = intArrayOf(556, 441, 341, 225)
            const val DAMP = 0.3
        }
    }

    /** [left] and [right] (-1 to 1) as a 16-bit stereo WAV file. */
    internal fun wavBytes(left: DoubleArray, right: DoubleArray): ByteArray {
        val frames = left.size
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            data.putShort((left[i].coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
            data.putShort((right[i].coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(RATE).putInt(RATE * 4).putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(frames * 4)
        return ByteArrayOutputStream(44 + frames * 4).apply {
            write(header.array())
            write(data.array())
        }.toByteArray()
    }
}
