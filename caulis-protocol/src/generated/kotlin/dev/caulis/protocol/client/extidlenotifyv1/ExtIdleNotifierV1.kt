// Generated from ext-idle-notify-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extidlenotifyv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSeat

/**
 * Idle notification manager
 *
 * This interface allows clients to monitor user idle status.
 *
 * After binding to this global, clients can create ext_idle_notification_v1
 * objects to get notified when the user is idle for a given amount of time.
 */
public class ExtIdleNotifierV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtIdleNotifierV1> get() = Companion

    /**
     * Destroy the manager
     *
     * Destroy the manager object. All objects created via this interface
     * remain valid.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

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
    public fun getIdleNotification(timeout: Int, seat: WlSeat): ExtIdleNotificationV1 {
        val created = connection.createProxy(ExtIdleNotificationV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_idle_notification", "id=${Wire.show(created)}, timeout=${Wire.show(timeout)}, seat=${Wire.show(seat)}")
        request(1).newId(created).uint(timeout).obj(seat).end()
        return created
    }

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
    public fun getInputIdleNotification(timeout: Int, seat: WlSeat): ExtIdleNotificationV1 {
        check(version >= 2) { "ext_idle_notifier_v1.get_input_idle_notification needs version 2, this object has $version" }
        val created = connection.createProxy(ExtIdleNotificationV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_input_idle_notification", "id=${Wire.show(created)}, timeout=${Wire.show(timeout)}, seat=${Wire.show(seat)}")
        request(2).newId(created).uint(timeout).obj(seat).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ExtIdleNotifierV1>("ext_idle_notifier_v1", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtIdleNotifierV1 = ExtIdleNotifierV1(connection, id, version)
    }
}
