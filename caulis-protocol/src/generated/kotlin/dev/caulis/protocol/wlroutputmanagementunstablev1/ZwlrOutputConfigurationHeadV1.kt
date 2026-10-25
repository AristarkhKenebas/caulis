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
 * Head configuration
 *
 * This object is used by the client to update a single head's configuration.
 *
 * It is a protocol error to set the same property twice.
 */
public class ZwlrOutputConfigurationHeadV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrOutputConfigurationHeadV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_output_configuration_head_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Set the mode
         *
         * This request sets the head's mode.
         */
        public fun setMode(resource: ZwlrOutputConfigurationHeadV1, mode: ZwlrOutputModeV1)

        /**
         * Set a custom mode
         *
         * This request assigns a custom mode to the head. The size is given in
         * physical hardware units of the output device. If set to zero, the
         * refresh rate is unspecified.
         *
         * It is a protocol error to set both a mode and a custom mode.
         */
        public fun setCustomMode(resource: ZwlrOutputConfigurationHeadV1, width: Int, height: Int, refresh: Int)

        /**
         * Set the position
         *
         * This request sets the head's position in the global compositor space.
         */
        public fun setPosition(resource: ZwlrOutputConfigurationHeadV1, x: Int, y: Int)

        /**
         * Set the transform
         *
         * This request sets the head's transform.
         */
        public fun setTransform(resource: ZwlrOutputConfigurationHeadV1, transform: Int)

        /**
         * Set the scale
         *
         * This request sets the head's scale.
         */
        public fun setScale(resource: ZwlrOutputConfigurationHeadV1, scale: Double)

        /**
         * Enable/disable adaptive sync
         *
         * This request enables/disables adaptive sync. Adaptive sync is also
         * known as Variable Refresh Rate or VRR.
         *
         * Since version 4.
         */
        public fun setAdaptiveSync(resource: ZwlrOutputConfigurationHeadV1, state: Int)
    }

    public object Error {
        /** property has already been set */
        public const val ALREADY_SET: Int = 1
        /** mode doesn't belong to head */
        public const val INVALID_MODE: Int = 2
        /** mode is invalid */
        public const val INVALID_CUSTOM_MODE: Int = 3
        /** transform value outside enum */
        public const val INVALID_TRANSFORM: Int = 4
        /** scale negative or zero */
        public const val INVALID_SCALE: Int = 5
        /** invalid enum value used in the set_adaptive_sync request */
        public const val INVALID_ADAPTIVE_SYNC_STATE: Int = 6
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_output_configuration_head_v1 is not implemented")
        when (opcode) {
            0 -> {
                val mode = args.obj(ZwlrOutputModeV1::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_mode", "mode=${Wire.show(mode)}")
                handler.setMode(this, mode)
            }
            1 -> {
                val width = args.int()
                val height = args.int()
                val refresh = args.int()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_custom_mode", "width=${Wire.show(width)}, height=${Wire.show(height)}, refresh=${Wire.show(refresh)}")
                handler.setCustomMode(this, width, height, refresh)
            }
            2 -> {
                val x = args.int()
                val y = args.int()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_position", "x=${Wire.show(x)}, y=${Wire.show(y)}")
                handler.setPosition(this, x, y)
            }
            3 -> {
                val transform = args.int()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_transform", "transform=${Wire.show(transform)}")
                handler.setTransform(this, transform)
            }
            4 -> {
                val scale = args.fixed()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_scale", "scale=${Wire.show(scale)}")
                handler.setScale(this, scale)
            }
            5 -> {
                if (version < 4) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_output_configuration_head_v1.set_adaptive_sync needs version 4")
                val state = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_adaptive_sync", "state=${Wire.show(state)}")
                handler.setAdaptiveSync(this, state)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_output_configuration_head_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrOutputConfigurationHeadV1>("zwlr_output_configuration_head_v1", 4) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrOutputConfigurationHeadV1 = ZwlrOutputConfigurationHeadV1(client, id, version)
    }
}
