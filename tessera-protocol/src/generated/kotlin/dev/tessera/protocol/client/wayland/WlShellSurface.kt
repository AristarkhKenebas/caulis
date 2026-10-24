// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.client.wayland

import dev.tessera.protocol.client.Connection
import dev.tessera.protocol.client.EventReader
import dev.tessera.protocol.client.Proxy
import dev.tessera.protocol.client.ProxyType

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
public class WlShellSurface private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlShellSurface> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Ping client
         *
         * Ping a client to check if it is receiving events and sending
         * requests. A client is expected to reply with a pong request.
         */
        public fun ping(proxy: WlShellSurface, serial: Int) {}

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
        public fun configure(proxy: WlShellSurface, edges: Int, width: Int, height: Int) {}

        /**
         * Popup interaction is done
         *
         * The popup_done event is sent out when a popup grab is broken,
         * that is, when the user clicks a surface that doesn't belong
         * to the client owning the popup surface.
         */
        public fun popupDone(proxy: WlShellSurface) {}
    }

    /**
     * Respond to a ping event
     *
     * A client must respond to a ping event with a pong request or
     * the client may be deemed unresponsive.
     */
    public fun pong(serial: Int): Unit {
        request(0).uint(serial).end()
    }

    /**
     * Start an interactive move
     *
     * Start a pointer-driven move of the surface.
     *
     * This request must be used in response to a button press event.
     * The server may ignore move requests depending on the state of
     * the surface (e.g. fullscreen or maximized).
     */
    public fun move(seat: WlSeat, serial: Int): Unit {
        request(1).obj(seat).uint(serial).end()
    }

    /**
     * Start an interactive resize
     *
     * Start a pointer-driven resizing of the surface.
     *
     * This request must be used in response to a button press event.
     * The server may ignore resize requests depending on the state of
     * the surface (e.g. fullscreen or maximized).
     */
    public fun resize(seat: WlSeat, serial: Int, edges: Int): Unit {
        request(2).obj(seat).uint(serial).uint(edges).end()
    }

    /**
     * Make the surface a toplevel surface
     *
     * Map the surface as a toplevel surface.
     *
     * A toplevel surface is not fullscreen, maximized or transient.
     */
    public fun setToplevel(): Unit {
        request(3).end()
    }

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
    public fun setTransient(parent: WlSurface, x: Int, y: Int, flags: Int): Unit {
        request(4).obj(parent).int(x).int(y).uint(flags).end()
    }

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
    public fun setFullscreen(method: Int, framerate: Int, output: WlOutput?): Unit {
        request(5).uint(method).uint(framerate).obj(output).end()
    }

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
    public fun setPopup(seat: WlSeat, serial: Int, parent: WlSurface, x: Int, y: Int, flags: Int): Unit {
        request(6).obj(seat).uint(serial).obj(parent).int(x).int(y).uint(flags).end()
    }

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
    public fun setMaximized(output: WlOutput?): Unit {
        request(7).obj(output).end()
    }

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
    public fun setTitle(title: String): Unit {
        request(8).string(title).end()
    }

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
    public fun setClass(`class`: String): Unit {
        request(9).string(`class`).end()
    }

    /** edge values for resizing */
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

    /** details of transient behaviour */
    public object Transient {
        /** do not set keyboard focus */
        public const val INACTIVE: Int = 1
    }

    /** different method to set the surface fullscreen */
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

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val serial = args.uint()
                args.end()
                handler?.ping(this, serial)
            }
            1 -> {
                val edges = args.uint()
                val width = args.int()
                val height = args.int()
                args.end()
                handler?.configure(this, edges, width, height)
            }
            2 -> {
                args.end()
                handler?.popupDone(this)
            }
        }
    }

    public companion object : ProxyType<WlShellSurface>("wl_shell_surface", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlShellSurface = WlShellSurface(connection, id, version)
    }
}
