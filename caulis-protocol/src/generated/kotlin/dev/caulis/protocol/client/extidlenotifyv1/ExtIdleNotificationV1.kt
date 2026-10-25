// Generated from ext-idle-notify-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extidlenotifyv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

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
public class ExtIdleNotificationV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtIdleNotificationV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Notification object is idle
         *
         * This event is sent when the notification object becomes idle.
         *
         * It's a compositor protocol error to send this event twice without a
         * resumed event in-between.
         */
        public fun idled(proxy: ExtIdleNotificationV1) {}

        /**
         * Notification object is no longer idle
         *
         * This event is sent when the notification object stops being idle.
         *
         * It's a compositor protocol error to send this event twice without an
         * idled event in-between. It's a compositor protocol error to send this
         * event prior to any idled event.
         */
        public fun resumed(proxy: ExtIdleNotificationV1) {}
    }

    /**
     * Destroy the notification object
     *
     * Destroy the notification object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "idled", "")
                handler?.idled(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "resumed", "")
                handler?.resumed(this)
            }
        }
    }

    public companion object : ProxyType<ExtIdleNotificationV1>("ext_idle_notification_v1", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtIdleNotificationV1 = ExtIdleNotificationV1(connection, id, version)
    }
}
