// Generated from xdg-shell.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.xdgshell

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Desktop user interface surface base interface
 *
 * An interface that may be implemented by a wl_surface, for
 *    implementations that provide a desktop-style user interface.
 *
 *    It provides a base set of functionality required to construct user
 *    interface elements requiring management by the compositor, such as
 *    toplevel windows, menus, etc. The types of functionality are split into
 *    xdg_surface roles.
 *
 *    Creating an xdg_surface does not set the role for a wl_surface. In order
 *    to map an xdg_surface, the client must create a role-specific object
 *    using, e.g., get_toplevel, get_popup. The wl_surface for any given
 *    xdg_surface can have at most one role, and may not be assigned any role
 *    not based on xdg_surface.
 *
 *    A role must be assigned before any other requests are made to the
 *    xdg_surface object.
 *
 *    The client must call wl_surface.commit on the corresponding wl_surface
 *    for the xdg_surface state to take effect.
 *
 *    Creating an xdg_surface from a wl_surface which has a buffer attached or
 *    committed is a client error, and any attempts by a client to attach or
 *    manipulate a buffer prior to the first xdg_surface.configure call must
 *    also be treated as errors.
 *
 *    After creating a role-specific object and setting it up (e.g. by sending
 *    the title, app ID, size constraints, parent, etc), the client must
 *    perform an initial commit without any buffer attached. The compositor
 *    will reply with initial wl_surface state such as
 *    wl_surface.preferred_buffer_scale followed by an xdg_surface.configure
 *    event. The client must acknowledge it and is then allowed to attach a
 *    buffer to map the surface.
 *
 *    Mapping an xdg_surface-based role surface is defined as making it
 *    possible for the surface to be shown by the compositor. Note that
 *    a mapped surface is not guaranteed to be visible once it is mapped.
 *
 *    For an xdg_surface to be mapped by the compositor, the following
 *    conditions must be met:
 *    (1) the client has assigned an xdg_surface-based role to the surface
 *    (2) the client has set and committed the xdg_surface state and the
 * role-dependent state to the surface
 *    (3) the client has committed a buffer to the surface
 *
 *    A newly-unmapped surface is considered to have met condition (1) out
 *    of the 3 required conditions for mapping a surface if its role surface
 *    has not been destroyed, i.e. the client must perform the initial commit
 *    again before attaching a buffer.
 */
