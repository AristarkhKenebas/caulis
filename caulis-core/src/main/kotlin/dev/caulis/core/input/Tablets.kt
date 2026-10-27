package dev.caulis.core.input

import dev.caulis.core.Surface
import dev.caulis.protocol.Client
import dev.caulis.protocol.Display
import dev.caulis.protocol.tabletv2.ZwpTabletManagerV2
import dev.caulis.protocol.tabletv2.ZwpTabletPadGroupV2
import dev.caulis.protocol.tabletv2.ZwpTabletPadRingV2
import dev.caulis.protocol.tabletv2.ZwpTabletPadStripV2
import dev.caulis.protocol.tabletv2.ZwpTabletPadV2
import dev.caulis.protocol.tabletv2.ZwpTabletSeatV2
import dev.caulis.protocol.tabletv2.ZwpTabletToolV2
import dev.caulis.protocol.tabletv2.ZwpTabletV2
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * zwp_tablet_manager_v2 (tablet-v2): drawing programs (Krita, GIMP, Inkscape, Xwayland's
 * X11 programs) get the pen as a pen — pressure, tilt, distance, rotation, the eraser end —
 * instead of as the pointer.
 *
 * Every program that asks is told about the tablets there are ([add]), the tools used on
 * them so far, and the pads. Which program gets the pen is the desktop's choice
 * ([dev.caulis.core.Desktop.tabletMotion] and the rest: the one under it, unless the pen is
 * the pointer there); the pads go to the program with the keyboard.
 */
class Tablets(private val display: Display, private val seat: Seat) {

    private val log = dev.caulis.log.Logger("input")

    // The tablets and pads there are now, the tools used so far (programs are told of a tool
    // when it is first used), and the tablet each tool was last used on.
    private val tablets = ArrayList<Tablet>()
    private val pads = ArrayList<TabletPad>()
    private val tools = LinkedHashMap<TabletTool, Tablet>()

    // One program's tablet seat, and its objects for each tablet, tool and pad.
    private class ProgramSeat(val resource: ZwpTabletSeatV2) {
        val client: Client get() = resource.client
        val tablets = HashMap<Tablet, ZwpTabletV2>()
        val tools = HashMap<TabletTool, ZwpTabletToolV2>()
        val pads = HashMap<TabletPad, PadObjects>()
    }

    private class PadObjects(
        val pad: ZwpTabletPadV2,
        val groups: List<ZwpTabletPadGroupV2>,
        val rings: Map<Int, ZwpTabletPadRingV2>,
        val strips: Map<Int, ZwpTabletPadStripV2>,
    ) {
        // The surface the pad was entered on (leave only follows an enter).
        var entered: Surface? = null
    }

    private val seats = ArrayList<ProgramSeat>()

    // Where each tool in a program's surface is: the surface, its tablet, and the tip and
    // buttons held there (let go when it leaves).
    private class Focus(val surface: Surface, val tablet: Tablet) {
        var down = false
        val buttons = LinkedHashSet<Int>()
    }

    private val focus = HashMap<TabletTool, Focus>()

    // The surface the pads' events go to (the one with the keyboard), and each pad's modes.
    private var padFocus: Surface? = null
    private val modes = HashMap<TabletPad, IntArray>()

    init {
        display.addGlobal(ZwpTabletManagerV2, VERSION) { manager ->
            manager.requests = object : ZwpTabletManagerV2.Requests {
                override fun getTabletSeat(resource: ZwpTabletManagerV2, tabletSeat: ZwpTabletSeatV2, seat: WlSeat) = serve(tabletSeat)
                override fun destroy(resource: ZwpTabletManagerV2) {}
            }
        }
        // The pads follow the keyboard.
        seat.onKeyboardFocus(::padsTo)
        // The lock took the surface the pen was over.
        seat.penFocusLost = { surface -> for ((tool, f) in focus.toList()) if (f.surface === surface) proximityOut(now(), tool) }
    }

    private fun serve(resource: ZwpTabletSeatV2) {
        val programSeat = ProgramSeat(resource)
        resource.requests = object : ZwpTabletSeatV2.Requests {
            override fun destroy(resource: ZwpTabletSeatV2) {}
        }
        seats += programSeat
        resource.onDestroy { seats -= programSeat }
        log.trace { "A program takes tablet events (${tablets.size} tablets, ${pads.size} pads)" }
        for (tablet in tablets) announce(programSeat, tablet)
        for ((tool, tablet) in tools) announce(programSeat, tool, tablet)
        for (pad in pads) announce(programSeat, pad)
    }

