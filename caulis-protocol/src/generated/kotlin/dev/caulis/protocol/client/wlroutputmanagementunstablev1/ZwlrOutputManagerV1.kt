// Generated from wlr-output-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlroutputmanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Output device configuration manager
 *
 * This interface is a manager that allows reading and writing the current
 * output device configuration.
 *
 * Output devices that display pixels (e.g. a physical monitor or a virtual
 * output in a window) are represented as heads. Heads cannot be created nor
 * destroyed by the client, but they can be enabled or disabled and their
 * properties can be changed. Each head may have one or more available modes.
 *
 * Whenever a head appears (e.g. a monitor is plugged in), it will be
 * advertised via the head event. Immediately after the output manager is
 * bound, all current heads are advertised.
 *
 * Whenever a head's properties change, the relevant wlr_output_head events
 * will be sent. Not all head properties will be sent: only properties that
 * have changed need to.
 *
 * Whenever a head disappears (e.g. a monitor is unplugged), a
 * wlr_output_head.finished event will be sent.
 *
 * After one or more heads appear, change or disappear, the done event will
 * be sent. It carries a serial which can be used in a create_configuration
 * request to update heads properties.
 *
 * The information obtained from this protocol should only be used for output
 * configuration purposes. This protocol is not designed to be a generic
 * output property advertisement protocol for regular clients. Instead,
 * protocols such as xdg-output should be used.
 */
public class ZwlrOutputManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrOutputManagerV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Introduce a new head
         *
         * This event introduces a new head. This happens whenever a new head
         * appears (e.g. a monitor is plugged in) or after the output manager is
         * bound.
         */
        public fun head(proxy: ZwlrOutputManagerV1, head: ZwlrOutputHeadV1) {}

        /**
         * Sent all information about current configuration
         *
         * This event is sent after all information has been sent after binding to
         * the output manager object and after any subsequent changes. This applies
         * to child head and mode objects as well. In other words, this event is
         * sent whenever a head or mode is created or destroyed and whenever one of
         * their properties has been changed. Not all state is re-sent each time
         * the current configuration changes: only the actual changes are sent.
         *
         * This allows changes to the output configuration to be seen as atomic,
         * even if they happen via multiple events.
         *
         * A serial is sent to be used in a future create_configuration request.
         */
        public fun done(proxy: ZwlrOutputManagerV1, serial: Int) {}

        /**
         * The compositor has finished with the manager
         *
         * This event indicates that the compositor is done sending manager events.
         * The compositor will destroy the object immediately after sending this
         * event, so it will become invalid and the client should release any
         * resources associated with it.
         */
        public fun finished(proxy: ZwlrOutputManagerV1) {}
    }

    /**
     * Create a new output configuration object
     *
     * Create a new output configuration object. This allows to update head
     * properties.
     */
    public fun createConfiguration(serial: Int): ZwlrOutputConfigurationV1 {
        val created = connection.createProxy(ZwlrOutputConfigurationV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_configuration", "id=${Wire.show(created)}, serial=${Wire.show(serial)}")
        request(0).newId(created).uint(serial).end()
        return created
    }

    /**
     * Stop sending events
     *
     * Indicates the client no longer wishes to receive events for output
     * configuration changes. However the compositor may emit further events,
     * until the finished event is emitted.
     *
     * The client must not send any more requests after this one.
     */
    public fun stop(): Unit {
        if (Wire.tracing) Wire.sent(this, "stop", "")
        request(1).end()
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val head = args.newId(ZwlrOutputHeadV1, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "head", "head=${Wire.show(head)}")
                handler?.head(this, head)
            }
            1 -> {
                val serial = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "done", "serial=${Wire.show(serial)}")
                handler?.done(this, serial)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "finished", "")
                handler?.finished(this)
            }
        }
    }

    public companion object : ProxyType<ZwlrOutputManagerV1>("zwlr_output_manager_v1", 4) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrOutputManagerV1 = ZwlrOutputManagerV1(connection, id, version)
    }
}
