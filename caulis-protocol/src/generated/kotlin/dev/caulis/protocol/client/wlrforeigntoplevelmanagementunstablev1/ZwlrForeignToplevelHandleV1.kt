// Generated from wlr-foreign-toplevel-management-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrforeigntoplevelmanagementunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlOutput
import dev.caulis.protocol.client.wayland.WlSeat
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * An opened toplevel
 *
 * A zwlr_foreign_toplevel_handle_v1 object represents an opened toplevel
 * window. Each app may have multiple opened toplevels.
 *
 * Each toplevel has a list of outputs it is visible on, conveyed to the
 * client with the output_enter and output_leave events.
 */
public class ZwlrForeignToplevelHandleV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrForeignToplevelHandleV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Title change
         *
         * This event is emitted whenever the title of the toplevel changes.
         */
        public fun title(proxy: ZwlrForeignToplevelHandleV1, title: String) {}

        /**
         * App-id change
         *
         * This event is emitted whenever the app-id of the toplevel changes.
         */
        public fun appId(proxy: ZwlrForeignToplevelHandleV1, appId: String) {}

        /**
         * Toplevel entered an output
         *
         * This event is emitted whenever the toplevel becomes visible on
         * the given output. A toplevel may be visible on multiple outputs.
         */
        public fun outputEnter(proxy: ZwlrForeignToplevelHandleV1, output: WlOutput) {}

        /**
         * Toplevel left an output
         *
         * This event is emitted whenever the toplevel stops being visible on
         * the given output. It is guaranteed that an entered-output event
         * with the same output has been emitted before this event.
         */
        public fun outputLeave(proxy: ZwlrForeignToplevelHandleV1, output: WlOutput) {}

        /**
         * The toplevel state changed
         *
         * This event is emitted immediately after the zlw_foreign_toplevel_handle_v1
         * is created and each time the toplevel state changes, either because of a
         * compositor action or because of a request in this protocol.
         */
        public fun state(proxy: ZwlrForeignToplevelHandleV1, state: ByteArray) {}

        /**
         * All information about the toplevel has been sent
         *
         * This event is sent after all changes in the toplevel state have been
         * sent.
         *
         * This allows changes to the zwlr_foreign_toplevel_handle_v1 properties
         * to be seen as atomic, even if they happen via multiple events.
         */
        public fun done(proxy: ZwlrForeignToplevelHandleV1) {}

        /**
         * This toplevel has been destroyed
         *
         * This event means the toplevel has been destroyed. It is guaranteed there
         * won't be any more events for this zwlr_foreign_toplevel_handle_v1. The
         * toplevel itself becomes inert so any requests will be ignored except the
         * destroy request.
         */
        public fun closed(proxy: ZwlrForeignToplevelHandleV1) {}

        /**
         * Parent change
         *
         * This event is emitted whenever the parent of the toplevel changes.
         *
         * No event is emitted when the parent handle is destroyed by the client.
         *
         * Since version 3.
         */
        public fun parent(proxy: ZwlrForeignToplevelHandleV1, parent: ZwlrForeignToplevelHandleV1?) {}
    }

    /**
     * Requests that the toplevel be maximized
     *
     * Requests that the toplevel be maximized. If the maximized state actually
     * changes, this will be indicated by the state event.
     */
    public fun setMaximized(): Unit {
        if (Wire.tracing) Wire.sent(this, "set_maximized", "")
        request(0).end()
    }

    /**
     * Requests that the toplevel be unmaximized
     *
     * Requests that the toplevel be unmaximized. If the maximized state actually
     * changes, this will be indicated by the state event.
     */
    public fun unsetMaximized(): Unit {
        if (Wire.tracing) Wire.sent(this, "unset_maximized", "")
        request(1).end()
    }

    /**
     * Requests that the toplevel be minimized
     *
     * Requests that the toplevel be minimized. If the minimized state actually
     * changes, this will be indicated by the state event.
     */
    public fun setMinimized(): Unit {
        if (Wire.tracing) Wire.sent(this, "set_minimized", "")
        request(2).end()
    }

    /**
     * Requests that the toplevel be unminimized
     *
     * Requests that the toplevel be unminimized. If the minimized state actually
     * changes, this will be indicated by the state event.
     */
    public fun unsetMinimized(): Unit {
        if (Wire.tracing) Wire.sent(this, "unset_minimized", "")
        request(3).end()
    }

    /**
     * Activate the toplevel
     *
     * Request that this toplevel be activated on the given seat.
     * There is no guarantee the toplevel will be actually activated.
     */
    public fun activate(seat: WlSeat): Unit {
        if (Wire.tracing) Wire.sent(this, "activate", "seat=${Wire.show(seat)}")
        request(4).obj(seat).end()
    }

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
    public fun close(): Unit {
        if (Wire.tracing) Wire.sent(this, "close", "")
        request(5).end()
    }

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
    public fun setRectangle(surface: WlSurface, x: Int, y: Int, width: Int, height: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_rectangle", "surface=${Wire.show(surface)}, x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
        request(6).obj(surface).int(x).int(y).int(width).int(height).end()
    }

    /**
     * Destroy the zwlr_foreign_toplevel_handle_v1 object
     *
     * Destroys the zwlr_foreign_toplevel_handle_v1 object.
     *
     * This request should be called either when the client does not want to
     * use the toplevel anymore or after the closed event to finalize the
     * destruction of the object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(7).end()
        connection.destroyProxy(this)
    }

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
    public fun setFullscreen(output: WlOutput?): Unit {
        check(version >= 2) { "zwlr_foreign_toplevel_handle_v1.set_fullscreen needs version 2, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "set_fullscreen", "output=${Wire.show(output)}")
        request(8).obj(output).end()
    }

    /**
     * Request that the toplevel be unfullscreened
     *
     * Requests that the toplevel be unfullscreened. If the fullscreen state
     * actually changes, this will be indicated by the state event.
     *
     * Since version 2.
     */
    public fun unsetFullscreen(): Unit {
        check(version >= 2) { "zwlr_foreign_toplevel_handle_v1.unset_fullscreen needs version 2, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "unset_fullscreen", "")
        request(9).end()
    }

    /** types of states on the toplevel */
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

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val title = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "title", "title=${Wire.show(title)}")
                handler?.title(this, title)
            }
            1 -> {
                val appId = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "app_id", "app_id=${Wire.show(appId)}")
                handler?.appId(this, appId)
            }
            2 -> {
                val output = args.obj(WlOutput::class.java)
                args.end()
                if (Wire.tracing) Wire.received(this, "output_enter", "output=${Wire.show(output)}")
                handler?.outputEnter(this, output)
            }
            3 -> {
                val output = args.obj(WlOutput::class.java)
                args.end()
                if (Wire.tracing) Wire.received(this, "output_leave", "output=${Wire.show(output)}")
                handler?.outputLeave(this, output)
            }
            4 -> {
                val state = args.array()
                args.end()
                if (Wire.tracing) Wire.received(this, "state", "state=${Wire.show(state)}")
                handler?.state(this, state)
            }
            5 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "done", "")
                handler?.done(this)
            }
            6 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "closed", "")
                handler?.closed(this)
            }
            7 -> {
                val parent = args.objOrNull(ZwlrForeignToplevelHandleV1::class.java)
                args.end()
                if (Wire.tracing) Wire.received(this, "parent", "parent=${Wire.show(parent)}")
                handler?.parent(this, parent)
            }
        }
    }

    public companion object : ProxyType<ZwlrForeignToplevelHandleV1>("zwlr_foreign_toplevel_handle_v1", 3) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrForeignToplevelHandleV1 = ZwlrForeignToplevelHandleV1(connection, id, version)
    }
}
