// Generated from xdg-activation-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xdgactivationv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSeat
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * An exported activation handle
 *
 * An object for setting up a token and receiving a token handle that can
 * be passed as an activation token to another client.
 *
 * The object is created using the xdg_activation_v1.get_activation_token
 * request. This object should then be populated with the app_id, surface
 * and serial information and committed. The compositor shall then issue a
 * done event with the token. In case the request's parameters are invalid,
 * the compositor will provide an invalid token.
 */
public class XdgActivationTokenV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XdgActivationTokenV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * The exported activation token
         *
         * The 'done' event contains the unique token of this activation request
         * and notifies that the provider is done.
         */
        public fun done(proxy: XdgActivationTokenV1, token: String) {}
    }

    /**
     * Specifies the seat and serial of the activating event
     *
     * Provides information about the seat and serial event that requested the
     * token.
     *
     * The serial can come from an input or focus event. For instance, if a
     * click triggers the launch of a third-party client, the launcher client
     * should send a set_serial request with the serial and seat from the
     * wl_pointer.button event.
     *
     * Some compositors might refuse to activate toplevels when the token
     * doesn't have a valid and recent enough event serial.
     *
     * Must be sent before commit. This information is optional.
     */
    public fun setSerial(serial: Int, seat: WlSeat): Unit {
        if (Wire.tracing) Wire.sent(this, "set_serial", "serial=${Wire.show(serial)}, seat=${Wire.show(seat)}")
        request(0).uint(serial).obj(seat).end()
    }

    /**
     * Specifies the application being activated
     *
     * The requesting client can specify an app_id to associate the token
     * being created with it.
     *
     * Must be sent before commit. This information is optional.
     */
    public fun setAppId(appId: String): Unit {
        if (Wire.tracing) Wire.sent(this, "set_app_id", "app_id=${Wire.show(appId)}")
        request(1).string(appId).end()
    }

    /**
     * Specifies the surface requesting activation
     *
     * This request sets the surface requesting the activation. Note, this is
     * different from the surface that will be activated.
     *
     * Some compositors might refuse to activate toplevels when the token
     * doesn't have a requesting surface.
     *
     * Must be sent before commit. This information is optional.
     */
    public fun setSurface(surface: WlSurface): Unit {
        if (Wire.tracing) Wire.sent(this, "set_surface", "surface=${Wire.show(surface)}")
        request(2).obj(surface).end()
    }

    /**
     * Issues the token request
     *
     * Requests an activation token based on the different parameters that
     * have been offered through set_serial, set_surface and set_app_id.
     */
    public fun commit(): Unit {
        if (Wire.tracing) Wire.sent(this, "commit", "")
        request(3).end()
    }

    /**
     * Destroy the xdg_activation_token_v1 object
     *
     * Notify the compositor that the xdg_activation_token_v1 object will no
     * longer be used. The received token stays valid.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(4).end()
        connection.destroyProxy(this)
    }

    public object Error {
        /** The token has already been used previously */
        public const val ALREADY_USED: Int = 0
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val token = args.string()
                args.end()
                if (Wire.tracing) Wire.received(this, "done", "token=${Wire.show(token)}")
                handler?.done(this, token)
            }
        }
    }

    public companion object : ProxyType<XdgActivationTokenV1>("xdg_activation_token_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): XdgActivationTokenV1 = XdgActivationTokenV1(connection, id, version)
    }
}
