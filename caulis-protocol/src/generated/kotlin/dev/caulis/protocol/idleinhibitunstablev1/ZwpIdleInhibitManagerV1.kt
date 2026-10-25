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
import dev.caulis.protocol.wayland.WlSurface

/**
 * Control behavior when display idles
 *
 * This interface permits inhibiting the idle behavior such as screen
 * blanking, locking, and screensaving.  The client binds the idle manager
 * globally, then creates idle-inhibitor objects for each surface.
 *
 * Warning! The protocol described in this file is experimental and
 * backward incompatible changes may be made. Backward compatible changes
 * may be added together with the corresponding interface version bump.
 * Backward incompatible changes are done by bumping the version number in
 * the protocol and interface names and resetting the interface version.
 * Once the protocol is to be declared stable, the 'z' prefix and the
 * version number in the protocol and interface names are removed and the
 * interface version number is reset.
 */
public class ZwpIdleInhibitManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpIdleInhibitManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_idle_inhibit_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the idle inhibitor object
         *
         * Destroy the inhibit manager.
         */
        public fun destroy(resource: ZwpIdleInhibitManagerV1)

        /**
         * Create a new inhibitor object
         *
         * Create a new inhibitor object associated with the given surface.
         */
        public fun createInhibitor(resource: ZwpIdleInhibitManagerV1, id: ZwpIdleInhibitorV1, surface: WlSurface)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_idle_inhibit_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                args.end()
                val id = client.register(ZwpIdleInhibitorV1.create(client, idId, version))
                if (Wire.tracing) Wire.request(this, "create_inhibitor", "id=${Wire.show(id)}, surface=${Wire.show(surface)}")
                handler.createInhibitor(this, id, surface)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_idle_inhibit_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpIdleInhibitManagerV1>("zwp_idle_inhibit_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpIdleInhibitManagerV1 = ZwpIdleInhibitManagerV1(client, id, version)
    }
}
