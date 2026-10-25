// Generated from wlr-output-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlroutputmanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Head configuration
 *
 * This object is used by the client to update a single head's configuration.
 *
 * It is a protocol error to set the same property twice.
 */
public class ZwlrOutputConfigurationHeadV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrOutputConfigurationHeadV1> get() = Companion

    /**
     * Set the mode
     *
     * This request sets the head's mode.
     */
    public fun setMode(mode: ZwlrOutputModeV1): Unit {
        if (Wire.tracing) Wire.sent(this, "set_mode", "mode=${Wire.show(mode)}")
        request(0).obj(mode).end()
    }

    /**
     * Set a custom mode
     *
     * This request assigns a custom mode to the head. The size is given in
     * physical hardware units of the output device. If set to zero, the
     * refresh rate is unspecified.
     *
     * It is a protocol error to set both a mode and a custom mode.
     */
    public fun setCustomMode(width: Int, height: Int, refresh: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_custom_mode", "width=${Wire.show(width)}, height=${Wire.show(height)}, refresh=${Wire.show(refresh)}")
        request(1).int(width).int(height).int(refresh).end()
    }

    /**
     * Set the position
     *
     * This request sets the head's position in the global compositor space.
     */
    public fun setPosition(x: Int, y: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_position", "x=${Wire.show(x)}, y=${Wire.show(y)}")
        request(2).int(x).int(y).end()
    }

    /**
     * Set the transform
     *
     * This request sets the head's transform.
     */
    public fun setTransform(transform: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_transform", "transform=${Wire.show(transform)}")
        request(3).int(transform).end()
    }

    /**
     * Set the scale
     *
     * This request sets the head's scale.
     */
    public fun setScale(scale: Double): Unit {
        if (Wire.tracing) Wire.sent(this, "set_scale", "scale=${Wire.show(scale)}")
        request(4).fixed(scale).end()
    }

    /**
     * Enable/disable adaptive sync
     *
     * This request enables/disables adaptive sync. Adaptive sync is also
     * known as Variable Refresh Rate or VRR.
     *
     * Since version 4.
     */
    public fun setAdaptiveSync(state: Int): Unit {
        check(version >= 4) { "zwlr_output_configuration_head_v1.set_adaptive_sync needs version 4, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "set_adaptive_sync", "state=${Wire.show(state)}")
        request(5).uint(state).end()
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

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwlrOutputConfigurationHeadV1>("zwlr_output_configuration_head_v1", 4) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrOutputConfigurationHeadV1 = ZwlrOutputConfigurationHeadV1(connection, id, version)
    }
}
