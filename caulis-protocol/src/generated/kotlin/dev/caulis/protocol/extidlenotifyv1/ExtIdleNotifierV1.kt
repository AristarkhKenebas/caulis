// Generated from ext-idle-notify-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extidlenotifyv1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSeat

/**
 * Idle notification manager
 *
 * This interface allows clients to monitor user idle status.
 *
 * After binding to this global, clients can create ext_idle_notification_v1
 * objects to get notified when the user is idle for a given amount of time.
 */
public class ExtIdleNotifierV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtIdleNotifierV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_idle_notifier_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the manager
         *
         * Destroy the manager object. All objects created via this interface
         * remain valid.
         */
        public fun destroy(resource: ExtIdleNotifierV1)

        /**
         * Create a notification object
         *
         * Create a new idle notification object.
         *
         * The notification object has a minimum timeout duration and is tied to a
         * seat. The client will be notified if the seat is inactive for at least
         * the provided timeout. See ext_idle_notification_v1 for more details.
         *
         * A zero timeout is valid and means the client wants to be notified as
         * soon as possible when the seat is inactive.
         */
        public fun getIdleNotification(resource: ExtIdleNotifierV1, id: ExtIdleNotificationV1, timeout: Int, seat: WlSeat)

        /**
         * Create a notification object
         *
         * Create a new idle notification object to track input from the
         * user, such as keyboard and mouse movement. Because this object is
         * meant to track user input alone, it ignores idle inhibitors.
         *
         * The notification object has a minimum timeout duration and is tied to a
         * seat. The client will be notified if the seat is inactive for at least
         * the provided timeout. See ext_idle_notification_v1 for more details.
         *
         * A zero timeout is valid and means the client wants to be notified as
         * soon as possible when the seat is inactive.
         *
         * Since version 2.
         */
        public fun getInputIdleNotification(resource: ExtIdleNotifierV1, id: ExtIdleNotificationV1, timeout: Int, seat: WlSeat)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_idle_notifier_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val timeout = args.uint()
                val seat = args.obj(WlSeat::class.java)
                args.end()
                val id = client.register(ExtIdleNotificationV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_idle_notification", "id=${Wire.show(id)}, timeout=${Wire.show(timeout)}, seat=${Wire.show(seat)}")
                handler.getIdleNotification(this, id, timeout, seat)
            }
            2 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "ext_idle_notifier_v1.get_input_idle_notification needs version 2")
                val idId = args.newId()
                val timeout = args.uint()
                val seat = args.obj(WlSeat::class.java)
                args.end()
                val id = client.register(ExtIdleNotificationV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_input_idle_notification", "id=${Wire.show(id)}, timeout=${Wire.show(timeout)}, seat=${Wire.show(seat)}")
                handler.getInputIdleNotification(this, id, timeout, seat)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_idle_notifier_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtIdleNotifierV1>("ext_idle_notifier_v1", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ExtIdleNotifierV1 = ExtIdleNotifierV1(client, id, version)
    }
}
