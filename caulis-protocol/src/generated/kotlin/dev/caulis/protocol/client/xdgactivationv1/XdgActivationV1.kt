// Generated from xdg-activation-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.xdgactivationv1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

/**
 * Interface for activating surfaces
 *
 * A global interface used for informing the compositor about applications
 * being activated or started, or for applications to request to be
 * activated.
 */
public class XdgActivationV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<XdgActivationV1> get() = Companion

    /**
     * Destroy the xdg_activation object
     *
     * Notify the compositor that the xdg_activation object will no longer be
     * used.
     *
     * The child objects created via this interface are unaffected and should
     * be destroyed separately.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Requests a token
     *
     * Creates an xdg_activation_token_v1 object that will provide
     * the initiating client with a unique token for this activation. This
     * token should be offered to the clients to be activated.
     */
    public fun getActivationToken(): XdgActivationTokenV1 {
        val created = connection.createProxy(XdgActivationTokenV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_activation_token", "id=${Wire.show(created)}")
        request(1).newId(created).end()
        return created
    }

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
    public fun activate(token: String, surface: WlSurface): Unit {
        if (Wire.tracing) Wire.sent(this, "activate", "token=${Wire.show(token)}, surface=${Wire.show(surface)}")
        request(2).string(token).obj(surface).end()
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<XdgActivationV1>("xdg_activation_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): XdgActivationV1 = XdgActivationV1(connection, id, version)
    }
}
