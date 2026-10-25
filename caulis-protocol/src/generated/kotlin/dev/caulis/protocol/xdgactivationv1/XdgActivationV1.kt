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
import dev.caulis.protocol.wayland.WlSurface

/**
 * Interface for activating surfaces
 *
 * A global interface used for informing the compositor about applications
 * being activated or started, or for applications to request to be
 * activated.
 */
public class XdgActivationV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<XdgActivationV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements xdg_activation_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the xdg_activation object
         *
         * Notify the compositor that the xdg_activation object will no longer be
         * used.
         *
         * The child objects created via this interface are unaffected and should
         * be destroyed separately.
         */
        public fun destroy(resource: XdgActivationV1)

        /**
         * Requests a token
         *
         * Creates an xdg_activation_token_v1 object that will provide
         * the initiating client with a unique token for this activation. This
         * token should be offered to the clients to be activated.
         */
        public fun getActivationToken(resource: XdgActivationV1, id: XdgActivationTokenV1)

        /**
         * Notify new interaction being available
         *
         * Requests surface activation. It's up to the compositor to display
         * this information as desired, for example by placing the surface above
         * the rest.
         *
         * The compositor may know who requested this by checking the activation
         * token and might decide not to follow through with the activation if it's
         * considered unwanted.
         *
         * Compositors can ignore unknown activation tokens when an invalid
         * token is passed.
         */
        public fun activate(resource: XdgActivationV1, token: String, surface: WlSurface)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "xdg_activation_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                args.end()
                val id = client.register(XdgActivationTokenV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "get_activation_token", "id=${Wire.show(id)}")
                handler.getActivationToken(this, id)
            }
            2 -> {
                val token = args.string()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "activate", "token=${Wire.show(token)}, surface=${Wire.show(surface)}")
                handler.activate(this, token, surface)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown xdg_activation_v1 request $opcode")
        }
    }

    public companion object : ResourceType<XdgActivationV1>("xdg_activation_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): XdgActivationV1 = XdgActivationV1(client, id, version)
    }
}
