package dev.caulis.wm

import java.nio.file.Path
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
