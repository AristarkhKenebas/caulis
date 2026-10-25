// Generated from wlr-output-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlroutputmanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Output configuration
 *
 * This object is used by the client to describe a full output configuration.
 *
 * First, the client needs to setup the output configuration. Each head can
 * be either enabled (and configured) or disabled. It is a protocol error to
 * send two enable_head or disable_head requests with the same head. It is a
 * protocol error to omit a head in a configuration.
 *
 * Then, the client can apply or test the configuration. The compositor will
 * then reply with a succeeded, failed or cancelled event. Finally the client
 * should destroy the configuration object.
 */
public class ZwlrOutputConfigurationV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrOutputConfigurationV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Configuration changes succeeded
         *
         * Sent after the compositor has successfully applied the changes or
         * tested them.
         *
         * Upon receiving this event, the client should destroy this object.
         *
         * If the current configuration has changed, events to describe the changes
         * will be sent followed by a wlr_output_manager.done event.
         */
        public fun succeeded(proxy: ZwlrOutputConfigurationV1) {}

        /**
         * Configuration changes failed
         *
         * Sent if the compositor rejects the changes or failed to apply them. The
         * compositor should revert any changes made by the apply request that
         * triggered this event.
         *
         * Upon receiving this event, the client should destroy this object.
         */
        public fun failed(proxy: ZwlrOutputConfigurationV1) {}

        /**
         * Configuration has been cancelled
         *
         * Sent if the compositor cancels the configuration because the state of an
         * output changed and the client has outdated information (e.g. after an
         * output has been hotplugged).
         *
         * The client can create a new configuration with a newer serial and try
         * again.
         *
         * Upon receiving this event, the client should destroy this object.
         */
        public fun cancelled(proxy: ZwlrOutputConfigurationV1) {}
    }

    /**
     * Enable and configure a head
     *
     * Enable a head. This request creates a head configuration object that can
     * be used to change the head's properties.
     */
    public fun enableHead(head: ZwlrOutputHeadV1): ZwlrOutputConfigurationHeadV1 {
        val created = connection.createProxy(ZwlrOutputConfigurationHeadV1, this.version)
        if (Wire.tracing) Wire.sent(this, "enable_head", "id=${Wire.show(created)}, head=${Wire.show(head)}")
        request(0).newId(created).obj(head).end()
        return created
    }

    /**
     * Disable a head
     *
     * Disable a head.
     */
    public fun disableHead(head: ZwlrOutputHeadV1): Unit {
        if (Wire.tracing) Wire.sent(this, "disable_head", "head=${Wire.show(head)}")
        request(1).obj(head).end()
    }

    /**
     * Apply the configuration
     *
     * Apply the new output configuration.
     *
     * In case the configuration is successfully applied, there is no guarantee
     * that the new output state matches completely the requested
     * configuration. For instance, a compositor might round the scale if it
     * doesn't support fractional scaling.
     *
     * After this request has been sent, the compositor must respond with an
     * succeeded, failed or cancelled event. Sending a request that isn't the
     * destructor is a protocol error.
     */
    public fun apply(): Unit {
        if (Wire.tracing) Wire.sent(this, "apply", "")
        request(2).end()
    }

    /**
     * Test the configuration
     *
     * Test the new output configuration. The configuration won't be applied,
     * but will only be validated.
     *
     * Even if the compositor succeeds to test a configuration, applying it may
     * fail.
     *
     * After this request has been sent, the compositor must respond with an
     * succeeded, failed or cancelled event. Sending a request that isn't the
     * destructor is a protocol error.
     */
    public fun test(): Unit {
        if (Wire.tracing) Wire.sent(this, "test", "")
        request(3).end()
    }

    /**
     * Destroy the output configuration
     *
     * Using this request a client can tell the compositor that it is not going
     * to use the configuration object anymore. Any changes to the outputs
     * that have not been applied will be discarded.
     *
     * This request also destroys wlr_output_configuration_head objects created
     * via this object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(4).end()
        connection.destroyProxy(this)
    }

    public object Error {
        /** head has been configured twice */
        public const val ALREADY_CONFIGURED_HEAD: Int = 1
        /** head has not been configured */
        public const val UNCONFIGURED_HEAD: Int = 2
        /** request sent after configuration has been applied or tested */
        public const val ALREADY_USED: Int = 3
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "succeeded", "")
                handler?.succeeded(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "failed", "")
                handler?.failed(this)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "cancelled", "")
                handler?.cancelled(this)
            }
        }
    }

    public companion object : ProxyType<ZwlrOutputConfigurationV1>("zwlr_output_configuration_v1", 4) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrOutputConfigurationV1 = ZwlrOutputConfigurationV1(connection, id, version)
    }
}
