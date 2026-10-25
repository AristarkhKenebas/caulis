// Generated from xdg-activation-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.xdgactivationv1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface

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
public class XdgActivationTokenV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<XdgActivationTokenV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements xdg_activation_token_v1. */
    public var requests: Requests? = null

    public interface Requests {
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
        public fun setSerial(resource: XdgActivationTokenV1, serial: Int, seat: WlSeat)

        /**
         * Specifies the application being activated
         *
         * The requesting client can specify an app_id to associate the token
         * being created with it.
         *
         * Must be sent before commit. This information is optional.
         */
        public fun setAppId(resource: XdgActivationTokenV1, appId: String)

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
        public fun setSurface(resource: XdgActivationTokenV1, surface: WlSurface)

        /**
         * Issues the token request
         *
         * Requests an activation token based on the different parameters that
         * have been offered through set_serial, set_surface and set_app_id.
         */
        public fun commit(resource: XdgActivationTokenV1)

        /**
         * Destroy the xdg_activation_token_v1 object
         *
         * Notify the compositor that the xdg_activation_token_v1 object will no
         * longer be used. The received token stays valid.
         */
        public fun destroy(resource: XdgActivationTokenV1)
    }

    /**
     * The exported activation token
     *
     * The 'done' event contains the unique token of this activation request
     * and notifies that the provider is done.
     */
    public fun sendDone(token: String) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "done", "token=${Wire.show(token)}")
        event(0).string(token).end()
    }

    public object Error {
        /** The token has already been used previously */
        public const val ALREADY_USED: Int = 0
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "xdg_activation_token_v1 is not implemented")
        when (opcode) {
            0 -> {
                val serial = args.uint()
                val seat = args.obj(WlSeat::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_serial", "serial=${Wire.show(serial)}, seat=${Wire.show(seat)}")
                handler.setSerial(this, serial, seat)
            }
            1 -> {
                val appId = args.string()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_app_id", "app_id=${Wire.show(appId)}")
                handler.setAppId(this, appId)
            }
            2 -> {
                val surface = args.obj(WlSurface::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_surface", "surface=${Wire.show(surface)}")
                handler.setSurface(this, surface)
            }
            3 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "commit", "")
                handler.commit(this)
            }
            4 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown xdg_activation_token_v1 request $opcode")
        }
    }

    public companion object : ResourceType<XdgActivationTokenV1>("xdg_activation_token_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): XdgActivationTokenV1 = XdgActivationTokenV1(client, id, version)
    }
}
