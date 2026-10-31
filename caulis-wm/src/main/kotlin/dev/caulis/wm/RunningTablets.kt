package dev.caulis.wm

import dev.caulis.core.input.KeyBindings
import dev.caulis.core.input.Tablet
import dev.caulis.core.input.TabletAxes
import dev.caulis.core.input.TabletPad
import dev.caulis.core.input.TabletTool

/**
 * Drawing tablets in a running compositor: their events, which monitor they map to, pads.
 */
internal class RunningTablets(private val running: Running) {

    // The tablets, pens and pads of [devices]: places on the desktop as each tablet's settings
    // say; the pen goes to programs as a pen or as the pointer (the desktop decides), pads to
    // the program with the keyboard or to a binding ("Pad1").
    internal fun useTablets(devices: dev.caulis.core.libinput.InputDevices) {
        devices.onTabletAdded = running.tablets::add
        devices.onTabletRemoved = running.tablets::remove
        devices.onPadAdded = running.tablets::addPad
        devices.onPadRemoved = running.tablets::removePad
        devices.onTabletProximity = { time, tablet, tool, x, y, axes, near ->
            val (dx, dy) = tablet.place(x, y, tabletArea(tablet))
            running.desktop.tabletProximity(time, tablet, tool, dx, dy, asLaid(tablet, axes), near)
        }
        devices.onTabletMotion = { time, tablet, tool, x, y, axes ->
            val (dx, dy) = tablet.place(x, y, tabletArea(tablet))
            running.desktop.tabletMotion(time, tool, dx, dy, asLaid(tablet, axes))
        }
        devices.onTabletTip = { time, _, tool, down -> running.desktop.tabletTip(time, tool, down) }
        devices.onTabletButton = { time, _, tool, button, pressed -> running.desktop.tabletButton(time, tool, button, pressed) }
        devices.onPadButton = ::padButton
        devices.onPadRing = { time, pad, ring, degrees, finger ->
            running.userActive()
            running.tablets.padRing(time, pad, ring, degrees, finger)
        }
        devices.onPadStrip = { time, pad, strip, position, finger ->
            running.userActive()
            running.tablets.padStrip(time, pad, strip, position, finger)
        }
        devices.onPadMode = running.tablets::padMode
        for (device in devices.list) {
            device.tablet?.let(running.tablets::add)
            device.pad?.let(running.tablets::addPad)
        }
    }

    // A left-handed tablet lies turned around: its pen tilts the other way.
    private fun asLaid(tablet: Tablet, axes: TabletAxes) = if (tablet.settings.leftHanded == true) axes.turnedAround() else axes

    // Monitors named in tablet settings that are not there (said once each).
    private val missingTabletMonitors = HashSet<String>()

    /**
     * The part of the desktop [tablet] covers: the monitor its settings name; else, for a
     * tablet that is a screen, the laptop's own (or the first); else all monitors as one.
     */
    fun tabletArea(tablet: Tablet): dev.caulis.geometry.Rect {
        val monitors = running.desktop.monitors
        tablet.settings.monitor?.let { name ->
            monitors.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.area }
            if (missingTabletMonitors.add(name)) running.inputLog.warn("The tablet ${tablet.name} is set to the monitor $name, which is not there (it covers ${if (tablet.screen) "the laptop's screen" else "all monitors"} meanwhile)")
        }
        if (tablet.screen) return (monitors.firstOrNull { running.config.isBuiltIn(it.name) } ?: monitors.first()).area
        return monitors.map { it.area }.reduce(dev.caulis.geometry.Rect::union)
    }

    /** A tablet pad's [button] (0 the first): a combination bound to it ("Pad1"), else the program with the keyboard. */
    fun padButton(timeMillis: Int, pad: TabletPad, button: Int, pressed: Boolean) {
        running.userActive()
        val code = KeyBindings.padButton(button)
        val active = running.rinput.activeKeys()
        if (!pressed) for (other in running.rinput.allKeys()) if (other !== active) other.key(code, false)
        if (active.key(code, pressed)) return
        running.tablets.padButton(timeMillis, pad, button, pressed)
    }

    // The pretend tablet, pen and pad of the simulate functions (tests).
    internal val testTablet = Tablet("Caulis test tablet", widthMm = 216.0, heightMm = 135.0)

    internal val testPen = TabletTool(TabletTool.Type.PEN, setOf(TabletTool.Capability.PRESSURE, TabletTool.Capability.DISTANCE, TabletTool.Capability.TILT))

    internal val testPad by lazy { TabletPad("Caulis test pad", buttons = 4, tablet = testTablet).also(running.tablets::addPad) }

    internal var testPenNear = false
}