public class XdgSurface private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<XdgSurface> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements xdg_surface. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the xdg_surface
         *
         * Destroy the xdg_surface object. An xdg_surface must only be destroyed
         * after its role object has been destroyed, otherwise
         * a defunct_role_object error is raised.
         */
        public fun destroy(resource: XdgSurface)

        /**
         * Assign the xdg_toplevel surface role
         *
         * This creates an xdg_toplevel object for the given xdg_surface and gives
         * the associated wl_surface the xdg_toplevel role.
         *
         * See the documentation of xdg_toplevel for more details about what an
         * xdg_toplevel is and how it is used.
         */
        public fun getToplevel(resource: XdgSurface, id: XdgToplevel)

        /**
         * Assign the xdg_popup surface role
         *
         * This creates an xdg_popup object for the given xdg_surface and gives
         * the associated wl_surface the xdg_popup role.
         *
         * If null is passed as a parent, a parent surface must be specified using
         * some other protocol, before committing the initial state.
         *
         * See the documentation of xdg_popup for more details about what an
         * xdg_popup is and how it is used.
         */
        public fun getPopup(resource: XdgSurface, id: XdgPopup, parent: XdgSurface?, positioner: XdgPositioner)

        /**
         * Set the new window geometry
         *
         * The window geometry of a surface is its "visible bounds" from the
         * user's perspective. Client-side decorations often have invisible
         * portions like drop-shadows which should be ignored for the
         * purposes of aligning, placing and constraining windows. Note that
         * in some situations, compositors may clip rendering to the window
         * geometry, so the client should avoid putting functional elements
         * outside of it.
         *
         * The window geometry is double-buffered state, see wl_surface.commit.
         *
         * When maintaining a position, the compositor should treat the (x, y)
         * coordinate of the window geometry as the top left corner of the window.
         * A client changing the (x, y) window geometry coordinate should in
         * general not alter the position of the window.
         *
         * Once the window geometry of the surface is set, it is not possible to
         * unset it, and it will remain the same until set_window_geometry is
         * called again, even if a new subsurface or buffer is attached.
         *
         * If never set, the value is the full bounds of the surface,
         * including any subsurfaces. This updates dynamically on every
         * commit. This unset is meant for extremely simple clients.
         *
         * The arguments are given in the surface-local coordinate space of
         * the wl_surface associated with this xdg_surface, and may extend outside
         * of the wl_surface itself to mark parts of the subsurface tree as part of
         * the window geometry.
         *
         * When applied, the effective window geometry will be the set window
         * geometry clamped to the bounding rectangle of the combined
         * geometry of the surface of the xdg_surface and the associated
         * subsurfaces.
         *
         * The effective geometry will not be recalculated unless a new call to
         * set_window_geometry is done and the new pending surface state is
         * subsequently applied.
         *
         * The width and height of the effective window geometry must be
         * greater than zero. Setting an invalid size will raise an
         * invalid_size error.
         */
        public fun setWindowGeometry(resource: XdgSurface, x: Int, y: Int, width: Int, height: Int)

        /**
         * Ack a configure event
         *
         * When a configure event is received, if a client commits the
         * surface in response to the configure event, then the client
         * must make an ack_configure request sometime before the commit
         * request, passing along the serial of the configure event.
         *
         * For instance, for toplevel surfaces the compositor might use this
         * information to move a surface to the top left only when the client has
         * drawn itself for the maximized or fullscreen state.
         *
         * If the client receives multiple configure events before it
         * can respond to one, it only has to ack the last configure event.
         * Acking a configure event that was never sent raises an invalid_serial
         * error.
         *
         * A client is not required to commit immediately after sending
         * an ack_configure request - it may even ack_configure several times
         * before its next surface commit.
         *
         * A client may send multiple ack_configure requests before committing, but
         * only the last request sent before a commit indicates which configure
         * event the client really is responding to.
         *
         * Sending an ack_configure request consumes the serial number sent with
         * the request, as well as serial numbers sent by all configure events
         * sent on this xdg_surface prior to the configure event referenced by
         * the committed serial.
         *
         * It is an error to issue multiple ack_configure requests referencing a
         * serial from the same configure event, or to issue an ack_configure
         * request referencing a serial from a configure event issued before the
         * event identified by the last ack_configure request for the same
         * xdg_surface. Doing so will raise an invalid_serial error.
         */
        public fun ackConfigure(resource: XdgSurface, serial: Int)
    }

    /**
     * Suggest a surface change
     *
     * The configure event marks the end of a configure sequence. A configure
     * sequence is a set of one or more events configuring the state of the
     * xdg_surface, including the final xdg_surface.configure event.
     *
     * Where applicable, xdg_surface surface roles will during a configure
     * sequence extend this event as a latched state sent as events before the
     * xdg_surface.configure event. Such events should be considered to make up
     * a set of atomically applied configuration states, where the
     * xdg_surface.configure commits the accumulated state.
     *
     * Clients should arrange their surface for the new states, and then send
     * an ack_configure request with the serial sent in this configure event at
     * some point before committing the new surface.
     *
     * If the client receives multiple configure events before it can respond
     * to one, it is free to discard all but the last event it received.
     */
    public fun sendConfigure(serial: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(serial).end()
    }

    public object Error {
        /** Surface was not fully constructed */
        public const val NOT_CONSTRUCTED: Int = 1
        /** Surface was already constructed */
        public const val ALREADY_CONSTRUCTED: Int = 2
        /** Attaching a buffer to an unconfigured surface */
        public const val UNCONFIGURED_BUFFER: Int = 3
        /** Invalid serial number when acking a configure event */
        public const val INVALID_SERIAL: Int = 4
        /** Width or height was zero or negative */
        public const val INVALID_SIZE: Int = 5
        /** Surface was destroyed before its role object */
        public const val DEFUNCT_ROLE_OBJECT: Int = 6
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "xdg_surface is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(XdgToplevel.create(client, idId, version))
                handler.getToplevel(this, id)
            }
            2 -> {
                val idId = args.newId()
                val parent = args.objOrNull(XdgSurface::class.java)
                val positioner = args.obj(XdgPositioner::class.java)
                args.end()
                val id = client.register(XdgPopup.create(client, idId, version))
                handler.getPopup(this, id, parent, positioner)
            }
            3 -> {
                val x = args.int()
                val y = args.int()
                val width = args.int()
                val height = args.int()
                args.end()
                handler.setWindowGeometry(this, x, y, width, height)
            }
            4 -> {
                val serial = args.uint()
                args.end()
                handler.ackConfigure(this, serial)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown xdg_surface request $opcode")
        }
    }

    public companion object : ResourceType<XdgSurface>("xdg_surface", 7) {
        internal override fun create(client: Client, id: Int, version: Int): XdgSurface = XdgSurface(client, id, version)
    }
}
