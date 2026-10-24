// Generated from xdg-shell.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.client.xdgshell

import dev.tessera.protocol.client.Connection
import dev.tessera.protocol.client.EventReader
import dev.tessera.protocol.client.Proxy
import dev.tessera.protocol.client.ProxyType
import dev.tessera.protocol.client.wayland.WlOutput
import dev.tessera.protocol.client.wayland.WlSeat

/**
 * Toplevel surface
 *
 * This interface defines an xdg_surface role which allows a surface to,
 * among other things, set window-like properties such as maximize,
 * fullscreen, and minimize, set application-specific metadata like title and
 * id, and well as trigger user interactive operations such as interactive
 * resize and move.
 *
 * An xdg_toplevel by default is responsible for providing the full intended
 * visual representation of the toplevel, which depending on the window
 * state, may mean things like a title bar, window controls and drop shadow.
 *
 * Unmapping an xdg_toplevel means that the surface cannot be shown
 * by the compositor until it is explicitly mapped again.
 * All active operations (e.g., move, resize) are canceled and all
 * attributes (e.g. title, state, stacking, ...) are discarded for
 * an xdg_toplevel surface when it is unmapped. The xdg_toplevel returns to
 * the state it had right after xdg_surface.get_toplevel. The client
 * can re-map the toplevel by performing a commit without any buffer
 * attached, waiting for a configure event and handling it as usual (see
 * xdg_surface description).
 *
 * Attaching a null buffer to a toplevel unmaps the surface.
 */