    /** True if the program of [surface] takes tablet events (else the pen is the pointer for it). */
    fun takes(surface: Surface): Boolean = seats.any { it.client === surface.resource.client }

    // --- devices coming and going --------------------------------------------------------------

    /** A tablet came: programs are told. (A tablet a tool is used on is added by itself.) */
    fun add(tablet: Tablet) {
        if (tablet in tablets) return
        tablets += tablet
        log.info { "Tablet: ${tablet.name}" }
        for (s in seats) announce(s, tablet)
    }

    /** A tablet went away: programs are told, and so are they of its tools that cannot be told apart from others (no serial). */
    fun remove(tablet: Tablet) {
        if (!tablets.remove(tablet)) return
        for ((tool, f) in focus.toList()) if (f.tablet === tablet) proximityOut(now(), tool)
        for (s in seats) s.tablets.remove(tablet)?.sendRemoved()
        for (tool in tools.filter { (tool, on) -> on === tablet && tool.serial == 0L }.keys) {
            tools.remove(tool)
            for (s in seats) s.tools.remove(tool)?.sendRemoved()
        }
        log.info { "Tablet gone: ${tablet.name}" }
    }

    /** A tablet's pad came (its tablet first, if it has one): programs are told. */
    fun addPad(pad: TabletPad) {
        if (pad in pads) return
        pad.tablet?.let(::add)
        pads += pad
        modes[pad] = IntArray(pad.groups.size)
        log.info { "Tablet pad: ${pad.name} (${pad.buttons} buttons, ${pad.rings} rings, ${pad.strips} strips)" }
        for (s in seats) announce(s, pad)
    }

    /** A pad went away. */
    fun removePad(pad: TabletPad) {
        if (!pads.remove(pad)) return
        modes.remove(pad)
        for (s in seats) s.pads.remove(pad)?.pad?.sendRemoved()
    }

    private fun announce(s: ProgramSeat, tablet: Tablet) {
        val r = s.client.createServerResource(ZwpTabletV2, s.resource.version)
        r.requests = object : ZwpTabletV2.Requests {
            override fun destroy(resource: ZwpTabletV2) {}
        }
        s.tablets[tablet] = r
        r.onDestroy { if (s.tablets[tablet] === r) s.tablets.remove(tablet) }
        s.resource.sendTabletAdded(r)
        r.sendName(tablet.name)
        if (tablet.vendorId != 0 || tablet.productId != 0) r.sendId(tablet.vendorId, tablet.productId)
        tablet.path?.let(r::sendPath)
        r.sendDone()
    }

    private fun announce(s: ProgramSeat, tool: TabletTool, tablet: Tablet) {
        if (tablet !in s.tablets) return
        val r = s.client.createServerResource(ZwpTabletToolV2, s.resource.version)
        r.requests = object : ZwpTabletToolV2.Requests {
            override fun setCursor(resource: ZwpTabletToolV2, serial: Int, surface: WlSurface?, hotspotX: Int, hotspotY: Int) {
                val target = surface?.dataAs<Surface>()
                if (target != null && !target.assignRole(Surface.Role.CURSOR, resource, ZwpTabletToolV2.Error.ROLE)) return
                seat.setPenCursor(resource.client, target, hotspotX, hotspotY)
            }

            override fun destroy(resource: ZwpTabletToolV2) {}
        }
        s.tools[tool] = r
        r.onDestroy { if (s.tools[tool] === r) s.tools.remove(tool) }
        s.resource.sendToolAdded(r)
        r.sendType(
            when (tool.type) {
                TabletTool.Type.PEN -> ZwpTabletToolV2.Type.PEN
                TabletTool.Type.ERASER -> ZwpTabletToolV2.Type.ERASER
                TabletTool.Type.BRUSH -> ZwpTabletToolV2.Type.BRUSH
                TabletTool.Type.PENCIL -> ZwpTabletToolV2.Type.PENCIL
                TabletTool.Type.AIRBRUSH -> ZwpTabletToolV2.Type.AIRBRUSH
                TabletTool.Type.MOUSE -> ZwpTabletToolV2.Type.MOUSE
                TabletTool.Type.LENS -> ZwpTabletToolV2.Type.LENS
            },
        )
        if (tool.serial != 0L) r.sendHardwareSerial((tool.serial ushr 32).toInt(), tool.serial.toInt())
        if (tool.hardwareId != 0L) r.sendHardwareIdWacom((tool.hardwareId ushr 32).toInt(), tool.hardwareId.toInt())
        for (capability in tool.capabilities) {
            r.sendCapability(
                when (capability) {
                    TabletTool.Capability.TILT -> ZwpTabletToolV2.Capability.TILT
                    TabletTool.Capability.PRESSURE -> ZwpTabletToolV2.Capability.PRESSURE
                    TabletTool.Capability.DISTANCE -> ZwpTabletToolV2.Capability.DISTANCE
                    TabletTool.Capability.ROTATION -> ZwpTabletToolV2.Capability.ROTATION
                    TabletTool.Capability.SLIDER -> ZwpTabletToolV2.Capability.SLIDER
                    TabletTool.Capability.WHEEL -> ZwpTabletToolV2.Capability.WHEEL
                },
            )
        }
        r.sendDone()
    }

