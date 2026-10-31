package dev.caulis.core.libinput

import dev.caulis.core.input.Tablet
import dev.caulis.core.input.TabletAxes
import dev.caulis.core.input.TabletPad
import dev.caulis.core.input.TabletTool
import dev.caulis.ffi.CStrings
import dev.caulis.ffi.c.input.Input
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Drawing tablets as libinput tells of them: tools, axes, pads and their groups.
 */
internal class InputTablets(private val inputDevices: InputDevices) {

    // Tablet tools: each one by what tells it apart (see toolOf), and those near a tablet now.
    private val tools = HashMap<String, TabletTool>()

    internal class Near(val tablet: Tablet, val device: Long) {
        var tip = false
        var pressure = 0.0
        var x = 0.0
        var y = 0.0
        val buttons = LinkedHashSet<Int>()
    }

    internal val near = HashMap<TabletTool, Near>()

    // Each pad's groups' modes as last told, and its buttons held.
    private val padModes = HashMap<TabletPad, IntArray>()

    internal val padHeld = HashMap<TabletPad, MutableSet<Int>>()

    // The device group of each device (a tablet's pen and its pad are one group).
    internal val groups = HashMap<Long, Long>()

    internal fun tabletTool(type: Int, event: MemorySegment, device: MemorySegment) {
        val tablet = inputDevices.devices[device.address()]?.tablet ?: return
        val e = Input.libinput_event_get_tablet_tool_event(event)
        val time = Input.libinput_event_tablet_tool_get_time(e)
        val tool = toolOf(Input.libinput_event_tablet_tool_get_tool(e), device)
        val x = Input.libinput_event_tablet_tool_get_x_transformed(e, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
        val y = Input.libinput_event_tablet_tool_get_y_transformed(e, ABSOLUTE_RANGE) / ABSOLUTE_RANGE
        when (type) {
            Input.LIBINPUT_EVENT_TABLET_TOOL_PROXIMITY() -> {
                if (Input.libinput_event_tablet_tool_get_proximity_state(e) == Input.LIBINPUT_TABLET_TOOL_PROXIMITY_STATE_IN()) {
                    val n = Near(tablet, device.address())
                    near[tool] = n
                    val axes = axes(e, tool, all = true)
                    n.pressure = axes.pressure ?: 0.0
                    n.x = x
                    n.y = y
                    inputDevices.inputThread.toMain { inputDevices.onTabletProximity(time, tablet, tool, x, y, axes, true) }
                } else {
                    away(time, tool)
                }
            }
            Input.LIBINPUT_EVENT_TABLET_TOOL_AXIS(), Input.LIBINPUT_EVENT_TABLET_TOOL_TIP() -> {
                val n = near[tool] ?: return
                val axes = axes(e, tool, all = false)
                axes.pressure?.let { n.pressure = it }
                if (x != n.x || y != n.y || axes != TabletAxes.NONE) {
                    n.x = x
                    n.y = y
                    inputDevices.inputThread.toMain { inputDevices.onTabletMotion(time, tablet, tool, x, y, axes) }
                }
                // The tip: as libinput says, or as hard as the settings want.
                val threshold = tablet.settings.tipPressure?.takeIf { TabletTool.Capability.PRESSURE in tool.capabilities }
                val down = when {
                    threshold != null -> n.pressure >= threshold
                    type == Input.LIBINPUT_EVENT_TABLET_TOOL_TIP() -> Input.libinput_event_tablet_tool_get_tip_state(e) == Input.LIBINPUT_TABLET_TOOL_TIP_DOWN()
                    else -> n.tip
                }
                if (down != n.tip) {
                    n.tip = down
                    inputDevices.inputThread.toMain { inputDevices.onTabletTip(time, tablet, tool, down) }
                }
            }
            Input.LIBINPUT_EVENT_TABLET_TOOL_BUTTON() -> {
                val n = near[tool] ?: return
                val button = Input.libinput_event_tablet_tool_get_button(e)
                val pressed = Input.libinput_event_tablet_tool_get_button_state(e) == Input.LIBINPUT_BUTTON_STATE_PRESSED()
                if (if (pressed) n.buttons.add(button) else n.buttons.remove(button)) inputDevices.inputThread.toMain { inputDevices.onTabletButton(time, tablet, tool, button, pressed) }
            }
        }
    }

    // [tool] went away (or its tablet did): its tip lifted and its buttons let go first.
    internal fun away(time: Int, tool: TabletTool) {
        val n = near.remove(tool) ?: return
        val tablet = n.tablet
        val x = n.x
        val y = n.y
        if (n.tip) inputDevices.inputThread.toMain { inputDevices.onTabletTip(time, tablet, tool, false) }
        for (button in n.buttons.toList()) inputDevices.inputThread.toMain { inputDevices.onTabletButton(time, tablet, tool, button, false) }
        inputDevices.inputThread.toMain { inputDevices.onTabletProximity(time, tablet, tool, x, y, TabletAxes.NONE, false) }
    }

    // The tool libinput means: the same object for the same physical tool. One with a serial is
    // itself on every tablet; one without is a tool of its tablet.
    private fun toolOf(handle: MemorySegment, device: MemorySegment): TabletTool {
        val serial = Input.libinput_tablet_tool_get_serial(handle)
        val id = Input.libinput_tablet_tool_get_tool_id(handle)
        val type = Input.libinput_tablet_tool_get_type(handle)
        val key = if (serial != 0L) "$type:$serial:$id" else "${device.address()}:$type:$id"
        return tools.getOrPut(key) {
            val capabilities = buildSet {
                if (Input.libinput_tablet_tool_has_pressure(handle) != 0) add(TabletTool.Capability.PRESSURE)
                if (Input.libinput_tablet_tool_has_distance(handle) != 0) add(TabletTool.Capability.DISTANCE)
                if (Input.libinput_tablet_tool_has_tilt(handle) != 0) add(TabletTool.Capability.TILT)
                if (Input.libinput_tablet_tool_has_rotation(handle) != 0) add(TabletTool.Capability.ROTATION)
                if (Input.libinput_tablet_tool_has_slider(handle) != 0) add(TabletTool.Capability.SLIDER)
                if (Input.libinput_tablet_tool_has_wheel(handle) != 0) add(TabletTool.Capability.WHEEL)
            }
            val kind = when (type) {
                Input.LIBINPUT_TABLET_TOOL_TYPE_ERASER() -> TabletTool.Type.ERASER
                Input.LIBINPUT_TABLET_TOOL_TYPE_BRUSH() -> TabletTool.Type.BRUSH
                Input.LIBINPUT_TABLET_TOOL_TYPE_PENCIL() -> TabletTool.Type.PENCIL
                Input.LIBINPUT_TABLET_TOOL_TYPE_AIRBRUSH() -> TabletTool.Type.AIRBRUSH
                Input.LIBINPUT_TABLET_TOOL_TYPE_MOUSE() -> TabletTool.Type.MOUSE
                Input.LIBINPUT_TABLET_TOOL_TYPE_LENS() -> TabletTool.Type.LENS
                else -> TabletTool.Type.PEN
            }
            TabletTool(kind, capabilities, serial, id).also { inputDevices.log.info { "Tablet tool: ${it.type.name.lowercase()} (${capabilities.joinToString { c -> c.name.lowercase() }.ifEmpty { "no axes" }})" } }
        }
    }

    // What the tool tells besides its place: everything ([all], when it comes near) or what changed.
    private fun axes(e: MemorySegment, tool: TabletTool, all: Boolean): TabletAxes {
        fun has(c: TabletTool.Capability) = c in tool.capabilities
        fun changed(c: TabletTool.Capability, test: (MemorySegment) -> Int) = has(c) && (all || test(e) != 0)
        val tilt = changed(TabletTool.Capability.TILT) { Input.libinput_event_tablet_tool_tilt_x_has_changed(it) or Input.libinput_event_tablet_tool_tilt_y_has_changed(it) }
        val wheel = !all && has(TabletTool.Capability.WHEEL) && Input.libinput_event_tablet_tool_wheel_has_changed(e) != 0
        return TabletAxes(
            pressure = if (changed(TabletTool.Capability.PRESSURE, Input::libinput_event_tablet_tool_pressure_has_changed)) Input.libinput_event_tablet_tool_get_pressure(e) else null,
            distance = if (changed(TabletTool.Capability.DISTANCE, Input::libinput_event_tablet_tool_distance_has_changed)) Input.libinput_event_tablet_tool_get_distance(e) else null,
            // (Both together: programs get them in one event.)
            tiltX = if (tilt) Input.libinput_event_tablet_tool_get_tilt_x(e) else null,
            tiltY = if (tilt) Input.libinput_event_tablet_tool_get_tilt_y(e) else null,
            rotation = if (changed(TabletTool.Capability.ROTATION, Input::libinput_event_tablet_tool_rotation_has_changed)) Input.libinput_event_tablet_tool_get_rotation(e) else null,
            slider = if (changed(TabletTool.Capability.SLIDER, Input::libinput_event_tablet_tool_slider_has_changed)) Input.libinput_event_tablet_tool_get_slider_position(e) else null,
            wheelDegrees = if (wheel) Input.libinput_event_tablet_tool_get_wheel_delta(e) else 0.0,
            wheelClicks = if (wheel) Input.libinput_event_tablet_tool_get_wheel_delta_discrete(e) else 0,
        )
    }

    internal fun pad(type: Int, event: MemorySegment, device: MemorySegment) {
        val pad = inputDevices.devices[device.address()]?.pad ?: return
        val e = Input.libinput_event_get_tablet_pad_event(event)
        val time = Input.libinput_event_tablet_pad_get_time(e)
        // Every pad event says the mode its group is in (a mode button switched it).
        val modes = padModes.getOrPut(pad) { IntArray(pad.groups.size) }
        val group = Input.libinput_tablet_pad_mode_group_get_index(Input.libinput_event_tablet_pad_get_mode_group(e))
        val mode = Input.libinput_event_tablet_pad_get_mode(e)
        if (group in modes.indices && modes[group] != mode) {
            modes[group] = mode
            inputDevices.inputThread.toMain { inputDevices.onPadMode(time, pad, group, mode) }
        }
        when (type) {
            Input.LIBINPUT_EVENT_TABLET_PAD_BUTTON() -> {
                val button = Input.libinput_event_tablet_pad_get_button_number(e)
                val pressed = Input.libinput_event_tablet_pad_get_button_state(e) == Input.LIBINPUT_BUTTON_STATE_PRESSED()
                val buttons = padHeld.getOrPut(pad) { HashSet() }
                if (if (pressed) buttons.add(button) else buttons.remove(button)) inputDevices.inputThread.toMain { inputDevices.onPadButton(time, pad, button, pressed) }
            }
            Input.LIBINPUT_EVENT_TABLET_PAD_RING() -> {
                val position = Input.libinput_event_tablet_pad_get_ring_position(e)
                val finger = Input.libinput_event_tablet_pad_get_ring_source(e) == Input.LIBINPUT_TABLET_PAD_RING_SOURCE_FINGER()
                val ring = Input.libinput_event_tablet_pad_get_ring_number(e)
                inputDevices.inputThread.toMain { inputDevices.onPadRing(time, pad, ring, position.takeIf { it >= 0 }, finger) }
            }
            Input.LIBINPUT_EVENT_TABLET_PAD_STRIP() -> {
                val position = Input.libinput_event_tablet_pad_get_strip_position(e)
                val finger = Input.libinput_event_tablet_pad_get_strip_source(e) == Input.LIBINPUT_TABLET_PAD_STRIP_SOURCE_FINGER()
                val strip = Input.libinput_event_tablet_pad_get_strip_number(e)
                inputDevices.inputThread.toMain { inputDevices.onPadStrip(time, pad, strip, position.takeIf { it >= 0 }, finger) }
            }
        }
    }

    // A tablet or a pad came: what it is (its size, ids, buttons and groups).
    internal fun tabletOf(device: MemorySegment, name: String): Tablet = Arena.ofConfined().use { temp ->
        val width = temp.allocate(java.lang.foreign.ValueLayout.JAVA_DOUBLE)
        val height = temp.allocate(java.lang.foreign.ValueLayout.JAVA_DOUBLE)
        val sized = Input.libinput_device_get_size(device, width, height) == 0
        Tablet(
            name, Input.libinput_device_get_id_vendor(device), Input.libinput_device_get_id_product(device), pathOf(device),
            if (sized) width.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0) else 0.0,
            if (sized) height.get(java.lang.foreign.ValueLayout.JAVA_DOUBLE, 0) else 0.0,
            screen = Input.libinput_device_config_calibration_has_matrix(device) != 0,
        )
    }

