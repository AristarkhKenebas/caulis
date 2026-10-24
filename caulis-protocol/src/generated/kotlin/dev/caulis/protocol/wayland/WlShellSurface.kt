// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire

/**
 * Desktop-style metadata interface
 *
 * An interface that may be implemented by a wl_surface, for
 * implementations that provide a desktop-style user interface.
 *
 * It provides requests to treat surfaces like toplevel, fullscreen
 * or popup windows, move, resize or maximize them, associate
 * metadata like title and class, etc.
 *
 * On the server side the object is automatically destroyed when
 * the related wl_surface is destroyed. On the client side,
 * wl_shell_surface_destroy() must be called before destroying
 * the wl_surface object.
 */
public class WlShellSurface private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlShellSurface> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_shell_surface. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Respond to a ping event
         *
         * A client must respond to a ping event with a pong request or
         * the client may be deemed unresponsive.
         */
        public fun pong(resource: WlShellSurface, serial: Int)

        /**
         * Start an interactive move
         *
         * Start a pointer-driven move of the surface.
         *
         * This request must be used in response to a button press event.
         * The server may ignore move requests depending on the state of
         * the surface (e.g. fullscreen or maximized).
         */
        public fun move(resource: WlShellSurface, seat: WlSeat, serial: Int)

        /**
         * Start an interactive resize
         *
         * Start a pointer-driven resizing of the surface.
         *
         * This request must be used in response to a button press event.
         * The server may ignore resize requests depending on the state of
         * the surface (e.g. fullscreen or maximized).
         */
        public fun resize(resource: WlShellSurface, seat: WlSeat, serial: Int, edges: Int)

        /**
         * Make the surface a toplevel surface
         *
         * Map the surface as a toplevel surface.
         *
         * A toplevel surface is not fullscreen, maximized or transient.
         */
        public fun setToplevel(resource: WlShellSurface)

        /**
         * Make the surface a transient surface
         *
         * Map the surface relative to an existing surface.
         *
         * The x and y arguments specify the location of the upper left
         * corner of the surface relative to the upper left corner of the
         * parent surface, in surface-local coordinates.
         *
         * The flags argument controls details of the transient behaviour.
         */
        public fun setTransient(resource: WlShellSurface, parent: WlSurface, x: Int, y: Int, flags: Int)

        /**
         * Make the surface a fullscreen surface
         *
         * Map the surface as a fullscreen surface.
         *
         * If an output parameter is given then the surface will be made
         * fullscreen on that output. If the client does not specify the
         * output then the compositor will apply its policy - usually
         * choosing the output on which the surface has the biggest surface
         * area.
         *
         * The client may specify a method to resolve a size conflict
         * between the output size and the surface size - this is provided
         * through the method parameter.
         *
         * The framerate parameter is used only when the method is set
         * to "driver", to indicate the preferred framerate. A value of 0
         * indicates that the client does not care about framerate.  The
         * framerate is specified in mHz, that is framerate of 60000 is 60Hz.
         *
         * A method of "scale" or "driver" implies a scaling operation of
         * the surface, either via a direct scaling operation or a change of
         * the output mode. This will override any kind of output scaling, so
         * that mapping a surface with a buffer size equal to the mode can
         * fill the screen independent of buffer_scale.
         *
         * A method of "fill" means we don't scale up the buffer, however
         * any output scale is applied. This means that you may run into
         * an edge case where the application maps a buffer with the same
         * size of the output mode but buffer_scale 1 (thus making a
         * surface larger than the output). In this case it is allowed to
         * downscale the results to fit the screen.
         *
         * The compositor must reply to this request with a configure event
         * with the dimensions for the output on which the surface will
         * be made fullscreen.
         */
        public fun setFullscreen(resource: WlShellSurface, method: Int, framerate: Int, output: WlOutput?)

        /**
         * Make the surface a popup surface
         *
         * Map the surface as a popup.
         *
         * A popup surface is a transient surface with an added pointer
         * grab.
         *
         * An existing implicit grab will be changed to owner-events mode,
         * and the popup grab will continue after the implicit grab ends
         * (i.e. releasing the mouse button does not cause the popup to
         * be unmapped).
         *
         * The popup grab continues until the window is destroyed or a
         * mouse button is pressed in any other client's window. A click
         * in any of the client's surfaces is reported as normal, however,
         * clicks in other clients' surfaces will be discarded and trigger
         * the callback.
         *
         * The x and y arguments specify the location of the upper left
         * corner of the surface relative to the upper left corner of the
         * parent surface, in surface-local coordinates.
         */
        public fun setPopup(resource: WlShellSurface, seat: WlSeat, serial: Int, parent: WlSurface, x: Int, y: Int, flags: Int)

        /**
         * Make the surface a maximized surface
         *
         * Map the surface as a maximized surface.
         *
         * If an output parameter is given then the surface will be
         * maximized on that output. If the client does not specify the
         * output then the compositor will apply its policy - usually
         * choosing the output on which the surface has the biggest surface
         * area.
         *
         * The compositor will reply with a configure event telling
         * the expected new surface size. The operation is completed
         * on the next buffer attach to this surface.
         *
         * A maximized surface typically fills the entire output it is
         * bound to, except for desktop elements such as panels. This is
         * the main difference between a maximized shell surface and a
         * fullscreen shell surface.
         *
         * The details depend on the compositor implementation.
         */
        public fun setMaximized(resource: WlShellSurface, output: WlOutput?)

        /**
         * Set surface title
         *
         * Set a short title for the surface.
         *
         * This string may be used to identify the surface in a task bar,
         * window list, or other user interface elements provided by the
         * compositor.
         *
         * The string must be encoded in UTF-8.
         */
        public fun setTitle(resource: WlShellSurface, title: String)

        /**
         * Set surface class
         *
         * Set a class for the surface.
         *
         * The surface class identifies the general class of applications
         * to which the surface belongs. A common convention is to use the
         * file name (or the full path if it is a non-standard location) of
         * the application's .desktop file as the class.
         */
        public fun setClass(resource: WlShellSurface, `class`: String)
    }

    /**
     * Ping client
     *
     * Ping a client to check if it is receiving events and sending
     * requests. A client is expected to reply with a pong request.
     */
    public fun sendPing(serial: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "ping", "serial=${Wire.show(serial)}")
        event(0).uint(serial).end()
    }

    /**
     * Suggest resize
     *
     * The configure event asks the client to resize its surface.
     *
     * The size is a hint, in the sense that the client is free to
     * ignore it if it doesn't resize, pick a smaller size (to
     * satisfy aspect ratio or resize in steps of NxM pixels).
     *
     * The edges parameter provides a hint about how the surface
     * was resized. The client may use this information to decide
     * how to adjust its content to the new size (e.g. a scrolling
     * area might adjust its content position to leave the viewable
     * content unmoved).
     *
     * The client is free to dismiss all but the last configure
     * event it received.
     *
     * The width and height arguments specify the size of the window
     * in surface-local coordinates.
     */
    public fun sendConfigure(edges: Int, width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "configure", "edges=${Wire.show(edges)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        event(1).uint(edges).int(width).int(height).end()
    }

    /**
     * Popup interaction is done
     *
     * The popup_done event is sent out when a popup grab is broken,
     * that is, when the user clicks a surface that doesn't belong
     * to the client owning the popup surface.
     */
    public fun sendPopupDone() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "popup_done", "")
        event(2).end()
    }

    /**
     * Edge values for resizing
     *
     * Bit flags: combine with `or`.
     */
    public object Resize {
        /** no edge */
        public const val NONE: Int = 0
        /** top edge */
        public const val TOP: Int = 1
        /** bottom edge */
        public const val BOTTOM: Int = 2
        /** left edge */
        public const val LEFT: Int = 4
        /** top and left edges */
        public const val TOP_LEFT: Int = 5
        /** bottom and left edges */
        public const val BOTTOM_LEFT: Int = 6
        /** right edge */
        public const val RIGHT: Int = 8
        /** top and right edges */
        public const val TOP_RIGHT: Int = 9
        /** bottom and right edges */
        public const val BOTTOM_RIGHT: Int = 10
    }

    /**
     * Details of transient behaviour
     *
     * Bit flags: combine with `or`.
     */
    public object Transient {
        /** do not set keyboard focus */
        public const val INACTIVE: Int = 1
    }

    /**
     * Different method to set the surface fullscreen
     */
    public object FullscreenMethod {
        /** no preference, apply default policy */
        public const val DEFAULT: Int = 0
        /** scale, preserve the surface's aspect ratio and center on output */
        public const val SCALE: Int = 1
        /** switch output mode to the smallest mode that can fit the surface, add black borders to compensate size mismatch */
        public const val DRIVER: Int = 2
        /** no upscaling, center on output and add black borders to compensate size mismatch */
        public const val FILL: Int = 3
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_shell_surface is not implemented")
        when (opcode) {
            0 -> {
                val serial = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "pong", "serial=${Wire.show(serial)}")
                handler.pong(this, serial)
            }
            1 -> {
                val seat = args.obj(WlSeat::class.java)
                val serial = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "move", "seat=${Wire.show(seat)}, serial=${Wire.show(serial)}")
                handler.move(this, seat, serial)
            }
            2 -> {
                val seat = args.obj(WlSeat::class.java)
                val serial = args.uint()
                val edges = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "resize", "seat=${Wire.show(seat)}, serial=${Wire.show(serial)}, edges=${Wire.show(edges)}")
                handler.resize(this, seat, serial, edges)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "set_toplevel", "")
                handler.setToplevel(this)
            }
            4 -> {
                val parent = args.obj(WlSurface::class.java)
                val x = args.int()
                val y = args.int()
                val flags = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_transient", "parent=${Wire.show(parent)}, x=${Wire.show(x)}, y=${Wire.show(y)}, flags=${Wire.show(flags)}")
                handler.setTransient(this, parent, x, y, flags)
            }
            5 -> {
                val method = args.uint()
                val framerate = args.uint()
                val output = args.objOrNull(WlOutput::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_fullscreen", "method=${Wire.show(method)}, framerate=${Wire.show(framerate)}, output=${Wire.show(output)}")
                handler.setFullscreen(this, method, framerate, output)
            }
            6 -> {
                val seat = args.obj(WlSeat::class.java)
                val serial = args.uint()
                val parent = args.obj(WlSurface::class.java)
                val x = args.int()
                val y = args.int()
                val flags = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_popup", "seat=${Wire.show(seat)}, serial=${Wire.show(serial)}, parent=${Wire.show(parent)}, x=${Wire.show(x)}, y=${Wire.show(y)}, flags=${Wire.show(flags)}")
                handler.setPopup(this, seat, serial, parent, x, y, flags)
            }
            7 -> {
                val output = args.objOrNull(WlOutput::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_maximized", "output=${Wire.show(output)}")
                handler.setMaximized(this, output)
            }
            8 -> {
                val title = args.string()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_title", "title=${Wire.show(title)}")
                handler.setTitle(this, title)
            }
            9 -> {
                val `class` = args.string()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_class", "class_=${Wire.show(`class`)}")
                handler.setClass(this, `class`)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_shell_surface request $opcode")
        }
    }

    public companion object : ResourceType<WlShellSurface>("wl_shell_surface", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WlShellSurface = WlShellSurface(client, id, version)
    }
}
