// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.tabletv2

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Pad strip
 *
 * A linear interaction area, such as the strips found in Wacom Cintiq
 * models.
 *
 * Events on a strip are logically grouped by the zwp_tablet_pad_strip_v2.frame
 * event.
 */
public class ZwpTabletPadStripV2 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpTabletPadStripV2> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Strip event source
         *
         * Source information for strip events.
         *
         * This event does not occur on its own. It is sent before a
         * zwp_tablet_pad_strip_v2.frame event and carries the source information
         * for all events within that frame.
         *
         * The source specifies how this event was generated. If the source is
         * zwp_tablet_pad_strip_v2.source.finger, a zwp_tablet_pad_strip_v2.stop event
         * will be sent when the user lifts their finger off the device.
         *
         * This event is optional. If the source is unknown for an interaction,
         * no event is sent.
         */
        public fun source(proxy: ZwpTabletPadStripV2, source: Int) {}

        /**
         * Position changed
         *
         * Sent whenever the position on a strip changes.
         *
         * The position is normalized to a range of [0, 65535], the 0-value
         * represents the top-most and/or left-most position of the strip in
         * the pad's current rotation.
         */
        public fun position(proxy: ZwpTabletPadStripV2, position: Int) {}

        /**
         * Interaction stopped
         *
         * Stop notification for strip events.
         *
         * For some zwp_tablet_pad_strip_v2.source types, a zwp_tablet_pad_strip_v2.stop
         * event is sent to notify a client that the interaction with the strip
         * has terminated. This enables the client to implement kinetic
         * scrolling. See the zwp_tablet_pad_strip_v2.source documentation for
         * information on when this event may be generated.
         *
         * Any zwp_tablet_pad_strip_v2.position events with the same source after this
         * event should be considered as the start of a new interaction.
         */
        public fun stop(proxy: ZwpTabletPadStripV2) {}

        /**
         * End of a strip event sequence
         *
         * Indicates the end of a set of events that represent one logical
         * hardware strip event. A client is expected to accumulate the data
         * in all events within the frame before proceeding.
         *
         * All zwp_tablet_pad_strip_v2 events before a zwp_tablet_pad_strip_v2.frame event belong
         * logically together. For example, on termination of a finger interaction
         * on a strip the compositor will send a zwp_tablet_pad_strip_v2.source event,
         * a zwp_tablet_pad_strip_v2.stop event and a zwp_tablet_pad_strip_v2.frame
         * event.
         *
         * A zwp_tablet_pad_strip_v2.frame event is sent for every logical event
         * group, even if the group only contains a single zwp_tablet_pad_strip_v2
         * event. Specifically, a client may get a sequence: position, frame,
         * position, frame, etc.
         */
        public fun frame(proxy: ZwpTabletPadStripV2, time: Int) {}
    }

    /**
     * Set compositor feedback
     *
     * Requests the compositor to use the provided feedback string
     * associated with this strip. This request should be issued immediately
     * after a zwp_tablet_pad_group_v2.mode_switch event from the corresponding
     * group is received, or whenever the strip is mapped to a different
     * action. See zwp_tablet_pad_group_v2.mode_switch for more details.
     *
     * Clients are encouraged to provide context-aware descriptions for
     * the actions associated with the strip, and compositors may use this
     * information to offer visual feedback about the button layout
     * (eg. on-screen displays).
     *
     * The provided string 'description' is a UTF-8 encoded string to be
     * associated with this ring, and is considered user-visible; general
     * internationalization rules apply.
     *
     * The serial argument will be that of the last
     * zwp_tablet_pad_group_v2.mode_switch event received for the group of this
     * strip. Requests providing other serials than the most recent one will be
     * ignored.
     */
    public fun setFeedback(description: String, serial: Int): Unit {
        request(0).string(description).uint(serial).end()
    }

    /**
     * Destroy the strip object
     *
     * This destroys the client's resource for this strip object.
     */
    public fun destroy(): Unit {
        request(1).end()
        connection.destroyProxy(this)
    }

    /** strip axis source */
    public object Source {
        /** finger */
        public const val FINGER: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val source = args.uint()
                args.end()
                handler?.source(this, source)
            }
            1 -> {
                val position = args.uint()
                args.end()
                handler?.position(this, position)
            }
            2 -> {
                args.end()
                handler?.stop(this)
            }
            3 -> {
                val time = args.uint()
                args.end()
                handler?.frame(this, time)
            }
        }
    }

    public companion object : ProxyType<ZwpTabletPadStripV2>("zwp_tablet_pad_strip_v2", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpTabletPadStripV2 = ZwpTabletPadStripV2(connection, id, version)
    }
}
