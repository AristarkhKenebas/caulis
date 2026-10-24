// Generated from xwayland-shell-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xwaylandshellv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Interface for associating Xwayland windows to wl_surfaces
 *
 * An Xwayland surface is a surface managed by an Xwayland server.
 * It is used for associating surfaces to Xwayland windows.
 *
 * The Xwayland server associated with actions in this interface is
 * determined by the Wayland client making the request.
 *
 * The client must call wl_surface.commit on the corresponding wl_surface
 * for the xwayland_surface_v1 state to take effect.
 */
public class XwaylandSurfaceV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XwaylandSurfaceV1> get() = Companion

    /**
     * Associates a Xwayland window to a wl_surface
     *
     * Associates an Xwayland window to a wl_surface.
     * The association state is double-buffered, see wl_surface.commit.
     *
     * The `serial_lo` and `serial_hi` parameters specify a non-zero
     * monotonic serial number which is entirely unique and provided by the
     * Xwayland server equal to the serial value provided by a client message
     * with a message type of the `WL_SURFACE_SERIAL` atom on the X11 window
     * for this surface to be associated to.
     *
     * The serial value in the `WL_SURFACE_SERIAL` client message is specified
     * as having the lo-bits specified in `l[0]` and the hi-bits specified
     * in `l[1]`.
     *
     * If the serial value provided by `serial_lo` and `serial_hi` is not
     * valid, the `invalid_serial` protocol error will be raised.
     *
     * An X11 window may be associated with multiple surfaces throughout its
     * lifespan. (eg. unmapping and remapping a window).
     *
     * For each wl_surface, this state must not be committed more than once,
     * otherwise the `already_associated` protocol error will be raised.
     */
    public fun setSerial(serialLo: Int, serialHi: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_serial", "serial_lo=${Wire.show(serialLo)}, serial_hi=${Wire.show(serialHi)}")
        request(0).uint(serialLo).uint(serialHi).end()
    }

    /**
     * Destroy the Xwayland surface object
     *
     * Destroy the xwayland_surface_v1 object.
     *
     * Any already existing associations are unaffected by this action.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    public object Error {
        /** given wl_surface is already associated with an X11 window */
        public const val ALREADY_ASSOCIATED: Int = 0
        /** serial was not valid */
        public const val INVALID_SERIAL: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<XwaylandSurfaceV1>("xwayland_surface_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): XwaylandSurfaceV1 = XwaylandSurfaceV1(connection, id, version)
    }
}
