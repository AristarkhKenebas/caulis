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
 * Output device
 *
 * A head is an output device. The difference between a wl_output object and
 * a head is that heads are advertised even if they are turned off. A head
 * object only advertises properties and cannot be used directly to change
 * them.
 *
 * A head has some read-only properties: modes, name, description and
 * physical_size. These cannot be changed by clients.
 *
 * Other properties can be updated via a wlr_output_configuration object.
 *
 * Properties sent via this interface are applied atomically via the
 * wlr_output_manager.done event. No guarantees are made regarding the order
 * in which properties are sent.
 */
public class ZwlrOutputHeadV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrOutputHeadV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_output_head_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the head object
         *
         * This request indicates that the client will no longer use this head
         * object.
         *
         * Since version 3.
         */
        public fun release(resource: ZwlrOutputHeadV1)
    }

    /**
     * Head name
     *
     * This event describes the head name.
     *
     * The naming convention is compositor defined, but limited to alphanumeric
     * characters and dashes (-). Each name is unique among all wlr_output_head
     * objects, but if a wlr_output_head object is destroyed the same name may
     * be reused later. The names will also remain consistent across sessions
     * with the same hardware and software configuration.
     *
     * Examples of names include 'HDMI-A-1', 'WL-1', 'X11-1', etc. However, do
     * not assume that the name is a reflection of an underlying DRM
     * connector, X11 connection, etc.
     *
     * If this head matches a wl_output, the wl_output.name event must report
     * the same name.
     *
     * The name event is sent after a wlr_output_head object is created. This
     * event is only sent once per object, and the name does not change over
     * the lifetime of the wlr_output_head object.
     */
    public fun sendName(name: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "name", "name=${Wire.show(name)}")
        event(0).string(name).end()
    }

    /**
     * Head description
     *
     * This event describes a human-readable description of the head.
     *
     * The description is a UTF-8 string with no convention defined for its
     * contents. Examples might include 'Foocorp 11" Display' or 'Virtual X11
     * output via :1'. However, do not assume that the name is a reflection of
     * the make, model, serial of the underlying DRM connector or the display
     * name of the underlying X11 connection, etc.
     *
     * If this head matches a wl_output, the wl_output.description event must
     * report the same name.
     *
     * The description event is sent after a wlr_output_head object is created.
     * This event is only sent once per object, and the description does not
     * change over the lifetime of the wlr_output_head object.
     */
    public fun sendDescription(description: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "description", "description=${Wire.show(description)}")
        event(1).string(description).end()
    }

    /**
     * Head physical size
     *
     * This event describes the physical size of the head. This event is only
     * sent if the head has a physical size (e.g. is not a projector or a
     * virtual device).
     *
     * The physical size event is sent after a wlr_output_head object is created. This
     * event is only sent once per object, and the physical size does not change over
     * the lifetime of the wlr_output_head object.
     */
    public fun sendPhysicalSize(width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "physical_size", "width=${Wire.show(width)}, height=${Wire.show(height)}")
        event(2).int(width).int(height).end()
    }

    /**
     * Introduce a mode
     *
     * This event introduces a mode for this head. It is sent once per
     * supported mode.
     */
    public fun sendMode(mode: ZwlrOutputModeV1) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "mode", "mode=${Wire.show(mode)}")
        event(3).newId(mode).end()
    }

    /**
     * Head is enabled or disabled
     *
     * This event describes whether the head is enabled. A disabled head is not
     * mapped to a region of the global compositor space.
     *
     * When a head is disabled, some properties (current_mode, position,
     * transform and scale) are irrelevant.
     */
    public fun sendEnabled(enabled: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "enabled", "enabled=${Wire.show(enabled)}")
        event(4).int(enabled).end()
    }

    /**
     * Current mode
     *
     * This event describes the mode currently in use for this head. It is only
     * sent if the output is enabled.
     */
    public fun sendCurrentMode(mode: ZwlrOutputModeV1) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "current_mode", "mode=${Wire.show(mode)}")
        event(5).obj(mode).end()
    }

    /**
     * Current position
     *
     * This events describes the position of the head in the global compositor
     * space. It is only sent if the output is enabled.
     */
    public fun sendPosition(x: Int, y: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "position", "x=${Wire.show(x)}, y=${Wire.show(y)}")
        event(6).int(x).int(y).end()
    }

    /**
     * Current transformation
     *
     * This event describes the transformation currently applied to the head.
     * It is only sent if the output is enabled.
     */
    public fun sendTransform(transform: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "transform", "transform=${Wire.show(transform)}")
        event(7).int(transform).end()
    }

    /**
     * Current scale
     *
     * This events describes the scale of the head in the global compositor
     * space. It is only sent if the output is enabled.
     */
    public fun sendScale(scale: Double) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "scale", "scale=${Wire.show(scale)}")
        event(8).fixed(scale).end()
    }

    /**
     * The head has disappeared
     *
     * This event indicates that the head is no longer available. The head
     * object becomes inert. Clients should send a destroy request and release
     * any resources associated with it.
     */
    public fun sendFinished() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "finished", "")
        event(9).end()
    }

    /**
     * Head manufacturer
     *
     * This event describes the manufacturer of the head.
     *
     * Together with the model and serial_number events the purpose is to
     * allow clients to recognize heads from previous sessions and for example
     * load head-specific configurations back.
     *
     * It is not guaranteed this event will be ever sent. A reason for that
     * can be that the compositor does not have information about the make of
     * the head or the definition of a make is not sensible in the current
     * setup, for example in a virtual session. Clients can still try to
     * identify the head by available information from other events but should
     * be aware that there is an increased risk of false positives.
     *
     * If sent, the make event is sent after a wlr_output_head object is
     * created and only sent once per object. The make does not change over
     * the lifetime of the wlr_output_head object.
     *
     * It is not recommended to display the make string in UI to users. For
     * that the string provided by the description event should be preferred.
     *
     * Since version 2.
     */
    public fun sendMake(make: String) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "make", "make=${Wire.show(make)}")
        event(10).string(make).end()
    }

    /**
     * Head model
     *
     * This event describes the model of the head.
     *
     * Together with the make and serial_number events the purpose is to
     * allow clients to recognize heads from previous sessions and for example
     * load head-specific configurations back.
     *
     * It is not guaranteed this event will be ever sent. A reason for that
     * can be that the compositor does not have information about the model of
     * the head or the definition of a model is not sensible in the current
     * setup, for example in a virtual session. Clients can still try to
     * identify the head by available information from other events but should
     * be aware that there is an increased risk of false positives.
     *
     * If sent, the model event is sent after a wlr_output_head object is
     * created and only sent once per object. The model does not change over
     * the lifetime of the wlr_output_head object.
     *
     * It is not recommended to display the model string in UI to users. For
     * that the string provided by the description event should be preferred.
     *
     * Since version 2.
     */
    public fun sendModel(model: String) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "model", "model=${Wire.show(model)}")
        event(11).string(model).end()
    }

    /**
     * Head serial number
     *
     * This event describes the serial number of the head.
     *
     * Together with the make and model events the purpose is to allow clients
     * to recognize heads from previous sessions and for example load head-
     * specific configurations back.
     *
     * It is not guaranteed this event will be ever sent. A reason for that
     * can be that the compositor does not have information about the serial
     * number of the head or the definition of a serial number is not sensible
     * in the current setup. Clients can still try to identify the head by
     * available information from other events but should be aware that there
     * is an increased risk of false positives.
     *
     * If sent, the serial number event is sent after a wlr_output_head object
     * is created and only sent once per object. The serial number does not
     * change over the lifetime of the wlr_output_head object.
     *
     * It is not recommended to display the serial_number string in UI to
     * users. For that the string provided by the description event should be
     * preferred.
     *
     * Since version 2.
     */
    public fun sendSerialNumber(serialNumber: String) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "serial_number", "serial_number=${Wire.show(serialNumber)}")
        event(12).string(serialNumber).end()
    }

    /**
     * Current adaptive sync state
     *
     * This event describes whether adaptive sync is currently enabled for
     * the head or not. Adaptive sync is also known as Variable Refresh
     * Rate or VRR.
     *
     * Since version 4.
     */
    public fun sendAdaptiveSync(state: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 4) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "adaptive_sync", "state=${Wire.show(state)}")
        event(13).uint(state).end()
    }

    public object AdaptiveSyncState {
        /** adaptive sync is disabled */
        public const val DISABLED: Int = 0
        /** adaptive sync is enabled */
        public const val ENABLED: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_output_head_v1 is not implemented")
        when (opcode) {
            0 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_output_head_v1.release needs version 3")
                args.end()
                if (Wire.tracing) Wire.request(this, "release", "")
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_output_head_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrOutputHeadV1>("zwlr_output_head_v1", 4) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrOutputHeadV1 = ZwlrOutputHeadV1(client, id, version)
    }
}
