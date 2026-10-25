// Generated from idle-inhibit-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.idleinhibitunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Context object for inhibiting idle behavior
 *
 * An idle inhibitor prevents the output that the associated surface is
 * visible on from being set to a state where it is not visually usable due
 * to lack of user interaction (e.g. blanked, dimmed, locked, set to power
 * save, etc.)  Any screensaver processes are also blocked from displaying.
 *
 * If the surface is destroyed, unmapped, becomes occluded, loses
 * visibility, or otherwise becomes not visually relevant for the user, the
 * idle inhibitor will not be honored by the compositor; if the surface
 * subsequently regains visibility the inhibitor takes effect once again.
 * Likewise, the inhibitor isn't honored if the system was already idled at
 * the time the inhibitor was established, although if the system later
 * de-idles and re-idles the inhibitor will take effect.
 */
public class ZwpIdleInhibitorV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpIdleInhibitorV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_idle_inhibitor_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the idle inhibitor object
         *
         * Remove the inhibitor effect from the associated wl_surface.
         */
        public fun destroy(resource: ZwpIdleInhibitorV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_idle_inhibitor_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_idle_inhibitor_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpIdleInhibitorV1>("zwp_idle_inhibitor_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpIdleInhibitorV1 = ZwpIdleInhibitorV1(client, id, version)
    }
}