    private fun announce(s: ProgramSeat, pad: TabletPad) {
        val client = s.client
        val version = s.resource.version
        val r = client.createServerResource(ZwpTabletPadV2, version)
        r.requests = object : ZwpTabletPadV2.Requests {
            // (What a button does, for an on-screen overview of the pad: not shown by Caulis.)
            override fun setFeedback(resource: ZwpTabletPadV2, button: Int, description: String, serial: Int) {}
            override fun destroy(resource: ZwpTabletPadV2) {}
        }
        s.resource.sendPadAdded(r)
        pad.path?.let(r::sendPath)
        r.sendButtons(pad.buttons)
        val rings = HashMap<Int, ZwpTabletPadRingV2>()
        val strips = HashMap<Int, ZwpTabletPadStripV2>()
        val groups = pad.groups.map { group ->
            val g = client.createServerResource(ZwpTabletPadGroupV2, version)
            g.requests = object : ZwpTabletPadGroupV2.Requests {
                override fun destroy(resource: ZwpTabletPadGroupV2) {}
            }
            r.sendGroup(g)
            g.sendButtons(intArray(group.buttons))
            for (number in group.rings) {
                val ring = client.createServerResource(ZwpTabletPadRingV2, version)
                ring.requests = object : ZwpTabletPadRingV2.Requests {
                    override fun setFeedback(resource: ZwpTabletPadRingV2, description: String, serial: Int) {}
                    override fun destroy(resource: ZwpTabletPadRingV2) {}
                }
                g.sendRing(ring)
                rings[number] = ring
            }
            for (number in group.strips) {
                val strip = client.createServerResource(ZwpTabletPadStripV2, version)
                strip.requests = object : ZwpTabletPadStripV2.Requests {
                    override fun setFeedback(resource: ZwpTabletPadStripV2, description: String, serial: Int) {}
                    override fun destroy(resource: ZwpTabletPadStripV2) {}
                }
                g.sendStrip(strip)
                strips[number] = strip
            }
            g.sendModes(group.modes.coerceAtLeast(1))
            g.sendDone()
            g
        }
        r.sendDone()
        s.pads[pad] = PadObjects(r, groups, rings, strips)
        r.onDestroy { if (s.pads[pad]?.pad === r) s.pads.remove(pad) }
        // The program has the keyboard already: its new pad is in its surface at once.
        val surface = padFocus
        if (surface != null && surface.resource.client === client) enterPad(s, pad, surface)
    }

    private fun intArray(values: List<Int>): ByteArray {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach { bytes.putInt(it) }
        return bytes.array()
    }

    private fun seatsOf(client: Client) = seats.filter { it.client === client && it.resource.isAlive }

    // --- the pen (the desktop decides where it goes) -------------------------------------------

    // [x], [y] in the surface's units; a surface in screen pixels (Xwayland's) gets its pixels.
    private fun scaled(surface: Surface, v: Double) = v * (surface.nativeScale ?: 1.0)

    // The tool is used on [tablet]: programs that do not know them yet are told.
    private fun used(tool: TabletTool, tablet: Tablet) {
        add(tablet)
        if (tools[tool] === tablet) return
        tools[tool] = tablet
        for (s in seats) if (tool !in s.tools) announce(s, tool, tablet)
    }