    internal fun padOf(device: MemorySegment, name: String): TabletPad {
        val buttons = Input.libinput_device_tablet_pad_get_num_buttons(device).coerceAtLeast(0)
        val rings = Input.libinput_device_tablet_pad_get_num_rings(device).coerceAtLeast(0)
        val strips = Input.libinput_device_tablet_pad_get_num_strips(device).coerceAtLeast(0)
        val groups = (0 until Input.libinput_device_tablet_pad_get_num_mode_groups(device).coerceAtLeast(0)).mapNotNull { i ->
            val g = Input.libinput_device_tablet_pad_get_mode_group(device, i)
            if (g == MemorySegment.NULL) return@mapNotNull null
            TabletPad.Group(
                (0 until buttons).filter { Input.libinput_tablet_pad_mode_group_has_button(g, it) != 0 },
                (0 until rings).filter { Input.libinput_tablet_pad_mode_group_has_ring(g, it) != 0 },
                (0 until strips).filter { Input.libinput_tablet_pad_mode_group_has_strip(g, it) != 0 },
                Input.libinput_tablet_pad_mode_group_get_num_modes(g).coerceAtLeast(1),
            )
        }
        return if (groups.isEmpty()) TabletPad(name, buttons, rings, strips, path = pathOf(device)) else TabletPad(name, buttons, rings, strips, groups, path = pathOf(device))
    }

    // The device's file ("/dev/input/event7"), or null.
    private fun pathOf(device: MemorySegment): String? {
        val udevDevice = Input.libinput_device_get_udev_device(device)
        if (udevDevice == MemorySegment.NULL) return null
        return try {
            CStrings.borrowed(Input.udev_device_get_devnode(udevDevice))
        } finally {
            Input.udev_device_unref(udevDevice)
        }
    }

    // A tablet's pen and its pad are one device group: each knows the other once both are there.
    internal fun pairPads() {
        val tablets = inputDevices.devices.values.filter { it.tablet != null }
        for (padDevice in inputDevices.devices.values) {
            val pad = padDevice.pad ?: continue
            if (pad.tablet != null) continue
            val group = groups[padDevice.handle.address()] ?: continue
            pad.tablet = tablets.firstOrNull { groups[it.handle.address()] == group }?.tablet
        }
    }
}
