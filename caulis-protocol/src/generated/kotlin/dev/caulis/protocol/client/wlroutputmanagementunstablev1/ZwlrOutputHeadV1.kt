// Generated from wlr-output-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlroutputmanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

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
public class ZwlrOutputHeadV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrOutputHeadV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun name(proxy: ZwlrOutputHeadV1, name: String) {}

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
        public fun description(proxy: ZwlrOutputHeadV1, description: String) {}

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
        public fun physicalSize(proxy: ZwlrOutputHeadV1, width: Int, height: Int) {}

        /**
         * Introduce a mode
         *
         * This event introduces a mode for this head. It is sent once per
         * supported mode.
         */
        public fun mode(proxy: ZwlrOutputHeadV1, mode: ZwlrOutputModeV1) {}

        /**
         * Head is enabled or disabled
         *
         * This event describes whether the head is enabled. A disabled head is not
         * mapped to a region of the global compositor space.
         *
         * When a head is disabled, some properties (current_mode, position,
         * transform and scale) are irrelevant.
         */
        public fun enabled(proxy: ZwlrOutputHeadV1, enabled: Int) {}

        /**
         * Current mode
         *
         * This event describes the mode currently in use for this head. It is only
         * sent if the output is enabled.
         */
        public fun currentMode(proxy: ZwlrOutputHeadV1, mode: ZwlrOutputModeV1) {}

        /**
         * Current position
         *
         * This events describes the position of the head in the global compositor
         * space. It is only sent if the output is enabled.
         */
        public fun position(proxy: ZwlrOutputHeadV1, x: Int, y: Int) {}

        /**
         * Current transformation
         *
         * This event describes the transformation currently applied to the head.
         * It is only sent if the output is enabled.
         */
        public fun transform(proxy: ZwlrOutputHeadV1, transform: Int) {}

        /**
         * Current scale
         *
         * This events describes the scale of the head in the global compositor
         * space. It is only sent if the output is enabled.
         */
        public fun scale(proxy: ZwlrOutputHeadV1, scale: Double) {}

        /**
         * The head has disappeared
         *
         * This event indicates that the head is no longer available. The head
         * object becomes inert. Clients should send a destroy request and release
         * any resources associated with it.
         */
        public fun finished(proxy: ZwlrOutputHeadV1) {}

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
        public fun make(proxy: ZwlrOutputHeadV1, make: String) {}

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
        public fun model(proxy: ZwlrOutputHeadV1, model: String) {}

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
        public fun serialNumber(proxy: ZwlrOutputHeadV1, serialNumber: String) {}

        /**
         * Current adaptive sync state
         *
         * This event describes whether adaptive sync is currently enabled for
         * the head or not. Adaptive sync is also known as Variable Refresh
         * Rate or VRR.
         *
         * Since version 4.
         */
        public fun adaptiveSync(proxy: ZwlrOutputHeadV1, state: Int) {}
    }

    /**
     * Destroy the head object
     *
     * This request indicates that the client will no longer use this head
     * object.
     *
     * Since version 3.
     */
    public fun release(): Unit {
        check(version >= 3) { "zwlr_output_head_v1.release needs version 3, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "release", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    public object AdaptiveSyncState {
        /** adaptive sync is disabled */
        public const val DISABLED: Int = 0
        /** adaptive sync is enabled */
        public const val ENABLED: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val name = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "name", "name=${Wire.show(name)}")
                handler?.name(this, name)
            }
            1 -> {
                val description = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "description", "description=${Wire.show(description)}")
                handler?.description(this, description)
            }
            2 -> {
                val width = args.int()
                val height = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "physical_size", "width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler?.physicalSize(this, width, height)
            }
            3 -> {
                val mode = args.newId(ZwlrOutputModeV1, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "mode", "mode=${Wire.show(mode)}")
                handler?.mode(this, mode)
            }
            4 -> {
                val enabled = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "enabled", "enabled=${Wire.show(enabled)}")
                handler?.enabled(this, enabled)
            }
            5 -> {
                val mode = args.obj(ZwlrOutputModeV1::class.java)
                args.end()
                if (Wire.tracing) Wire.received(this, "current_mode", "mode=${Wire.show(mode)}")
                handler?.currentMode(this, mode)
            }
            6 -> {
                val x = args.int()
                val y = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "position", "x=${Wire.show(x)}, y=${Wire.show(y)}")
                handler?.position(this, x, y)
            }
            7 -> {
                val transform = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "transform", "transform=${Wire.show(transform)}")
                handler?.transform(this, transform)
            }
            8 -> {
                val scale = args.fixed()
                args.end()
                if (Wire.tracing) Wire.received(this, "scale", "scale=${Wire.show(scale)}")
                handler?.scale(this, scale)
            }
            9 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "finished", "")
                handler?.finished(this)
            }
            10 -> {
                val make = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "make", "make=${Wire.show(make)}")
                handler?.make(this, make)
            }
            11 -> {
                val model = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "model", "model=${Wire.show(model)}")
                handler?.model(this, model)
            }
            12 -> {
                val serialNumber = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "serial_number", "serial_number=${Wire.show(serialNumber)}")
                handler?.serialNumber(this, serialNumber)
            }
            13 -> {
                val state = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "adaptive_sync", "state=${Wire.show(state)}")
                handler?.adaptiveSync(this, state)
            }
        }
    }

    public companion object : ProxyType<ZwlrOutputHeadV1>("zwlr_output_head_v1", 4) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrOutputHeadV1 = ZwlrOutputHeadV1(connection, id, version)
    }
}
