package dev.caulis.wm

/**
 * What a laptop's keys do ([Keys.volumeUp], [Keys.brightnessUp], the media keys and the
 * actions `volume`, `brightness`, `media`): the steps, the limits, and the programs run for
 * them. Change them with `systemKeys { volumeStep = 2; maxVolume = 1.5 }`; every change applies
 * at the next key press.
 */
class SystemKeys {
    /** How much one press of the volume keys changes the volume, in percent. */
    var volumeStep = 5
        set(value) {
            require(value in 1..100) { "volumeStep is 1 to 100, not $value" }
            field = value
        }

    /** The loudest the volume keys go (1.0 = 100 %; more amplifies, as wpctl's -l). */
    var maxVolume = 1.0
        set(value) {
            require(value in 0.1..4.0) { "maxVolume is 0.1 to 4, not $value" }
            field = value
        }

    /** Changing the volume switches the sound on again if it was muted. */
    var unmuteOnVolumeChange = true

    /** How much one press of the brightness keys changes the brightness, in percent. */
    var brightnessStep = 5
        set(value) {
            require(value in 1..100) { "brightnessStep is 1 to 100, not $value" }
            field = value
        }

    /** The darkest the brightness keys go, in percent (above 0: a black screen looks broken). */
    var minBrightnessPercent = 1
        set(value) {
            require(value in 0..100) { "minBrightnessPercent is 0 to 100, not $value" }
            field = value
        }

    /** The backlight the brightness keys change, by its name in /sys/class/backlight; null: the first there. */
    var backlight: String? = null

    /** The program for the volume and the microphone (PipeWire's wpctl, or one that takes the same words). */
    var wpctl = "wpctl"

    /** The program for the brightness when logind cannot set it (brightnessctl). */
    var brightnessctl = "brightnessctl"

    /** The program for music players (playerctl). */
    var playerctl = "playerctl"

    /** How long one of these programs may take before it is stopped, in milliseconds. */
    var timeoutMillis = 3000L
        set(value) {
            require(value in 100..60_000) { "timeoutMillis is 100 to 60000, not $value" }
            field = value
        }

    // A copy (of a configuration).
    internal fun copy() = SystemKeys().also { c ->
        c.volumeStep = volumeStep
        c.maxVolume = maxVolume
        c.unmuteOnVolumeChange = unmuteOnVolumeChange
        c.brightnessStep = brightnessStep
        c.minBrightnessPercent = minBrightnessPercent
        c.backlight = backlight
        c.wpctl = wpctl
        c.brightnessctl = brightnessctl
        c.playerctl = playerctl
        c.timeoutMillis = timeoutMillis
    }
}

/** Where the output of the programs a compositor starts goes (see [Caulis.programOutput]). */
enum class ProgramOutput {
    /** Nowhere (the default). */
    DISCARD,

    /** Each line to the log (area "clients", level 2), with the program's name. */
    LOG,

    /** Appended to [Caulis.programOutputFile]. */
    FILE,
}

/** What [Caulis.onScreenDisplay] hands what the laptop's keys changed to. */
fun interface ScreenDisplayHandler {
    /** Shows [title] ("Volume 45 %") with [level] (0..1 or null), [off] when muted; true: shown (Caulis's own is not). */
    fun show(title: String, level: Double?, off: Boolean): Boolean
}

/** For Java: [Caulis.onPointerMotion]. */
fun interface PointerMotionHandler {
    /** The pointer moved by [dx], [dy]; true takes the movement. */
    fun moved(caulis: Caulis, dx: Double, dy: Double): Boolean
}

/** For Java: [Caulis.onPointerButton]. */
fun interface PointerButtonHandler {
    /** A mouse [button] pressed or let go; true takes it. */
    fun button(caulis: Caulis, button: Int, pressed: Boolean): Boolean
}

/** For Java: [Caulis.onScroll]. */
fun interface ScrollHandler {
    /** The wheel or two-finger scrolling; true takes it. */
    fun scrolled(caulis: Caulis, vertical: Boolean, value: Double, steps: Double): Boolean
}

/** For Java: an action of your own ([Caulis.action]). */
fun interface ActionHandler {
    /** Runs the action with [argument] (the text after its name) on [window] (null: the focused one). */
    fun run(caulis: Caulis, argument: String, window: dev.caulis.core.Window?)
}