public class XdgToplevel private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XdgToplevel> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Suggest a surface change
         *
         * This configure event asks the client to resize its toplevel surface or
         * to change its state. The configured state should not be applied
         * immediately. See xdg_surface.configure for details.
         *
         * The width and height arguments specify a hint to the window
         * about how its surface should be resized in window geometry
         * coordinates. See set_window_geometry.
         *
         * If the width or height arguments are zero, it means the client
         * should decide its own window dimension. This may happen when the
         * compositor needs to configure the state of the surface but doesn't
         * have any information about any previous or expected dimension.
         *
         * The states listed in the event specify how the width/height
         * arguments should be interpreted, and possibly how it should be
         * drawn.
         *
         * The states are sent as an array of 32-bit unsigned integers in
         * native endianness. State values are defined in the state enum.
         *
         * Clients must send an ack_configure in response to this event. See
         * xdg_surface.configure and xdg_surface.ack_configure for details.
         */
        public fun configure(proxy: XdgToplevel, width: Int, height: Int, states: ByteArray) {}

        /**
         * Surface wants to be closed
         *
         * The close event is sent by the compositor when the user
         * wants the surface to be closed. This should be equivalent to
         * the user clicking the close button in client-side decorations,
         * if your application has any.
         *
         * This is only a request that the user intends to close the
         * window. The client may choose to ignore this request, or show
         * a dialog to ask the user to save their data, etc.
         */
        public fun close(proxy: XdgToplevel) {}

        /**
         * Recommended window geometry bounds
         *
         * The configure_bounds event may be sent prior to a xdg_toplevel.configure
         * event to communicate the bounds a window geometry size is recommended
         * to constrain to.
         *
         * The passed width and height are in surface coordinate space. If width
         * and height are 0, it means bounds is unknown and equivalent to as if no
         * configure_bounds event was ever sent for this surface.
         *
         * The bounds can for example correspond to the size of a monitor excluding
         * any panels or other shell components, so that a surface isn't created in
         * a way that it cannot fit.
         *
         * The bounds may change at any point, and in such a case, a new
         * xdg_toplevel.configure_bounds will be sent, followed by
         * xdg_toplevel.configure and xdg_surface.configure.
         *
         * Since version 4.
         */
        public fun configureBounds(proxy: XdgToplevel, width: Int, height: Int) {}

        /**
         * Compositor capabilities
         *
         * This event advertises the capabilities supported by the compositor. If
         * a capability isn't supported, clients should hide or disable the UI
         * elements that expose this functionality. For instance, if the
         * compositor doesn't advertise support for minimized toplevels, a button
         * triggering the set_minimized request should not be displayed.
         *
         * The compositor will ignore requests it doesn't support. For instance,
         * a compositor which doesn't advertise support for minimized will ignore
         * set_minimized requests.
         *
         * Compositors must send this event once before the first
         * xdg_surface.configure event. When the capabilities change, compositors
         * must send this event again and then send an xdg_surface.configure
         * event.
         *
         * The configured state should not be applied immediately. See
         * xdg_surface.configure for details.
         *
         * The capabilities are sent as an array of 32-bit unsigned integers in
         * native endianness. Capability values are defined in the wm_capabilities enum.
         *
         * Since version 5.
         */
        public fun wmCapabilities(proxy: XdgToplevel, capabilities: ByteArray) {}
    }

    /**
     * Destroy the xdg_toplevel
     *
     * This request destroys the role surface and unmaps the surface;
     * see "Unmapping" behavior in interface section for details.
     */
    public fun destroy(): Unit {
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Set the parent of this surface
     *
     * Set the "parent" of this surface. This surface should be stacked
     * above the parent surface and all other ancestor surfaces.
     *
     * Parent surfaces should be set on dialogs, toolboxes, or other
     * "auxiliary" surfaces, so that the parent is raised when the dialog
     * is raised.
     *
     * Setting a null parent for a child surface unsets its parent. Setting
     * a null parent for a surface which currently has no parent is a no-op.
     *
     * Only mapped surfaces can have child surfaces. Setting a parent which
     * is not mapped is equivalent to setting a null parent. If a surface
     * becomes unmapped, its children's parent is set to the parent of
     * the now-unmapped surface. If the now-unmapped surface has no parent,
     * its children's parent is unset. If the now-unmapped surface becomes
     * mapped again, its parent-child relationship is not restored.
     *
     * The parent toplevel must not be one of the child toplevel's
     * descendants, and the parent must be different from the child toplevel,
     * otherwise the invalid_parent protocol error is raised.
     */
    public fun setParent(parent: XdgToplevel?): Unit {
        request(1).obj(parent).end()
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
        request(2).string(title).end()
    }

    /**
     * Set application ID
     *
     * Set an application identifier for the surface.
     *
     * The app ID identifies the general class of applications to which
     * the surface belongs. The compositor can use this to group multiple
     * surfaces together, or to determine how to launch a new application.
     *
     * For D-Bus activatable applications, the app ID is used as the D-Bus
     * service name.
     *
     * The compositor shell will try to group application surfaces together
     * by their app ID. As a best practice, it is suggested to select app
     * ID's that match the basename of the application's .desktop file.
     * For example, "org.freedesktop.FooViewer" where the .desktop file is
     * "org.freedesktop.FooViewer.desktop".
     *
     * Like other properties, a set_app_id request can be sent after the
     * xdg_toplevel has been mapped to update the property.
     *
     * See the desktop-entry specification [0] for more details on
     * application identifiers and how they relate to well-known D-Bus
     * names and .desktop files.
     *
     * [0] https://standards.freedesktop.org/desktop-entry-spec/
     */
    public fun setAppId(appId: String): Unit {
        request(3).string(appId).end()
    }

    /**
     * Show the window menu
     *
     * Clients implementing client-side decorations might want to show
     * a context menu when right-clicking on the decorations, giving the
     * user a menu that they can use to maximize or minimize the window.
     *
     * This request asks the compositor to pop up such a window menu at
     * the given position, relative to the local surface coordinates of
     * the parent surface. There are no guarantees as to what menu items
     * the window menu contains, or even if a window menu will be drawn
     * at all.
     *
     * This request must be used in response to some sort of user action
     * like a button press, key press, or touch down event.
     */
    public fun showWindowMenu(seat: WlSeat, serial: Int, x: Int, y: Int): Unit {
        request(4).obj(seat).uint(serial).int(x).int(y).end()
    }

    /**
     * Start an interactive move
     *
     * Start an interactive, user-driven move of the surface.
     *
     * This request must be used in response to some sort of user action
     * like a button press, key press, or touch down event. The passed
     * serial is used to determine the type of interactive move (touch,
     * pointer, etc).
     *
     * The server may ignore move requests depending on the state of
     * the surface (e.g. fullscreen or maximized), or if the passed serial
     * is no longer valid.
     *
     * If triggered, the surface will lose the focus of the device
     * (wl_pointer, wl_touch, etc) used for the move. It is up to the
     * compositor to visually indicate that the move is taking place, such as
     * updating a pointer cursor, during the move. There is no guarantee
     * that the device focus will return when the move is completed.
     */
    public fun move(seat: WlSeat, serial: Int): Unit {
        request(5).obj(seat).uint(serial).end()
    }

    /**
     * Start an interactive resize
     *
     * Start a user-driven, interactive resize of the surface.
     *
     * This request must be used in response to some sort of user action
     * like a button press, key press, or touch down event. The passed
     * serial is used to determine the type of interactive resize (touch,
     * pointer, etc).
     *
     * The server may ignore resize requests depending on the state of
     * the surface (e.g. fullscreen or maximized).
     *
     * If triggered, the client will receive configure events with the
     * "resize" state enum value and the expected sizes. See the "resize"
     * enum value for more details about what is required. The client
     * must also acknowledge configure events using "ack_configure". After
     * the resize is completed, the client will receive another "configure"
     * event without the resize state.
     *
     * If triggered, the surface also will lose the focus of the device
     * (wl_pointer, wl_touch, etc) used for the resize. It is up to the
     * compositor to visually indicate that the resize is taking place,
     * such as updating a pointer cursor, during the resize. There is no
     * guarantee that the device focus will return when the resize is
     * completed.
     *
     * The edges parameter specifies how the surface should be resized, and
     * is one of the values of the resize_edge enum. Values not matching
     * a variant of the enum will cause the invalid_resize_edge protocol error.
     * The compositor may use this information to update the surface position
     * for example when dragging the top left corner. The compositor may also
     * use this information to adapt its behavior, e.g. choose an appropriate
     * cursor image.
     */
    public fun resize(seat: WlSeat, serial: Int, edges: Int): Unit {
        request(6).obj(seat).uint(serial).uint(edges).end()
    }

    /**
     * Set the maximum size
     *
     * Set a maximum size for the window.
     *
     * The client can specify a maximum size so that the compositor does
     * not try to configure the window beyond this size.
     *
     * The width and height arguments are in window geometry coordinates.
     * See xdg_surface.set_window_geometry.
     *
     * Values set in this way are double-buffered, see wl_surface.commit.
     *
     * The compositor can use this information to allow or disallow
     * different states like maximize or fullscreen and draw accurate
     * animations.
     *
     * Similarly, a tiling window manager may use this information to
     * place and resize client windows in a more effective way.
     *
     * The client should not rely on the compositor to obey the maximum
     * size. The compositor may decide to ignore the values set by the
     * client and request a larger size.
     *
     * If never set, or a value of zero in the request, means that the
     * client has no expected maximum size in the given dimension.
     * As a result, a client wishing to reset the maximum size
     * to an unspecified state can use zero for width and height in the
     * request.
     *
     * Requesting a maximum size to be smaller than the minimum size of
     * a surface is illegal and will result in an invalid_size error.
     *
     * The width and height must be greater than or equal to zero. Using
     * strictly negative values for width or height will result in an
     * invalid_size error.
     */
    public fun setMaxSize(width: Int, height: Int): Unit {
        request(7).int(width).int(height).end()
    }

    /**
     * Set the minimum size
     *
     * Set a minimum size for the window.
     *
     * The client can specify a minimum size so that the compositor does
     * not try to configure the window below this size.
     *
     * The width and height arguments are in window geometry coordinates.
     * See xdg_surface.set_window_geometry.
     *
     * Values set in this way are double-buffered, see wl_surface.commit.
     *
     * The compositor can use this information to allow or disallow
     * different states like maximize or fullscreen and draw accurate
     * animations.
     *
     * Similarly, a tiling window manager may use this information to
     * place and resize client windows in a more effective way.
     *
     * The client should not rely on the compositor to obey the minimum
     * size. The compositor may decide to ignore the values set by the
     * client and request a smaller size.
     *
     * If never set, or a value of zero in the request, means that the
     * client has no expected minimum size in the given dimension.
     * As a result, a client wishing to reset the minimum size
     * to an unspecified state can use zero for width and height in the
     * request.
     *
     * Requesting a minimum size to be larger than the maximum size of
     * a surface is illegal and will result in an invalid_size error.
     *
     * The width and height must be greater than or equal to zero. Using
     * strictly negative values for width and height will result in an
     * invalid_size error.
     */
    public fun setMinSize(width: Int, height: Int): Unit {
        request(8).int(width).int(height).end()
    }

    /**
     * Maximize the window
     *
     * Maximize the surface.
     *
     * After requesting that the surface should be maximized, the compositor
     * will respond by emitting a configure event. Whether this configure
     * actually sets the window maximized is subject to compositor policies.
     * The client must then update its content, drawing in the configured
     * state. The client must also acknowledge the configure when committing
     * the new content (see ack_configure).
     *
     * It is up to the compositor to decide how and where to maximize the
     * surface, for example which output and what region of the screen should
     * be used.
     *
     * If the surface was already maximized, the compositor will still emit
     * a configure event with the "maximized" state.
     *
     * If the surface is in a fullscreen state, this request has no direct
     * effect. It may alter the state the surface is returned to when
     * unmaximized unless overridden by the compositor.
     */
    public fun setMaximized(): Unit {
        request(9).end()
    }

    /**
     * Unmaximize the window
     *
     * Unmaximize the surface.
     *
     * After requesting that the surface should be unmaximized, the compositor
     * will respond by emitting a configure event. Whether this actually
     * un-maximizes the window is subject to compositor policies.
     * If available and applicable, the compositor will include the window
     * geometry dimensions the window had prior to being maximized in the
     * configure event. The client must then update its content, drawing it in
     * the configured state. The client must also acknowledge the configure
     * when committing the new content (see ack_configure).
     *
     * It is up to the compositor to position the surface after it was
     * unmaximized; usually the position the surface had before maximizing, if
     * applicable.
     *
     * If the surface was already not maximized, the compositor will still
     * emit a configure event without the "maximized" state.
     *
     * If the surface is in a fullscreen state, this request has no direct
     * effect. It may alter the state the surface is returned to when
     * unmaximized unless overridden by the compositor.
     */
    public fun unsetMaximized(): Unit {
        request(10).end()
    }

    /**
     * Set the window as fullscreen on an output
     *
     * Make the surface fullscreen.
     *
     * After requesting that the surface should be fullscreened, the
     * compositor will respond by emitting a configure event. Whether the
     * client is actually put into a fullscreen state is subject to compositor
     * policies. The client must also acknowledge the configure when
     * committing the new content (see ack_configure).
     *
     * The output passed by the request indicates the client's preference as
     * to which display it should be set fullscreen on. If this value is NULL,
     * it's up to the compositor to choose which display will be used to map
     * this surface.
     *
     * If the surface doesn't cover the whole output, the compositor will
     * position the surface in the center of the output and compensate with
     * border fill covering the rest of the output. The content of the
     * border fill is undefined, but should be assumed to be in some way that
     * attempts to blend into the surrounding area (e.g. solid black).
     *
     * If the fullscreened surface is not opaque, the compositor must make
     * sure that other screen content not part of the same surface tree (made
     * up of subsurfaces, popups or similarly coupled surfaces) are not
     * visible below the fullscreened surface.
     */
    public fun setFullscreen(output: WlOutput?): Unit {
        request(11).obj(output).end()
    }

    /**
     * Unset the window as fullscreen
     *
     * Make the surface no longer fullscreen.
     *
     * After requesting that the surface should be unfullscreened, the
     * compositor will respond by emitting a configure event.
     * Whether this actually removes the fullscreen state of the client is
     * subject to compositor policies.
     *
     * Making a surface unfullscreen sets states for the surface based on the following:
     * * the state(s) it may have had before becoming fullscreen
     * * any state(s) decided by the compositor
     * * any state(s) requested by the client while the surface was fullscreen
     *
     * The compositor may include the previous window geometry dimensions in
     * the configure event, if applicable.
     *
     * The client must also acknowledge the configure when committing the new
     * content (see ack_configure).
     */
    public fun unsetFullscreen(): Unit {
        request(12).end()
    }

    /**
     * Set the window as minimized
     *
     * Request that the compositor minimize your surface. There is no
     * way to know if the surface is currently minimized, nor is there
     * any way to unset minimization on this surface.
     *
     * If you are looking to throttle redrawing when minimized, please
     * instead use the wl_surface.frame event for this, as this will
     * also work with live previews on windows in Alt-Tab, Expose or
     * similar compositor features.
     */
    public fun setMinimized(): Unit {
        request(13).end()
    }

    public object Error {
        /** provided value is         not a valid variant of the resize_edge enum */
        public const val INVALID_RESIZE_EDGE: Int = 0
        /** invalid parent toplevel */
        public const val INVALID_PARENT: Int = 1
        /** client provided an invalid min or max size */
        public const val INVALID_SIZE: Int = 2
    }

    /** edge values for resizing */
    public object ResizeEdge {
        public const val NONE: Int = 0
        public const val TOP: Int = 1
        public const val BOTTOM: Int = 2
        public const val LEFT: Int = 4
        public const val TOP_LEFT: Int = 5
        public const val BOTTOM_LEFT: Int = 6
        public const val RIGHT: Int = 8
        public const val TOP_RIGHT: Int = 9
        public const val BOTTOM_RIGHT: Int = 10
    }

    /** types of state on the surface */
    public object State {
        /** the surface is maximized */
        public const val MAXIMIZED: Int = 1
        /** the surface is fullscreen */
        public const val FULLSCREEN: Int = 2
        /** the surface is being resized */
        public const val RESIZING: Int = 3
        /** the surface is now activated */
        public const val ACTIVATED: Int = 4
        public const val TILED_LEFT: Int = 5
        public const val TILED_RIGHT: Int = 6
        public const val TILED_TOP: Int = 7
        public const val TILED_BOTTOM: Int = 8
        public const val SUSPENDED: Int = 9
        public const val CONSTRAINED_LEFT: Int = 10
        public const val CONSTRAINED_RIGHT: Int = 11
        public const val CONSTRAINED_TOP: Int = 12
        public const val CONSTRAINED_BOTTOM: Int = 13
    }

    public object WmCapabilities {
        /** show_window_menu is available */
        public const val WINDOW_MENU: Int = 1
        /** set_maximized and unset_maximized are available */
        public const val MAXIMIZE: Int = 2
        /** set_fullscreen and unset_fullscreen are available */
        public const val FULLSCREEN: Int = 3
        /** set_minimized is available */
        public const val MINIMIZE: Int = 4
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val width = args.int()
                val height = args.int()
                val states = args.array()
                args.end()
                handler?.configure(this, width, height, states)
            }
            1 -> {
                args.end()
                handler?.close(this)
            }
            2 -> {
                val width = args.int()
                val height = args.int()
                args.end()
                handler?.configureBounds(this, width, height)
            }
            3 -> {
                val capabilities = args.array()
                args.end()
                handler?.wmCapabilities(this, capabilities)
            }
        }
    }

    public companion object : ProxyType<XdgToplevel>("xdg_toplevel", 7) {
        internal override fun create(connection: Connection, id: Int, version: Int): XdgToplevel = XdgToplevel(connection, id, version)
    }
}
