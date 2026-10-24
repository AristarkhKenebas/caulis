// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wayland

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Compositor output region
 *
 * An output describes part of the compositor geometry.  The
 * compositor works in the 'compositor coordinate system' and an
 * output corresponds to a rectangular area in that space that is
 * actually visible.  This typically corresponds to a monitor that
 * displays part of the compositor space.  This object is published
 * as global during start up, or when a monitor is hotplugged.
 */
public class WlOutput private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlOutput> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Properties of the output
         *
         * The geometry event describes geometric properties of the output.
         * The event is sent when binding to the output object and whenever
         * any of the properties change.
         *
         * The physical size can be set to zero if it doesn't make sense for this
         * output (e.g. for projectors or virtual outputs).
         *
         * The geometry event will be followed by a done event (starting from
         * version 2).
         *
         * Clients should use wl_surface.preferred_buffer_transform instead of the
         * transform advertised by this event to find the preferred buffer
         * transform to use for a surface.
         *
         * Note: wl_output only advertises partial information about the output
         * position and identification. Some compositors, for instance those not
         * implementing a desktop-style output layout or those exposing virtual
         * outputs, might fake this information. Instead of using x and y, clients
         * should use xdg_output.logical_position. Instead of using make and model,
         * clients should use name and description.
         */
        public fun geometry(proxy: WlOutput, x: Int, y: Int, physicalWidth: Int, physicalHeight: Int, subpixel: Int, make: String, model: String, transform: Int) {}

        /**
         * Advertise available modes for the output
         *
         * The mode event describes an available mode for the output.
         *
         * The event is sent when binding to the output object and there
         * will always be one mode, the current mode.  The event is sent
         * again if an output changes mode, for the mode that is now
         * current.  In other words, the current mode is always the last
         * mode that was received with the current flag set.
         *
         * Non-current modes are deprecated. A compositor can decide to only
         * advertise the current mode and never send other modes. Clients
         * should not rely on non-current modes.
         *
         * The size of a mode is given in physical hardware units of
         * the output device. This is not necessarily the same as
         * the output size in the global compositor space. For instance,
         * the output may be scaled, as described in wl_output.scale,
         * or transformed, as described in wl_output.transform. Clients
         * willing to retrieve the output size in the global compositor
         * space should use xdg_output.logical_size instead.
         *
         * The vertical refresh rate can be set to zero if it doesn't make
         * sense for this output (e.g. for virtual outputs).
         *
         * The mode event will be followed by a done event (starting from
         * version 2).
         *
         * Clients should not use the refresh rate to schedule frames. Instead,
         * they should use the wl_surface.frame event or the presentation-time
         * protocol.
         *
         * Note: this information is not always meaningful for all outputs. Some
         * compositors, such as those exposing virtual outputs, might fake the
         * refresh rate or the size.
         */
        public fun mode(proxy: WlOutput, flags: Int, width: Int, height: Int, refresh: Int) {}

        /**
         * Sent all information about output
         *
         * This event is sent after all other properties have been
         * sent after binding to the output object and after any
         * other property changes done after that. This allows
         * changes to the output properties to be seen as
         * atomic, even if they happen via multiple events.
         *
         * Since version 2.
         */
        public fun done(proxy: WlOutput) {}

        /**
         * Output scaling properties
         *
         * This event contains scaling geometry information
         * that is not in the geometry event. It may be sent after
         * binding the output object or if the output scale changes
         * later. The compositor will emit a non-zero, positive
         * value for scale. If it is not sent, the client should
         * assume a scale of 1.
         *
         * A scale larger than 1 means that the compositor will
         * automatically scale surface buffers by this amount
         * when rendering. This is used for very high resolution
         * displays where applications rendering at the native
         * resolution would be too small to be legible.
         *
         * Clients should use wl_surface.preferred_buffer_scale
         * instead of this event to find the preferred buffer
         * scale to use for a surface.
         *
         * The scale event will be followed by a done event.
         *
         * Since version 2.
         */
        public fun scale(proxy: WlOutput, factor: Int) {}

        /**
         * Name of this output
         *
         * Many compositors will assign user-friendly names to their outputs, show
         * them to the user, allow the user to refer to an output, etc. The client
         * may wish to know this name as well to offer the user similar behaviors.
         *
         * The name is a UTF-8 string with no convention defined for its contents.
         * Each name is unique among all wl_output globals. The name is only
         * guaranteed to be unique for the compositor instance.
         *
         * The same output name is used for all clients for a given wl_output
         * global. Thus, the name can be shared across processes to refer to a
         * specific wl_output global.
         *
         * The name is not guaranteed to be persistent across sessions, thus cannot
         * be used to reliably identify an output in e.g. configuration files.
         *
         * Examples of names include 'HDMI-A-1', 'WL-1', 'X11-1', etc. However, do
         * not assume that the name is a reflection of an underlying DRM connector,
         * X11 connection, etc.
         *
         * The name event is sent after binding the output object. This event is
         * only sent once per output object, and the name does not change over the
         * lifetime of the wl_output global.
         *
         * Compositors may re-use the same output name if the wl_output global is
         * destroyed and re-created later. Compositors should avoid re-using the
         * same name if possible.
         *
         * The name event will be followed by a done event.
         *
         * Since version 4.
         */
        public fun name(proxy: WlOutput, name: String) {}

        /**
         * Human-readable description of this output
         *
         * Many compositors can produce human-readable descriptions of their
         * outputs. The client may wish to know this description as well, e.g. for
         * output selection purposes.
         *
         * The description is a UTF-8 string with no convention defined for its
         * contents. The description is not guaranteed to be unique among all
         * wl_output globals. Examples might include 'Foocorp 11" Display' or
         * 'Virtual X11 output via :1'.
         *
         * The description event is sent after binding the output object and
         * whenever the description changes. The description is optional, and may
         * not be sent at all.
         *
         * The description event will be followed by a done event.
         *
         * Since version 4.
         */
        public fun description(proxy: WlOutput, description: String) {}
    }

    /**
     * Release the output object
     *
     * Using this request a client can tell the server that it is not going to
     * use the output object anymore.
     *
     * Since version 3.
     */
    public fun release(): Unit {
        check(version >= 3) { "wl_output.release needs version 3, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "release", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /** subpixel geometry information */
    public object Subpixel {
        /** unknown geometry */
        public const val UNKNOWN: Int = 0
        /** no geometry */
        public const val NONE: Int = 1
        /** horizontal RGB */
        public const val HORIZONTAL_RGB: Int = 2
        /** horizontal BGR */
        public const val HORIZONTAL_BGR: Int = 3
        /** vertical RGB */
        public const val VERTICAL_RGB: Int = 4
        /** vertical BGR */
        public const val VERTICAL_BGR: Int = 5
    }

    /** transformation applied to buffer contents */
    public object Transform {
        /** no transform */
        public const val NORMAL: Int = 0
        /** 90 degrees counter-clockwise */
        public const val _90: Int = 1
        /** 180 degrees counter-clockwise */
        public const val _180: Int = 2
        /** 270 degrees counter-clockwise */
        public const val _270: Int = 3
        /** 180 degree flip around a vertical axis */
        public const val FLIPPED: Int = 4
        /** flip and rotate 90 degrees counter-clockwise */
        public const val FLIPPED_90: Int = 5
        /** flip and rotate 180 degrees counter-clockwise */
        public const val FLIPPED_180: Int = 6
        /** flip and rotate 270 degrees counter-clockwise */
        public const val FLIPPED_270: Int = 7
    }

    /** mode information */
    public object Mode {
        /** indicates this is the current mode */
        public const val CURRENT: Int = 1
        /** indicates this is the preferred mode */
        public const val PREFERRED: Int = 2
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val x = args.int()
                val y = args.int()
                val physicalWidth = args.int()
                val physicalHeight = args.int()
                val subpixel = args.int()
                val make = args.string()
                val model = args.string()
                val transform = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "geometry", "x=${Wire.show(x)}, y=${Wire.show(y)}, physical_width=${Wire.show(physicalWidth)}, physical_height=${Wire.show(physicalHeight)}, subpixel=${Wire.show(subpixel)}, make=${Wire.show(make)}, model=${Wire.show(model)}, transform=${Wire.show(transform)}")
                handler?.geometry(this, x, y, physicalWidth, physicalHeight, subpixel, make, model, transform)
            }
            1 -> {
                val flags = args.uint()
                val width = args.int()
                val height = args.int()
                val refresh = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "mode", "flags=${Wire.show(flags)}, width=${Wire.show(width)}, height=${Wire.show(height)}, refresh=${Wire.show(refresh)}")
                handler?.mode(this, flags, width, height, refresh)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "done", "")
                handler?.done(this)
            }
            3 -> {
                val factor = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "scale", "factor=${Wire.show(factor)}")
                handler?.scale(this, factor)
            }
            4 -> {
                val name = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "name", "name=${Wire.show(name)}")
                handler?.name(this, name)
            }
            5 -> {
                val description = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "description", "description=${Wire.show(description)}")
                handler?.description(this, description)
            }
        }
    }

    public companion object : ProxyType<WlOutput>("wl_output", 4) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlOutput = WlOutput(connection, id, version)
    }
}