    /**
     * [tool] on [tablet] came over [surface] at ([x], [y]) of it (units), with all its [axes]:
     * that surface's program gets it from now on (until [proximityOut]).
     */
    internal fun proximityIn(timeMillis: Int, tablet: Tablet, tool: TabletTool, surface: Surface, x: Double, y: Double, axes: TabletAxes) {
        if (!surface.resource.isAlive || !seat.allowed(surface)) return
        focus[tool]?.let { if (it.surface === surface) return motion(timeMillis, tool, x, y, axes) }
        proximityOut(timeMillis, tool)
        used(tool, tablet)
        focus[tool] = Focus(surface, tablet)
        seat.penEntered(surface)
        log.trace { "The $tool is over ${surface.roleObject ?: surface} (tablet events)" }
        val serial = display.nextSerial()
        for (s in seatsOf(surface.resource.client)) {
            val t = s.tools[tool] ?: continue
            val on = s.tablets[tablet] ?: continue
            t.sendProximityIn(serial, on, surface.resource)
            t.sendMotion(scaled(surface, x), scaled(surface, y))
            sendAxes(t, axes)
            t.sendFrame(timeMillis)
        }
    }

    /** [tool] moved to ([x], [y]) of the surface it is over, and its [axes] changed. */
    internal fun motion(timeMillis: Int, tool: TabletTool, x: Double, y: Double, axes: TabletAxes) {
        val f = focusOf(tool) ?: return
        for (t in toolsOf(f.surface, tool)) {
            t.sendMotion(scaled(f.surface, x), scaled(f.surface, y))
            sendAxes(t, axes)
            t.sendFrame(timeMillis)
        }
    }

    private fun sendAxes(t: ZwpTabletToolV2, axes: TabletAxes) {
        axes.pressure?.let { t.sendPressure(unit(it)) }
        axes.distance?.let { t.sendDistance(unit(it)) }
        if (axes.tiltX != null || axes.tiltY != null) t.sendTilt(axes.tiltX ?: 0.0, axes.tiltY ?: 0.0)
        axes.rotation?.let(t::sendRotation)
        axes.slider?.let { t.sendSlider((it.coerceIn(-1.0, 1.0) * 65535).roundToInt()) }
        if (axes.wheelDegrees != 0.0 || axes.wheelClicks != 0) t.sendWheel(axes.wheelDegrees, axes.wheelClicks)
    }

    // 0..1 as the protocol's 0..65535.
    private fun unit(value: Double) = (value.coerceIn(0.0, 1.0) * 65535).roundToInt()

    /** [tool] touched the tablet ([down]) or lifted off it. */
    internal fun tip(timeMillis: Int, tool: TabletTool, down: Boolean) {
        val f = focusOf(tool) ?: return
        if (f.down == down) return
        f.down = down
        val serial = display.nextSerial()
        for (t in toolsOf(f.surface, tool)) {
            if (down) t.sendDown(serial) else t.sendUp()
            t.sendFrame(timeMillis)
        }
    }

    /** A button of [tool] ([button]: a Linux code, 0x14b the lower one) was pressed or let go. */
    internal fun button(timeMillis: Int, tool: TabletTool, button: Int, pressed: Boolean) {
        val f = focusOf(tool) ?: return
        if (if (pressed) !f.buttons.add(button) else !f.buttons.remove(button)) return
        val serial = display.nextSerial()
        val state = if (pressed) ZwpTabletToolV2.ButtonState.PRESSED else ZwpTabletToolV2.ButtonState.RELEASED
        for (t in toolsOf(f.surface, tool)) {
            t.sendButton(serial, button, state)
            t.sendFrame(timeMillis)
        }
    }

    /** [tool] left the surface it was over (or the tablet): the tip and buttons held are let go first. */
    internal fun proximityOut(timeMillis: Int, tool: TabletTool) {
        val f = focus.remove(tool) ?: return
        if (seat.penFocus === f.surface && focus.values.none { it.surface === f.surface }) seat.penEntered(null)
        if (!f.surface.resource.isAlive) return
        log.trace { "The $tool left ${f.surface.roleObject ?: f.surface}" }
        val serial = display.nextSerial()
        for (t in toolsOf(f.surface, tool)) {
            if (f.down) t.sendUp()
            for (button in f.buttons) t.sendButton(serial, button, ZwpTabletToolV2.ButtonState.RELEASED)
            t.sendProximityOut()
            t.sendFrame(timeMillis)
        }
    }

    /** The surface [tool] is over now (in a program that takes tablet events), or null. */
    internal fun surfaceOf(tool: TabletTool): Surface? = focus[tool]?.surface

    // Where the tool is, if events may go there (never to a surface the lock took).
    private fun focusOf(tool: TabletTool): Focus? {
        val f = focus[tool] ?: return null
        if (f.surface.resource.isAlive && seat.allowed(f.surface)) return f
        proximityOut(now(), tool)
        return null
    }

