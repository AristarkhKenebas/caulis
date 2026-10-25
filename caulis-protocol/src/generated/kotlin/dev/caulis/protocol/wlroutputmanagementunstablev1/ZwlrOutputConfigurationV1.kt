// Generated from wlr-output-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlroutputmanagementunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

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
public class ZwlrOutputConfigurationV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrOutputConfigurationV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_output_configuration_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Enable and configure a head
         *
         * Enable a head. This request creates a head configuration object that can
         * be used to change the head's properties.
         */
        public fun enableHead(resource: ZwlrOutputConfigurationV1, id: ZwlrOutputConfigurationHeadV1, head: ZwlrOutputHeadV1)

        /**
         * Disable a head
         *
         * Disable a head.
         */
        public fun disableHead(resource: ZwlrOutputConfigurationV1, head: ZwlrOutputHeadV1)

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
        public fun apply(resource: ZwlrOutputConfigurationV1)

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
        public fun test(resource: ZwlrOutputConfigurationV1)

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
        public fun destroy(resource: ZwlrOutputConfigurationV1)
    }

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
    public fun sendSucceeded() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "succeeded", "")
        event(0).end()
    }

    /**
     * Configuration changes failed
     *
     * Sent if the compositor rejects the changes or failed to apply them. The
     * compositor should revert any changes made by the apply request that
     * triggered this event.
     *
     * Upon receiving this event, the client should destroy this object.
     */
    public fun sendFailed() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "failed", "")
        event(1).end()
    }

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
    public fun sendCancelled() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "cancelled", "")
        event(2).end()
    }

    public object Error {
        /** head has been configured twice */
        public const val ALREADY_CONFIGURED_HEAD: Int = 1
        /** head has not been configured */
        public const val UNCONFIGURED_HEAD: Int = 2
        /** request sent after configuration has been applied or tested */
        public const val ALREADY_USED: Int = 3
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_output_configuration_v1 is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val head = args.obj(ZwlrOutputHeadV1::class.java)
                args.end()
                val id = client.register(ZwlrOutputConfigurationHeadV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "enable_head", "id=${Wire.show(id)}, head=${Wire.show(head)}")
                handler.enableHead(this, id, head)
            }
            1 -> {
                val head = args.obj(ZwlrOutputHeadV1::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "disable_head", "head=${Wire.show(head)}")
                handler.disableHead(this, head)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "apply", "")
                handler.apply(this)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "test", "")
                handler.test(this)
            }
            4 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_output_configuration_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrOutputConfigurationV1>("zwlr_output_configuration_v1", 4) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrOutputConfigurationV1 = ZwlrOutputConfigurationV1(client, id, version)
    }
}
