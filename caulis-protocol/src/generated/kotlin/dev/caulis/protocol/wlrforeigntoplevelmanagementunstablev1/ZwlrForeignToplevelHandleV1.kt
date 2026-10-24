// Generated from wlr-foreign-toplevel-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrforeigntoplevelmanagementunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface

/**
 * An opened toplevel
 *
 * A zwlr_foreign_toplevel_handle_v1 object represents an opened toplevel
 * window. Each app may have multiple opened toplevels.
 *
 * Each toplevel has a list of outputs it is visible on, conveyed to the
 * client with the output_enter and output_leave events.
 */
public class ZwlrForeignToplevelHandleV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrForeignToplevelHandleV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_foreign_toplevel_handle_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Requests that the toplevel be maximized
         *
         * Requests that the toplevel be maximized. If the maximized state actually
         * changes, this will be indicated by the state event.
         */
        public fun setMaximized(resource: ZwlrForeignToplevelHandleV1)

        /**
         * Requests that the toplevel be unmaximized
         *
         * Requests that the toplevel be unmaximized. If the maximized state actually
         * changes, this will be indicated by the state event.
         */
        public fun unsetMaximized(resource: ZwlrForeignToplevelHandleV1)

        /**
         * Requests that the toplevel be minimized
         *
         * Requests that the toplevel be minimized. If the minimized state actually
         * changes, this will be indicated by the state event.
         */
        public fun setMinimized(resource: ZwlrForeignToplevelHandleV1)

        /**
         * Requests that the toplevel be unminimized
         *
         * Requests that the toplevel be unminimized. If the minimized state actually
         * changes, this will be indicated by the state event.
         */
        public fun unsetMinimized(resource: ZwlrForeignToplevelHandleV1)

        /**
         * Activate the toplevel
         *
         * Request that this toplevel be activated on the given seat.
         * There is no guarantee the toplevel will be actually activated.
         */
        public fun activate(resource: ZwlrForeignToplevelHandleV1, seat: WlSeat)

        /**
         * Request that the toplevel be closed
         *
         * Send a request to the toplevel to close itself. The compositor would
         * typically use a shell-specific method to carry out this request, for
         * example by sending the xdg_toplevel.close event. However, this gives
         * no guarantees the toplevel will actually be destroyed. If and when
         * this happens, the zwlr_foreign_toplevel_handle_v1.closed event will
         * be emitted.
         */
        public fun close(resource: ZwlrForeignToplevelHandleV1)

        /**
         * The rectangle which represents the toplevel
         *
         * The rectangle of the surface specified in this request corresponds to
         * the place where the app using this protocol represents the given toplevel.
         * It can be used by the compositor as a hint for some operations, e.g
         * minimizing. The client is however not required to set this, in which
         * case the compositor is free to decide some default value.
         *
         * If the client specifies more than one rectangle, only the last one is
         * considered.
         *
         * The dimensions are given in surface-local coordinates.
         * Setting width=height=0 removes the already-set rectangle.
         */
        public fun setRectangle(resource: ZwlrForeignToplevelHandleV1, surface: WlSurface, x: Int, y: Int, width: Int, height: Int)

        /**
         * Destroy the zwlr_foreign_toplevel_handle_v1 object
         *
         * Destroys the zwlr_foreign_toplevel_handle_v1 object.
         *
         * This request should be called either when the client does not want to
         * use the toplevel anymore or after the closed event to finalize the
         * destruction of the object.
         */
        public fun destroy(resource: ZwlrForeignToplevelHandleV1)

        /**
         * Request that the toplevel be fullscreened
         *
         * Requests that the toplevel be fullscreened on the given output. If the
         * fullscreen state and/or the outputs the toplevel is visible on actually
         * change, this will be indicated by the state and output_enter/leave
         * events.
         *
         * The output parameter is only a hint to the compositor. Also, if output
         * is NULL, the compositor should decide which output the toplevel will be
         * fullscreened on, if at all.
         *
         * Since version 2.
         */
        public fun setFullscreen(resource: ZwlrForeignToplevelHandleV1, output: WlOutput?)

        /**
         * Request that the toplevel be unfullscreened
         *
         * Requests that the toplevel be unfullscreened. If the fullscreen state
         * actually changes, this will be indicated by the state event.
         *
         * Since version 2.
         */
        public fun unsetFullscreen(resource: ZwlrForeignToplevelHandleV1)
    }

    /**
     * Title change
     *
     * This event is emitted whenever the title of the toplevel changes.
     */
    public fun sendTitle(title: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "title", "title=${Wire.show(title)}")
        event(0).string(title).end()
    }

    /**
     * App-id change
     *
     * This event is emitted whenever the app-id of the toplevel changes.
     */
    public fun sendAppId(appId: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "app_id", "app_id=${Wire.show(appId)}")
        event(1).string(appId).end()
    }

    /**
     * Toplevel entered an output
     *
     * This event is emitted whenever the toplevel becomes visible on
     * the given output. A toplevel may be visible on multiple outputs.
     */
    public fun sendOutputEnter(output: WlOutput) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "output_enter", "output=${Wire.show(output)}")
        event(2).obj(output).end()
    }

    /**
     * Toplevel left an output
     *
     * This event is emitted whenever the toplevel stops being visible on
     * the given output. It is guaranteed that an entered-output event
     * with the same output has been emitted before this event.
     */
    public fun sendOutputLeave(output: WlOutput) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "output_leave", "output=${Wire.show(output)}")
        event(3).obj(output).end()
    }

    /**
     * The toplevel state changed
     *
     * This event is emitted immediately after the zlw_foreign_toplevel_handle_v1
     * is created and each time the toplevel state changes, either because of a
     * compositor action or because of a request in this protocol.
     */
    public fun sendState(state: ByteArray) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "state", "state=${Wire.show(state)}")
        event(4).array(state).end()
    }

    /**
     * All information about the toplevel has been sent
     *
     * This event is sent after all changes in the toplevel state have been
     * sent.
     *
     * This allows changes to the zwlr_foreign_toplevel_handle_v1 properties
     * to be seen as atomic, even if they happen via multiple events.
     */
    public fun sendDone() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "done", "")
        event(5).end()
    }

    /**
     * This toplevel has been destroyed
     *
     * This event means the toplevel has been destroyed. It is guaranteed there
     * won't be any more events for this zwlr_foreign_toplevel_handle_v1. The
     * toplevel itself becomes inert so any requests will be ignored except the
     * destroy request.
     */
    public fun sendClosed() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "closed", "")
        event(6).end()
    }

    /**
     * Parent change
     *
     * This event is emitted whenever the parent of the toplevel changes.
     *
     * No event is emitted when the parent handle is destroyed by the client.
     *
     * Since version 3.
     */
    public fun sendParent(parent: ZwlrForeignToplevelHandleV1?) {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "parent", "parent=${Wire.show(parent)}")
        event(7).obj(parent).end()
    }

    /**
     * Types of states on the toplevel
     */
    public object State {
        /** the toplevel is maximized */
        public const val MAXIMIZED: Int = 0
        /** the toplevel is minimized */
        public const val MINIMIZED: Int = 1
        /** the toplevel is active */
        public const val ACTIVATED: Int = 2
        /** the toplevel is fullscreen */
        public const val FULLSCREEN: Int = 3
    }

    public object Error {
        /** the provided rectangle is invalid */
        public const val INVALID_RECTANGLE: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_foreign_toplevel_handle_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "set_maximized", "")
                handler.setMaximized(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "unset_maximized", "")
                handler.unsetMaximized(this)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "set_minimized", "")
                handler.setMinimized(this)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "unset_minimized", "")
                handler.unsetMinimized(this)
            }
            4 -> {
                val seat = args.obj(WlSeat::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "activate", "seat=${Wire.show(seat)}")
                handler.activate(this, seat)
            }
            5 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "close", "")
                handler.close(this)
            }
            6 -> {
                val surface = args.obj(WlSurface::class.java)
                val x = args.int()
                val y = args.int()
                val width = args.int()
                val height = args.int()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_rectangle", "surface=${Wire.show(surface)}, x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler.setRectangle(this, surface, x, y, width, height)
            }
            7 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            8 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_foreign_toplevel_handle_v1.set_fullscreen needs version 2")
                val output = args.objOrNull(WlOutput::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_fullscreen", "output=${Wire.show(output)}")
                handler.setFullscreen(this, output)
            }
            9 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_foreign_toplevel_handle_v1.unset_fullscreen needs version 2")
                args.end()
                if (Wire.tracing) Wire.request(this, "unset_fullscreen", "")
                handler.unsetFullscreen(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_foreign_toplevel_handle_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrForeignToplevelHandleV1>("zwlr_foreign_toplevel_handle_v1", 3) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrForeignToplevelHandleV1 = ZwlrForeignToplevelHandleV1(client, id, version)
    }
}
