package dev.caulis.core.input

import dev.caulis.geometry.Rect

/**
 * A drawing tablet: the surface a pen is used on (a Wacom Intuos, a laptop's pen screen).
 * Its pad — the buttons and rings beside it — is a [TabletPad] of its own.
 *
 * [widthMm] and [heightMm] are its drawing area (0 when unknown); [screen] is true for a
 * tablet that is a screen (one draws on the picture itself). [path] is its device file
 * ("/dev/input/event7"), or null. Make one yourself for input of your own (tests, a remote
 * tablet); the real ones come from [dev.caulis.core.libinput.InputDevices].
 */
class Tablet @JvmOverloads constructor(
    val name: String,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val path: String? = null,
    val widthMm: Double = 0.0,
    val heightMm: Double = 0.0,
    val screen: Boolean = false,
) {
    /**
     * How it behaves: [DeviceSettings.monitor], [DeviceSettings.area], [DeviceSettings.leftHanded],
     * the pen's buttons and the rest of the settings marked "Tablets". Set by whoever reads
     * the settings ([dev.caulis.core.libinput.InputDevices] does, from [InputSettings.forTablet]).
     */
    var settings: DeviceSettings = DeviceSettings()

    /**
     * Where on the desktop the point ([x], [y]) of the tablet is — each 0..1 across its whole
     * drawing area, as libinput gives it — when the tablet covers [target] (desktop units).
     * The settings say how: turned around for the left hand, only a part of it used
     * ([DeviceSettings.area]), and the same shape as [target] ([DeviceSettings.keepAspect]: then a
     * strip of the tablet is left unused, from its right or bottom edge, rather than a circle
     * drawn on it coming out as an oval).
     */
    fun place(x: Double, y: Double, target: Rect): Pair<Double, Double> {
        val s = settings
        // Turned around: its top left corner is the bottom right one.
        var u = if (s.leftHanded == true) 1.0 - x else x
        var v = if (s.leftHanded == true) 1.0 - y else y
        val area = s.area ?: TabletArea.WHOLE
        var width = area.width
        var height = area.height
        if (s.keepAspect != false && widthMm > 0 && heightMm > 0 && target.width > 0 && target.height > 0) {
            // The part used, in millimetres, cut to the target's shape.
            val usedRatio = (width * widthMm) / (height * heightMm)
            val targetRatio = target.width.toDouble() / target.height
            if (usedRatio > targetRatio) width *= targetRatio / usedRatio else height *= usedRatio / targetRatio
        }
        u = ((u - area.x) / width).coerceIn(0.0, 1.0)
        v = ((v - area.y) / height).coerceIn(0.0, 1.0)
        // (Its far edges are the target's last unit, not the first one past it: another monitor may be there.)
        return (target.x + u * target.width).coerceAtMost(target.right - 1.0) to (target.y + v * target.height).coerceAtMost(target.bottom - 1.0)
    }

    override fun toString() = name
}

/**
 * The part of a tablet that is used, each value 0..1 of its whole drawing area as the user
 * sees it (turned around when [DeviceSettings.leftHanded]): `TabletArea(0.0, 0.0, 0.5, 0.5)`
 * is its top left quarter. Pen positions outside it go to the nearest edge.
 */
data class TabletArea(val x: Double, val y: Double, val width: Double, val height: Double) {
    init {
        require(x in 0.0..1.0 && y in 0.0..1.0) { "a tablet area starts at 0..1 of the tablet, not $x, $y" }
        require(width > 0.0 && height > 0.0 && x + width <= 1.0 + 1e-9 && y + height <= 1.0 + 1e-9) {
            "a tablet area is a part of the tablet (x + width and y + height at most 1), not $width × $height at $x, $y"
        }
    }

    companion object {
        /** The whole tablet. */
        @JvmField
        val WHOLE = TabletArea(0.0, 0.0, 1.0, 1.0)
    }
}

/**
 * A tool used on a tablet: a pen, its eraser end, an airbrush, a tablet mouse, ...
 * [serial] tells two pens of the same kind apart (0: it cannot), [hardwareId] is the
 * maker's id for the kind of tool (0: unknown). Tools are compared by identity: use the
 * same object for the same physical tool.
 */
class TabletTool @JvmOverloads constructor(
    val type: Type,
    val capabilities: Set<Capability> = emptySet(),
    val serial: Long = 0,
    val hardwareId: Long = 0,
) {
    enum class Type { PEN, ERASER, BRUSH, PENCIL, AIRBRUSH, MOUSE, LENS }

    /** What the tool tells besides its place. */
    enum class Capability { PRESSURE, DISTANCE, TILT, ROTATION, SLIDER, WHEEL }

    override fun toString() = type.name.lowercase()
}

/**
 * What a tool tells besides its place, each null when it did not change (or the tool cannot
 * tell it): [pressure] 0..1, [distance] above the tablet 0..1, [tiltX] and [tiltY] in degrees
 * from upright (positive: towards the right and towards the user), [rotation] in degrees
 * (clockwise), [slider] -1..1 (an airbrush's finger wheel), and a turn of a tablet mouse's
 * [wheelDegrees] in [wheelClicks].
 */
data class TabletAxes @JvmOverloads constructor(
    val pressure: Double? = null,
    val distance: Double? = null,
    val tiltX: Double? = null,
    val tiltY: Double? = null,
    val rotation: Double? = null,
    val slider: Double? = null,
    val wheelDegrees: Double = 0.0,
    val wheelClicks: Int = 0,
) {
    /** The same axes as a left-handed (turned around) tablet tells them: tilt the other way, rotation half a turn on. */
    fun turnedAround(): TabletAxes = copy(tiltX = tiltX?.let { -it }, tiltY = tiltY?.let { -it }, rotation = rotation?.let { (it + 180.0) % 360.0 })

    companion object {
        /** Nothing changed. */
        @JvmField
        val NONE = TabletAxes()
    }
}

/**
 * A tablet's pad: buttons (numbered from 0), rings and strips beside the drawing area, in
 * [groups] that each have modes (a button switches what the others do). Programs get its
 * events only when its [tablet] is known (the protocol names it).
 */
class TabletPad @JvmOverloads constructor(
    val name: String,
    val buttons: Int,
    val rings: Int = 0,
    val strips: Int = 0,
    val groups: List<Group> = listOf(Group((0 until buttons).toList(), (0 until rings).toList(), (0 until strips).toList(), 1)),
    tablet: Tablet? = null,
    val path: String? = null,
) {
    /** The tablet it belongs to, if known (a tablet's pen and pad may come in either order). */
    var tablet: Tablet? = tablet

    /** Buttons, rings and strips (by their numbers) that switch modes together; [modes] is how many (at least 1). */
    class Group(val buttons: List<Int>, val rings: List<Int>, val strips: List<Int>, val modes: Int)

    override fun toString() = name
}

/**
 * Where a pen that is the pointer goes (see [dev.caulis.core.Desktop.penPointer]): the pointer
 * moved to ([x], [y]) in desktop units, a mouse [button] (a Linux code, 0x110 left) pressed or let go.
 */
interface PenPointer {
    fun moved(timeMillis: Int, x: Double, y: Double)
    fun button(timeMillis: Int, button: Int, pressed: Boolean)
}
