// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.tabletv2

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

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
public class ZwpTabletPadV2 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpTabletPadV2> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Group announced
         *
         * Sent on zwp_tablet_pad_v2 initialization to announce available groups.
         * One event is sent for each pad group available.
         *
         * This event is sent in the initial burst of events before the
         * zwp_tablet_pad_v2.done event. At least one group will be announced.
         */
        public fun group(proxy: ZwpTabletPadV2, padGroup: ZwpTabletPadGroupV2) {}

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
        public fun path(proxy: ZwpTabletPadV2, path: String) {}

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
        public fun buttons(proxy: ZwpTabletPadV2, buttons: Int) {}

        /**
         * Pad description event sequence complete
         *
         * This event signals the end of the initial burst of descriptive
         * events. A client may consider the static description of the pad to
         * be complete and finalize initialization of the pad.
         */
        public fun done(proxy: ZwpTabletPadV2) {}

        /**
         * Physical button state
         *
         * Sent whenever the physical state of a button changes.
         */
        public fun button(proxy: ZwpTabletPadV2, time: Int, button: Int, state: Int) {}

        /**
         * Enter event
         *
         * Notification that this pad is focused on the specified surface.
         */
        public fun enter(proxy: ZwpTabletPadV2, serial: Int, tablet: ZwpTabletV2, surface: WlSurface) {}

        /**
         * Leave event
         *
         * Notification that this pad is no longer focused on the specified
         * surface.
         */
        public fun leave(proxy: ZwpTabletPadV2, serial: Int, surface: WlSurface) {}

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
        public fun removed(proxy: ZwpTabletPadV2) {}
    }

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
    public fun setFeedback(button: Int, description: String, serial: Int): Unit {
        request(0).uint(button).string(description).uint(serial).end()
    }

    /**
     * Destroy the pad object
     *
     * Destroy the zwp_tablet_pad_v2 object. Objects created from this object
     * are unaffected and should be destroyed separately.
     */
    public fun destroy(): Unit {
        request(1).end()
        connection.destroyProxy(this)
    }

    /** physical button state */
    public object ButtonState {
        /** the button is not pressed */
        public const val RELEASED: Int = 0
        /** the button is pressed */
        public const val PRESSED: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val padGroup = args.newId(ZwpTabletPadGroupV2, version)
                args.end()
                handler?.group(this, padGroup)
            }
            1 -> {
                val path = args.string()
                args.end()
                handler?.path(this, path)
            }
            2 -> {
                val buttons = args.uint()
                args.end()
                handler?.buttons(this, buttons)
            }
            3 -> {
                args.end()
                handler?.done(this)
            }
            4 -> {
                val time = args.uint()
                val button = args.uint()
                val state = args.uint()
                args.end()
                handler?.button(this, time, button, state)
            }
            5 -> {
                val serial = args.uint()
                val tablet = args.obj(ZwpTabletV2::class.java)
                val surface = args.obj(WlSurface::class.java)
                args.end()
                handler?.enter(this, serial, tablet, surface)
            }
            6 -> {
                val serial = args.uint()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                handler?.leave(this, serial, surface)
            }
            7 -> {
                args.end()
                handler?.removed(this)
            }
        }
    }

    public companion object : ProxyType<ZwpTabletPadV2>("zwp_tablet_pad_v2", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpTabletPadV2 = ZwpTabletPadV2(connection, id, version)
    }
}