    private fun toolsOf(surface: Surface, tool: TabletTool) = seatsOf(surface.resource.client).mapNotNull { it.tools[tool] }

    // --- pads: their events go to the program with the keyboard ------------------------------------

    private fun padsTo(surface: Surface?) {
        val old = padFocus
        if (old === surface) return
        padFocus = surface
        if (old != null && old.resource.isAlive) {
            val serial = display.nextSerial()
            for (s in seatsOf(old.resource.client)) for (objects in s.pads.values) {
                if (objects.entered !== old) continue
                objects.entered = null
                objects.pad.sendLeave(serial, old.resource)
            }
        }
        if (surface != null) for (s in seatsOf(surface.resource.client)) for (pad in s.pads.keys.toList()) enterPad(s, pad, surface)
    }

    private fun enterPad(s: ProgramSeat, pad: TabletPad, surface: Surface) {
        val objects = s.pads[pad] ?: return
        val tablet = pad.tablet?.let { s.tablets[it] } ?: return // (the protocol names the pad's tablet)
        if (objects.entered === surface) return
        objects.pad.sendEnter(display.nextSerial(), tablet, surface.resource)
        objects.entered = surface
        // Each group's mode, as it is now.
        val now = now()
        val current = modes[pad] ?: return
        objects.groups.forEachIndexed { i, g -> g.sendModeSwitch(now, display.nextSerial(), current.getOrElse(i) { 0 }) }
    }

    // The pad's objects of the program with the keyboard (if it takes tablet events, and the pad is in its surface).
    private fun padObjects(pad: TabletPad): List<PadObjects> {
        val surface = padFocus?.takeIf { it.resource.isAlive && seat.allowed(it) } ?: return emptyList()
        if (pad.tablet == null) return emptyList()
        return seatsOf(surface.resource.client).mapNotNull { it.pads[pad] }
    }

    /** A pad's [button] (0 is the first) was pressed or let go: the program with the keyboard gets it. */
    fun padButton(timeMillis: Int, pad: TabletPad, button: Int, pressed: Boolean) {
        val state = if (pressed) ZwpTabletPadV2.ButtonState.PRESSED else ZwpTabletPadV2.ButtonState.RELEASED
        for (objects in padObjects(pad)) objects.pad.sendButton(timeMillis, button, state)
    }

    /**
     * A pad's [ring] turned to [degrees] (clockwise from the top; null: the finger left it),
     * touched by a [finger] (false: unknown how).
     */
    fun padRing(timeMillis: Int, pad: TabletPad, ring: Int, degrees: Double?, finger: Boolean) {
        for (objects in padObjects(pad)) {
            val r = objects.rings[ring] ?: continue
            if (finger) r.sendSource(ZwpTabletPadRingV2.Source.FINGER)
            if (degrees != null) r.sendAngle(degrees) else r.sendStop()
            r.sendFrame(timeMillis)
        }
    }

    /** A pad's [strip] touched at [position] (0..1 from the top or left; null: the finger left it). */
    fun padStrip(timeMillis: Int, pad: TabletPad, strip: Int, position: Double?, finger: Boolean) {
        for (objects in padObjects(pad)) {
            val r = objects.strips[strip] ?: continue
            if (finger) r.sendSource(ZwpTabletPadStripV2.Source.FINGER)
            if (position != null) r.sendPosition(unit(position)) else r.sendStop()
            r.sendFrame(timeMillis)
        }
    }

    /** The pad's [group] switched to [mode] (0 is the first): the program with the keyboard is told. */
    fun padMode(timeMillis: Int, pad: TabletPad, group: Int, mode: Int) {
        val current = modes[pad] ?: return
        if (group !in current.indices || current[group] == mode) return
        // (A mode the group does not have would be a lie to the program.)
        if (mode !in 0 until pad.groups[group].modes.coerceAtLeast(1)) {
            log.warn("${pad.name}: mode $mode of group $group does not exist (it has ${pad.groups[group].modes}); ignored")
            return
        }
        current[group] = mode
        val serial = display.nextSerial()
        for (objects in padObjects(pad)) objects.groups.getOrNull(group)?.sendModeSwitch(timeMillis, serial, mode)
    }

    private fun now(): Int = (System.nanoTime() / 1_000_000).toInt()

    companion object {
        const val VERSION = 1
    }
}
