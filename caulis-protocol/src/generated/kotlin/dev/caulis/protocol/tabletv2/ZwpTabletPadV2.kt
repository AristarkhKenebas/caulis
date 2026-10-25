// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.tabletv2

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

/**
 * A set of buttons, rings, strips and dials
 *
 * A pad device is a set of buttons, rings, strips and dials
 * usually physically present on the tablet device itself. Some
 * exceptions exist where the pad device is physically detached, e.g. the
 * Wacom ExpressKey Remote.
 *
 * Pad devices have no axes that control the cursor and are generally
 * auxiliary devices to the tool devices used on the tablet surface.
 *
 * A pad device has a number of static characteristics, e.g. the number
 * of rings. These capabilities are sent in an event sequence after the
 * zwp_tablet_seat_v2.pad_added event before any actual events from this pad.
 * This initial event sequence is terminated by a zwp_tablet_pad_v2.done
 * event.
 *
 * All pad features (buttons, rings, strips and dials) are logically divided into
 * groups and all pads have at least one group. The available groups are
 * notified through the zwp_tablet_pad_v2.group event; the compositor will
 * emit one event per group before emitting zwp_tablet_pad_v2.done.
 *
 * Groups may have multiple modes. Modes allow clients to map multiple
 * actions to a single pad feature. Only one mode can be active per group,
 * although different groups may have different active modes.
 */
public class ZwpTabletPadV2 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpTabletPadV2> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_tablet_pad_v2. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Set compositor feedback
         *
         * Requests the compositor to use the provided feedback string
         * associated with this button. This request should be issued immediately
         * after a zwp_tablet_pad_group_v2.mode_switch event from the corresponding
         * group is received, or whenever a button is mapped to a different
         * action. See zwp_tablet_pad_group_v2.mode_switch for more details.
         *
         * Clients are encouraged to provide context-aware descriptions for
         * the actions associated with each button, and compositors may use
         * this information to offer visual feedback on the button layout
         * (e.g. on-screen displays).
         *
         * Button indices start at 0. Setting the feedback string on a button
         * that is reserved by the compositor (i.e. not belonging to any
         * zwp_tablet_pad_group_v2) does not generate an error but the compositor
         * is free to ignore the request.
         *
         * The provided string 'description' is a UTF-8 encoded string to be
         * associated with this ring, and is considered user-visible; general
         * internationalization rules apply.
         *
         * The serial argument will be that of the last
         * zwp_tablet_pad_group_v2.mode_switch event received for the group of this
         * button. Requests providing other serials than the most recent one will
         * be ignored.
         */
        public fun setFeedback(resource: ZwpTabletPadV2, button: Int, description: String, serial: Int)

        /**
         * Destroy the pad object
         *
         * Destroy the zwp_tablet_pad_v2 object. Objects created from this object
         * are unaffected and should be destroyed separately.
         */
        public fun destroy(resource: ZwpTabletPadV2)
    }

    /**
     * Group announced
     *
     * Sent on zwp_tablet_pad_v2 initialization to announce available groups.
     * One event is sent for each pad group available.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_pad_v2.done event. At least one group will be announced.
     */
    public fun sendGroup(padGroup: ZwpTabletPadGroupV2) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "group", "pad_group=${Wire.show(padGroup)}")
        event(0).newId(padGroup).end()
    }

    /**
     * Path to the device
     *
     * A system-specific device path that indicates which device is behind
     * this zwp_tablet_pad_v2. This information may be used to gather additional
     * information about the device, e.g. through libwacom.
     *
     * The format of the path is unspecified, it may be a device node, a
     * sysfs path, or some other identifier. It is up to the client to
     * identify the string provided.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_pad_v2.done event.
     */
    public fun sendPath(path: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "path", "path=${Wire.show(path)}")
        event(1).string(path).end()
    }

    /**
     * Buttons announced
     *
     * Sent on zwp_tablet_pad_v2 initialization to announce the available
     * buttons.
     *
     * This event is sent in the initial burst of events before the
     * zwp_tablet_pad_v2.done event. This event is only sent when at least one
     * button is available.
     */
    public fun sendButtons(buttons: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "buttons", "buttons=${Wire.show(buttons)}")
        event(2).uint(buttons).end()
    }

    /**
     * Pad description event sequence complete
     *
     * This event signals the end of the initial burst of descriptive
     * events. A client may consider the static description of the pad to
     * be complete and finalize initialization of the pad.
     */
    public fun sendDone() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "done", "")
        event(3).end()
    }

    /**
     * Physical button state
     *
     * Sent whenever the physical state of a button changes.
     */
    public fun sendButton(time: Int, button: Int, state: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "button", "time=${Wire.show(time)}, button=${Wire.show(button)}, state=${Wire.show(state)}")
        event(4).uint(time).uint(button).uint(state).end()
    }

    /**
     * Enter event
     *
     * Notification that this pad is focused on the specified surface.
     */
    public fun sendEnter(serial: Int, tablet: ZwpTabletV2, surface: WlSurface) {
        if (!isAlive || !client.isConnected) return
        if (!tablet.isAlive) return
        if (!surface.isAlive) return
        if (Wire.tracing) Wire.event(this, "enter", "serial=${Wire.show(serial)}, tablet=${Wire.show(tablet)}, surface=${Wire.show(surface)}")
        event(5).uint(serial).obj(tablet).obj(surface).end()
    }

    /**
     * Leave event
     *
     * Notification that this pad is no longer focused on the specified
     * surface.
     */
    public fun sendLeave(serial: Int, surface: WlSurface) {
        if (!isAlive || !client.isConnected) return
        if (!surface.isAlive) return
        if (Wire.tracing) Wire.event(this, "leave", "serial=${Wire.show(serial)}, surface=${Wire.show(surface)}")
        event(6).uint(serial).obj(surface).end()
    }

    /**
     * Pad removed event
     *
     * Sent when the pad has been removed from the system. When a tablet
     * is removed its pad(s) will be removed too.
     *
     * When this event is received, the client must destroy all rings, strips
     * and groups that were offered by this pad, and issue zwp_tablet_pad_v2.destroy
     * the pad itself.
     */
    public fun sendRemoved() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "removed", "")
        event(7).end()
    }

    /**
     * Physical button state
     */
    public object ButtonState {
        /** the button is not pressed */
        public const val RELEASED: Int = 0
        /** the button is pressed */
        public const val PRESSED: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_tablet_pad_v2 is not implemented")
        when (opcode) {
            0 -> {
                val button = args.uint()
                val description = args.string()
                val serial = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_feedback", "button=${Wire.show(button)}, description=${Wire.show(description)}, serial=${Wire.show(serial)}")
                handler.setFeedback(this, button, description, serial)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_tablet_pad_v2 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpTabletPadV2>("zwp_tablet_pad_v2", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpTabletPadV2 = ZwpTabletPadV2(client, id, version)
    }
}
