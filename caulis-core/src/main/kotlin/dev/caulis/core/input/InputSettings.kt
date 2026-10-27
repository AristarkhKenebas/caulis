package dev.caulis.core.input

/**
 * How a mouse, trackball, touchpad or drawing tablet behaves. Every setting is null by
 * default: then the device (and libinput) choose — except tapping on touchpads, which Caulis
 * turns on, and the tablet settings, whose defaults each one says.
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

    /** Buttons swapped for the left hand; a tablet is turned around (half a turn: its buttons on the right). */
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

    /**
     * Tablets: the monitor (by name, e.g. "DP-1") the tablet covers. Null: a tablet that is a
     * screen (a laptop's pen screen) covers the laptop's own monitor (else the first one), any
     * other tablet all monitors as one.
     */
    var monitor: String? = null

    /** Tablets: keep the shape of the monitor (default true): a circle drawn is a circle, a strip of the tablet stays unused. */
    var keepAspect: Boolean? = null

    /** Tablets: only this part of the tablet is used (null: all of it). */
    var area: TabletArea? = null

    /** Tablets: the pen is only ever the pointer (default false: programs that understand tablets get pressure, tilt and the rest). */
    var pointerOnly: Boolean? = null

    /**
     * Tablets: how hard the pen must press (0.01 to 1, of its full pressure) to touch — a click
     * for the pointer, a stroke for drawing programs. Null: as libinput decides (a light touch).
     */
    var tipPressure: Double? = null
        set(value) {
            require(value == null || value in 0.01..1.0) { "tipPressure is 0.01 to 1.0, not $value" }
            field = value
        }

    /** Tablets: the mouse button (a Linux code, 0: none) the pen's lower button is for programs that see the pen as the pointer (default 0x111, right). */
    var lowerButton: Int? = null

    /** Tablets: the same for the pen's upper button (default 0x112, middle). */
    var upperButton: Int? = null

    /** Tablets: the same for a third button on the pen (default 0x113, side). */
    var thirdButton: Int? = null

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
        m.monitor = monitor ?: under.monitor
        m.keepAspect = keepAspect ?: under.keepAspect
        m.area = area ?: under.area
        m.pointerOnly = pointerOnly ?: under.pointerOnly
        m.tipPressure = tipPressure ?: under.tipPressure
        m.lowerButton = lowerButton ?: under.lowerButton
        m.upperButton = upperButton ?: under.upperButton
        m.thirdButton = thirdButton ?: under.thirdButton
    }

    /**
     * Tablets: the mouse button a pen's [button] (a Linux code: 0x14b lower, 0x14c upper, 0x149
     * third) is when the pen is the pointer; other buttons (a tablet mouse's) stay what they are.
     * 0: none.
     */
    fun pointerButtonOf(button: Int): Int = when (button) {
        BTN_STYLUS -> lowerButton ?: BTN_RIGHT
        BTN_STYLUS2 -> upperButton ?: BTN_MIDDLE
        BTN_STYLUS3 -> thirdButton ?: BTN_SIDE
        else -> button
    }

    private companion object {
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
        const val BTN_SIDE = 0x113
        const val BTN_STYLUS = 0x14b
        const val BTN_STYLUS2 = 0x14c
        const val BTN_STYLUS3 = 0x149
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
 * `*` matches anything: "Logitech*"). Drawing tablets have [tablets] of their own (a
 * left-handed mouse does not turn the tablet around), with a device's own on top.
 */
class InputSettings {
    /** Every mouse, trackball and touchpad. */
    val pointers = DeviceSettings()

    /** Touchpads (on top of [pointers]). */
    val touchpads = DeviceSettings()

    /** Drawing tablets and their pens (not on top of [pointers]). */
    val tablets = DeviceSettings()

    /** Single devices by name (on top of the others). */
    val devices = LinkedHashMap<String, DeviceSettings>()

    /** The settings for the device called [name]. */
    fun forDevice(name: String, touchpad: Boolean): DeviceSettings {
        var settings = if (touchpad) touchpads.over(pointers) else pointers
        for ((pattern, own) in devices) if (matches(pattern, name)) settings = own.over(settings)
        return settings
    }

    /** The settings for the tablet called [name]. */
    fun forTablet(name: String): DeviceSettings {
        var settings = tablets.over(DeviceSettings())
        for ((pattern, own) in devices) if (matches(pattern, name)) settings = own.over(settings)
        return settings
    }

    companion object {
        /** True if [name] is [pattern] (case does not matter; `*` is anything). */
        fun matches(pattern: String, name: String): Boolean =
            Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE).matches(name)
    }
}
