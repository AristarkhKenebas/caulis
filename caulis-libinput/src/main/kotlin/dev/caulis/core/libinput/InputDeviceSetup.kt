package dev.caulis.core.libinput

import dev.caulis.core.input.AccelProfile
import dev.caulis.core.input.ClickMethod
import dev.caulis.core.input.ScrollMethod
import dev.caulis.core.input.TapButtons
import dev.caulis.ffi.CStrings
import dev.caulis.ffi.c.input.Input
import java.lang.foreign.MemorySegment

/**
 * A device coming: what it is and how it is set up from the settings.
 */
internal class InputDeviceSetup(private val inputDevices: InputDevices) {

    // A device came: what it is, and its settings.
    internal fun added(device: MemorySegment) {
        val name = CStrings.borrowed(Input.libinput_device_get_name(device)) ?: "unknown"
        fun has(capability: Int) = Input.libinput_device_has_capability(device, capability) != 0
        val touchpad = Input.libinput_device_config_tap_get_finger_count(device) > 0
        val kinds = buildSet {
            if (has(Input.LIBINPUT_DEVICE_CAP_KEYBOARD())) add(InputDevice.Kind.KEYBOARD)
            if (has(Input.LIBINPUT_DEVICE_CAP_POINTER())) add(if (touchpad) InputDevice.Kind.TOUCHPAD else InputDevice.Kind.POINTER)
            if (has(Input.LIBINPUT_DEVICE_CAP_TOUCH())) add(InputDevice.Kind.TOUCHSCREEN)
            if (has(Input.LIBINPUT_DEVICE_CAP_TABLET_TOOL())) add(InputDevice.Kind.TABLET)
            if (has(Input.LIBINPUT_DEVICE_CAP_TABLET_PAD())) add(InputDevice.Kind.TABLET_PAD)
            if (has(Input.LIBINPUT_DEVICE_CAP_SWITCH())) add(InputDevice.Kind.SWITCH)
        }
        val known = InputDevice(name, kinds, device)
        if (InputDevice.Kind.TABLET in kinds) known.tablet = inputDevices.tabletInput.tabletOf(device, name)
        if (InputDevice.Kind.TABLET_PAD in kinds) known.pad = inputDevices.tabletInput.padOf(device, name)
        inputDevices.devices[device.address()] = known
        inputDevices.tabletInput.groups[device.address()] = Input.libinput_device_get_device_group(device).address()
        inputDevices.log.info { "Device: $name (${kinds.joinToString { it.name.lowercase() }})" }
        configure(known)
        inputDevices.tabletInput.pairPads()
        inputDevices.published = inputDevices.devices.values.toList()
        known.tablet?.let { t -> inputDevices.inputThread.toMain { inputDevices.onTabletAdded(t) } }
        known.pad?.let { p -> inputDevices.inputThread.toMain { inputDevices.onPadAdded(p) } }
        inputDevices.inputThread.toMain { inputDevices.onDevicesChanged() }
    }

