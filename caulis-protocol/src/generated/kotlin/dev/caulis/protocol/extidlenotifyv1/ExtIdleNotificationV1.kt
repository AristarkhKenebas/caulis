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

/**
 * Idle notification
 *
 * This interface is used by the compositor to send idle notification events
 * to clients.
 *
 * Initially the notification object is not idle. The notification object
 * becomes idle when no user activity has happened for at least the timeout
 * duration, starting from the creation of the notification object. User
 * activity may include input events or a presence sensor, but is
 * compositor-specific.
 *
 * How this notification responds to idle inhibitors depends on how
 * it was constructed. If constructed from the
 * get_idle_notification request, then if an idle inhibitor is
 * active (e.g. another client has created a zwp_idle_inhibitor_v1
 * on a visible surface), the compositor must not make the
 * notification object idle. However, if constructed from the
 * get_input_idle_notification request, then idle inhibitors are
 * ignored, and only input from the user, e.g. from a keyboard or
 * mouse, counts as activity.
 *
 * When the notification object becomes idle, an idled event is sent. When
 * user activity starts again, the notification object stops being idle,
 * a resumed event is sent and the timeout is restarted.
 */
public class ExtIdleNotificationV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtIdleNotificationV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_idle_notification_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the notification object
         *
         * Destroy the notification object.
         */
        public fun destroy(resource: ExtIdleNotificationV1)
    }

    /**
     * Notification object is idle
     *
     * This event is sent when the notification object becomes idle.
     *
     * It's a compositor protocol error to send this event twice without a
     * resumed event in-between.
     */
    public fun sendIdled() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "idled", "")
        event(0).end()
    }

    /**
     * Notification object is no longer idle
     *
     * This event is sent when the notification object stops being idle.
     *
     * It's a compositor protocol error to send this event twice without an
     * idled event in-between. It's a compositor protocol error to send this
     * event prior to any idled event.
     */
    public fun sendResumed() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "resumed", "")
        event(1).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_idle_notification_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_idle_notification_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtIdleNotificationV1>("ext_idle_notification_v1", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ExtIdleNotificationV1 = ExtIdleNotificationV1(client, id, version)
    }
}
