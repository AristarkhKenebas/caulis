package dev.caulis.core.input

/**
 * How a mouse, trackball or touchpad behaves. Every setting is null by default: then the
 * device (and libinput) choose — except tapping on touchpads, which Caulis turns on.
 */
class DeviceSettings {
    /** false: the device is not used at all. */
    var enabled: Boolean? = null

    /** Touchpads: off while a mouse is plugged in. */
    var offWithMouse: Boolean? = null

    /** How fast the pointer goes, -1.0 (slowest) to 1.0 (fastest); 0.0 is the device's normal speed. */
    var speed: Double? = null
        set(value) {
            require(value == null || value in -1.0..1.0) { "speed is -1.0 to 1.0, not $value" }
            field = value
        }

    /** Acceleration: [AccelProfile.FLAT] (as fast as the hand) or [AccelProfile.ADAPTIVE] (faster when moved fast). */
    var accelProfile: AccelProfile? = null

    /** Content follows the fingers (as on phones): scrolling down moves the page up. */
    var naturalScroll: Boolean? = null

    /** Scrolling goes this many times as far (0.1 to 10). */
    var scrollFactor: Double? = null
        set(value) {
            require(value == null || value in 0.1..10.0) { "scrollFactor is 0.1 to 10, not $value" }
            field = value
        }

    /** How a touchpad (or a mouse with a button) scrolls. */
    var scrollMethod: ScrollMethod? = null

    /** With [ScrollMethod.BUTTON]: the button held to scroll by moving (a Linux button code, e.g. 0x112 middle). */
    var scrollButton: Int? = null

    /** Buttons swapped for the left hand. */
    var leftHanded: Boolean? = null

    /** Left and right pressed together are a middle click. */
    var middleEmulation: Boolean? = null

    /** Touchpads: a tap is a click. */
    var tap: Boolean? = null

    /** Touchpads: which click two and three fingers tap. */
    var tapButtons: TapButtons? = null

    /** Touchpads: tap, then touch again and move to drag. */
    var tapDrag: Boolean? = null

    /** Touchpads: a drag goes on when the finger lifts for a moment. */
    var dragLock: Boolean? = null

    /** Touchpads: off while typing. */
    var disableWhileTyping: Boolean? = null

    /** Touchpads: what a click is — the area pressed, or the number of fingers. */
    var clickMethod: ClickMethod? = null

    /** These settings, with those of [under] where these say nothing. */
    fun over(under: DeviceSettings): DeviceSettings = DeviceSettings().also { m ->
        m.enabled = enabled ?: under.enabled
        m.offWithMouse = offWithMouse ?: under.offWithMouse
        m.speed = speed ?: under.speed
        m.accelProfile = accelProfile ?: under.accelProfile
        m.naturalScroll = naturalScroll ?: under.naturalScroll
        m.scrollFactor = scrollFactor ?: under.scrollFactor
        m.scrollMethod = scrollMethod ?: under.scrollMethod
        m.scrollButton = scrollButton ?: under.scrollButton
        m.leftHanded = leftHanded ?: under.leftHanded
        m.middleEmulation = middleEmulation ?: under.middleEmulation
        m.tap = tap ?: under.tap
        m.tapButtons = tapButtons ?: under.tapButtons
        m.tapDrag = tapDrag ?: under.tapDrag
        m.dragLock = dragLock ?: under.dragLock
        m.disableWhileTyping = disableWhileTyping ?: under.disableWhileTyping
        m.clickMethod = clickMethod ?: under.clickMethod
    }
}

enum class AccelProfile { FLAT, ADAPTIVE }

enum class ScrollMethod { NONE, TWO_FINGER, EDGE, BUTTON }

/** Two fingers / three fingers: right / middle click, or middle / right. */
enum class TapButtons { LEFT_RIGHT_MIDDLE, LEFT_MIDDLE_RIGHT }

/** A touchpad click: by the area pressed (bottom right is right), or by the number of fingers (two are right). */
enum class ClickMethod { AREAS, FINGERS }

/**
 * Settings of all pointing devices: those of every pointer, on top of them those of
 * touchpads, on top of those a device's own (by its name, as `--devices` lists them;
 * `*` matches anything: "Logitech*").
 */
class InputSettings {
    /** Every mouse, trackball and touchpad. */
    val pointers = DeviceSettings()

    /** Touchpads (on top of [pointers]). */
    val touchpads = DeviceSettings()

    /** Single devices by name (on top of the others). */
    val devices = LinkedHashMap<String, DeviceSettings>()

    /** The settings for the device called [name]. */
    fun forDevice(name: String, touchpad: Boolean): DeviceSettings {
        var settings = if (touchpad) touchpads.over(pointers) else pointers
        for ((pattern, own) in devices) if (matches(pattern, name)) settings = own.over(settings)
        return settings
    }

    companion object {
        /** True if [name] is [pattern] (case does not matter; `*` is anything). */
        fun matches(pattern: String, name: String): Boolean =
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE).matches(name)
    }
}