    // Applies the settings that are for [device] (pointers, touchpads, tablets).
    internal fun configure(device: InputDevice) {
        val d = device.handle
        if (device.tablet != null || device.pad != null) {
            val s = inputDevices.settings.forTablet(device.name)
            device.tablet?.settings = s
            Input.libinput_device_config_send_events_set_mode(
                d, if (s.enabled == false) Input.LIBINPUT_CONFIG_SEND_EVENTS_DISABLED() else Input.LIBINPUT_CONFIG_SEND_EVENTS_ENABLED(),
            )
            if (InputDevice.Kind.POINTER !in device.kinds) return
        }
        val touchpad = InputDevice.Kind.TOUCHPAD in device.kinds
        if (InputDevice.Kind.POINTER !in device.kinds && !touchpad) return
        val s = inputDevices.settings.forDevice(device.name, touchpad)
        device.scrollFactor = s.scrollFactor ?: 1.0
        fun flag(value: Boolean?, on: Int, off: Int, set: (Int) -> Int) = value?.let { set(if (it) on else off) }
        val modes = Input.libinput_device_config_send_events_get_modes(d)
        val mode = when {
            s.enabled == false || (touchpad && !inputDevices.touchpadsOn) -> Input.LIBINPUT_CONFIG_SEND_EVENTS_DISABLED()
            s.offWithMouse == true && modes and Input.LIBINPUT_CONFIG_SEND_EVENTS_DISABLED_ON_EXTERNAL_MOUSE() != 0 -> Input.LIBINPUT_CONFIG_SEND_EVENTS_DISABLED_ON_EXTERNAL_MOUSE()
            else -> Input.LIBINPUT_CONFIG_SEND_EVENTS_ENABLED()
        }
        Input.libinput_device_config_send_events_set_mode(d, mode)
        if (Input.libinput_device_config_accel_is_available(d) != 0) {
            s.speed?.let { Input.libinput_device_config_accel_set_speed(d, it) }
            s.accelProfile?.let {
                Input.libinput_device_config_accel_set_profile(d, if (it == AccelProfile.FLAT) Input.LIBINPUT_CONFIG_ACCEL_PROFILE_FLAT() else Input.LIBINPUT_CONFIG_ACCEL_PROFILE_ADAPTIVE())
            }
        }
        if (Input.libinput_device_config_scroll_has_natural_scroll(d) != 0) {
            s.naturalScroll?.let { Input.libinput_device_config_scroll_set_natural_scroll_enabled(d, if (it) 1 else 0) }
        }
        s.scrollMethod?.let { method ->
            val wanted = when (method) {
                ScrollMethod.NONE -> Input.LIBINPUT_CONFIG_SCROLL_NO_SCROLL()
                ScrollMethod.TWO_FINGER -> Input.LIBINPUT_CONFIG_SCROLL_2FG()
                ScrollMethod.EDGE -> Input.LIBINPUT_CONFIG_SCROLL_EDGE()
                ScrollMethod.BUTTON -> Input.LIBINPUT_CONFIG_SCROLL_ON_BUTTON_DOWN()
            }
            if (wanted == 0 || Input.libinput_device_config_scroll_get_methods(d) and wanted != 0) Input.libinput_device_config_scroll_set_method(d, wanted)
        }
        s.scrollButton?.let { Input.libinput_device_config_scroll_set_button(d, it) }
        if (Input.libinput_device_config_left_handed_is_available(d) != 0) s.leftHanded?.let { Input.libinput_device_config_left_handed_set(d, if (it) 1 else 0) }
        if (Input.libinput_device_config_middle_emulation_is_available(d) != 0) {
            flag(s.middleEmulation, Input.LIBINPUT_CONFIG_MIDDLE_EMULATION_ENABLED(), Input.LIBINPUT_CONFIG_MIDDLE_EMULATION_DISABLED()) { Input.libinput_device_config_middle_emulation_set_enabled(d, it) }
        }
        if (touchpad) {
            // Tapping is on unless said otherwise.
            Input.libinput_device_config_tap_set_enabled(d, if (s.tap != false) Input.LIBINPUT_CONFIG_TAP_ENABLED() else Input.LIBINPUT_CONFIG_TAP_DISABLED())
            s.tapButtons?.let {
                Input.libinput_device_config_tap_set_button_map(d, if (it == TapButtons.LEFT_RIGHT_MIDDLE) Input.LIBINPUT_CONFIG_TAP_MAP_LRM() else Input.LIBINPUT_CONFIG_TAP_MAP_LMR())
            }
            flag(s.tapDrag, Input.LIBINPUT_CONFIG_DRAG_ENABLED(), Input.LIBINPUT_CONFIG_DRAG_DISABLED()) { Input.libinput_device_config_tap_set_drag_enabled(d, it) }
            flag(s.dragLock, Input.LIBINPUT_CONFIG_DRAG_LOCK_ENABLED(), Input.LIBINPUT_CONFIG_DRAG_LOCK_DISABLED()) { Input.libinput_device_config_tap_set_drag_lock_enabled(d, it) }
            if (Input.libinput_device_config_dwt_is_available(d) != 0) {
                flag(s.disableWhileTyping, Input.LIBINPUT_CONFIG_DWT_ENABLED(), Input.LIBINPUT_CONFIG_DWT_DISABLED()) { Input.libinput_device_config_dwt_set_enabled(d, it) }
            }
            s.clickMethod?.let { method ->
                val wanted = if (method == ClickMethod.AREAS) Input.LIBINPUT_CONFIG_CLICK_METHOD_BUTTON_AREAS() else Input.LIBINPUT_CONFIG_CLICK_METHOD_CLICKFINGER()
                if (Input.libinput_device_config_click_get_methods(d) and wanted != 0) Input.libinput_device_config_click_set_method(d, wanted)
            }
        }
    }

    // Remembers what is held. Returns false for a release of something never pressed
    // (e.g. a key held while switching back from another terminal): that is not passed on.
    internal fun track(device: MemorySegment, code: Int, pressed: Boolean): Boolean {
        val keys = inputDevices.held.getOrPut(device.address()) { HashSet() }
        return if (pressed) keys.add(code) else keys.remove(code)
    }
}
