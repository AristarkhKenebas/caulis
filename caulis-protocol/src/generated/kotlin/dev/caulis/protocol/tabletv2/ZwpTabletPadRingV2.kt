// Generated from tablet-v2.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.tabletv2

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Pad ring
 *
 * A circular interaction area, such as the touch ring on the Wacom Intuos
 * Pro series tablets.
 *
 * Events on a ring are logically grouped by the zwp_tablet_pad_ring_v2.frame
 * event.
 */
public class ZwpTabletPadRingV2 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpTabletPadRingV2> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_tablet_pad_ring_v2. */
    public var requests: Requests? = null

    public interface Requests {
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
        public fun setFeedback(resource: ZwpTabletPadRingV2, description: String, serial: Int)

        /**
         * Destroy the ring object
         *
         * This destroys the client's resource for this ring object.
         */
        public fun destroy(resource: ZwpTabletPadRingV2)
    }

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
    public fun sendSource(source: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(source).end()
    }

    /**
     * Angle changed
     *
     * Sent whenever the angle on a ring changes.
     *
     * The angle is provided in degrees clockwise from the logical
     * north of the ring in the pad's current rotation.
     */
    public fun sendAngle(degrees: Double) {
        if (!isAlive || !client.isConnected) return
        event(1).fixed(degrees).end()
    }

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
    public fun sendStop() {
        if (!isAlive || !client.isConnected) return
        event(2).end()
    }

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
    public fun sendFrame(time: Int) {
        if (!isAlive || !client.isConnected) return
        event(3).uint(time).end()
    }

    /**
     * Ring axis source
     */
    public object Source {
        /** finger */
        public const val FINGER: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_tablet_pad_ring_v2 is not implemented")
        when (opcode) {
            0 -> {
                val description = args.string()
                val serial = args.uint()
                args.end()
                handler.setFeedback(this, description, serial)
            }
            1 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_tablet_pad_ring_v2 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpTabletPadRingV2>("zwp_tablet_pad_ring_v2", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpTabletPadRingV2 = ZwpTabletPadRingV2(client, id, version)
    }
}
