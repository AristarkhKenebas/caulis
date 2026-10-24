// Generated from xdg-decoration-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.xdgdecorationunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Decoration object for a toplevel surface
 *
 * The decoration object allows the compositor to toggle server-side window
 * decorations for a toplevel surface. The client can request to switch to
 * another mode.
 *
 * The xdg_toplevel_decoration object must be destroyed before its
 * xdg_toplevel.
 */
public class ZxdgToplevelDecorationV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZxdgToplevelDecorationV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zxdg_toplevel_decoration_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the decoration object
         *
         * Switch back to a mode without any server-side decorations at the next
         * commit, unless a new xdg_toplevel_decoration is created for the surface
         * first.
         */
        public fun destroy(resource: ZxdgToplevelDecorationV1)

        /**
         * Set the decoration mode
         *
         * Set the toplevel surface decoration mode. This informs the compositor
         * that the client prefers the provided decoration mode.
         *
         * After requesting a decoration mode, the compositor will respond by
         * emitting an xdg_surface.configure event. The client should then update
         * its content, drawing it without decorations if the received mode is
         * server-side decorations. The client must also acknowledge the configure
         * when committing the new content (see xdg_surface.ack_configure).
         *
         * The compositor can decide not to use the client's mode and enforce a
         * different mode instead.
         *
         * Clients whose decoration mode depend on the xdg_toplevel state may send
         * a set_mode request in response to an xdg_surface.configure event and wait
         * for the next xdg_surface.configure event to prevent unwanted state.
         * Such clients are responsible for preventing configure loops and must
         * make sure not to send multiple successive set_mode requests with the
         * same decoration mode.
         *
         * If an invalid mode is supplied by the client, the invalid_mode protocol
         * error is raised by the compositor.
         */
        public fun setMode(resource: ZxdgToplevelDecorationV1, mode: Int)

        /**
         * Unset the decoration mode
         *
         * Unset the toplevel surface decoration mode. This informs the compositor
         * that the client doesn't prefer a particular decoration mode.
         *
         * This request has the same semantics as set_mode.
         */
        public fun unsetMode(resource: ZxdgToplevelDecorationV1)
    }

    /**
     * Notify a decoration mode change
     *
     * The configure event configures the effective decoration mode. The
     * configured state should not be applied immediately. Clients must send an
     * ack_configure in response to this event. See xdg_surface.configure and
     * xdg_surface.ack_configure for details.
     *
     * A configure event can be sent at any time. The specified mode must be
     * obeyed by the client.
     */
    public fun sendConfigure(mode: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "configure", "mode=${Wire.show(mode)}")
        event(0).uint(mode).end()
    }

    public object Error {
        /** xdg_toplevel has a buffer attached before configure */
        public const val UNCONFIGURED_BUFFER: Int = 0
        /** xdg_toplevel already has a decoration object */
        public const val ALREADY_CONSTRUCTED: Int = 1
        /** xdg_toplevel destroyed before the decoration object */
        public const val ORPHANED: Int = 2
        /** invalid mode */
        public const val INVALID_MODE: Int = 3
    }

    /**
     * Window decoration modes
     */
    public object Mode {
        /** no server-side window decoration */
        public const val CLIENT_SIDE: Int = 1
        /** server-side window decoration */
        public const val SERVER_SIDE: Int = 2
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zxdg_toplevel_decoration_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val mode = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_mode", "mode=${Wire.show(mode)}")
                handler.setMode(this, mode)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "unset_mode", "")
                handler.unsetMode(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zxdg_toplevel_decoration_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZxdgToplevelDecorationV1>("zxdg_toplevel_decoration_v1", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZxdgToplevelDecorationV1 = ZxdgToplevelDecorationV1(client, id, version)
    }
}
