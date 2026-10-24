// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.tabletv2

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Pad ring
 *
 * A circular interaction area, such as the touch ring on the Wacom Intuos
 * Pro series tablets.
 *
 * Events on a ring are logically grouped by the zwp_tablet_pad_ring_v2.frame
 * event.
 */
public class ZwpTabletPadRingV2 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpTabletPadRingV2> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Ring event source
         *
         * Source information for ring events.
         *
         * This event does not occur on its own. It is sent before a
         * zwp_tablet_pad_ring_v2.frame event and carries the source information
         * for all events within that frame.
         *
         * The source specifies how this event was generated. If the source is
         * zwp_tablet_pad_ring_v2.source.finger, a zwp_tablet_pad_ring_v2.stop event
         * will be sent when the user lifts the finger off the device.
         *
         * This event is optional. If the source is unknown for an interaction,
         * no event is sent.
         */
        public fun source(proxy: ZwpTabletPadRingV2, source: Int) {}

        /**
         * Angle changed
         *
         * Sent whenever the angle on a ring changes.
         *
         * The angle is provided in degrees clockwise from the logical
         * north of the ring in the pad's current rotation.
         */
        public fun angle(proxy: ZwpTabletPadRingV2, degrees: Double) {}

        /**
         * Interaction stopped
         *
         * Stop notification for ring events.
         *
         * For some zwp_tablet_pad_ring_v2.source types, a zwp_tablet_pad_ring_v2.stop
         * event is sent to notify a client that the interaction with the ring
         * has terminated. This enables the client to implement kinetic scrolling.
         * See the zwp_tablet_pad_ring_v2.source documentation for information on
         * when this event may be generated.
         *
         * Any zwp_tablet_pad_ring_v2.angle events with the same source after this
         * event should be considered as the start of a new interaction.
         */
        public fun stop(proxy: ZwpTabletPadRingV2) {}

        /**
         * End of a ring event sequence
         *
         * Indicates the end of a set of ring events that logically belong
         * together. A client is expected to accumulate the data in all events
         * within the frame before proceeding.
         *
         * All zwp_tablet_pad_ring_v2 events before a zwp_tablet_pad_ring_v2.frame event belong
         * logically together. For example, on termination of a finger interaction
         * on a ring the compositor will send a zwp_tablet_pad_ring_v2.source event,
         * a zwp_tablet_pad_ring_v2.stop event and a zwp_tablet_pad_ring_v2.frame event.
         *
         * A zwp_tablet_pad_ring_v2.frame event is sent for every logical event
         * group, even if the group only contains a single zwp_tablet_pad_ring_v2
         * event. Specifically, a client may get a sequence: angle, frame,
         * angle, frame, etc.
         */
        public fun frame(proxy: ZwpTabletPadRingV2, time: Int) {}
    }

    /**
     * Set compositor feedback
     *
     * Request that the compositor use the provided feedback string
     * associated with this ring. This request should be issued immediately
     * after a zwp_tablet_pad_group_v2.mode_switch event from the corresponding
     * group is received, or whenever the ring is mapped to a different
     * action. See zwp_tablet_pad_group_v2.mode_switch for more details.
     *
     * Clients are encouraged to provide context-aware descriptions for
     * the actions associated with the ring; compositors may use this
     * information to offer visual feedback about the button layout
     * (eg. on-screen displays).
     *
     * The provided string 'description' is a UTF-8 encoded string to be
     * associated with this ring, and is considered user-visible; general
     * internationalization rules apply.
     *
     * The serial argument will be that of the last
     * zwp_tablet_pad_group_v2.mode_switch event received for the group of this
     * ring. Requests providing other serials than the most recent one will be
     * ignored.
     */
    public fun setFeedback(description: String, serial: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_feedback", "description=${Wire.show(description)}, serial=${Wire.show(serial)}")
        request(0).string(description).uint(serial).end()
    }

    /**
     * Destroy the ring object
     *
     * This destroys the client's resource for this ring object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    /** ring axis source */
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
                if (Wire.tracing) Wire.received(this, "source", "source=${Wire.show(source)}")
                handler?.source(this, source)
            }
            1 -> {
                val degrees = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "angle", "degrees=${Wire.show(degrees)}")
                handler?.angle(this, degrees)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "stop", "")
                handler?.stop(this)
            }
            3 -> {
                val time = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "frame", "time=${Wire.show(time)}")
                handler?.frame(this, time)
            }
        }
    }

    public companion object : ProxyType<ZwpTabletPadRingV2>("zwp_tablet_pad_ring_v2", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpTabletPadRingV2 = ZwpTabletPadRingV2(connection, id, version)
    }
}
