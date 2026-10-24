// Generated from ext-image-capture-source-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extimagecapturesourcev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.extforeigntoplevellistv1.ExtForeignToplevelHandleV1
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Image capture source manager for foreign toplevels
 *
 * A manager for creating image capture source objects for
 * ext_foreign_toplevel_handle_v1 objects.
 */
public class ExtForeignToplevelImageCaptureSourceManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtForeignToplevelImageCaptureSourceManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_foreign_toplevel_image_capture_source_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create source object for foreign toplevel
         *
         * Creates a source object for a foreign toplevel handle. Images captured
         * from this source will show the same content as the toplevel.
         */
        public fun createSource(resource: ExtForeignToplevelImageCaptureSourceManagerV1, source: ExtImageCaptureSourceV1, toplevelHandle: ExtForeignToplevelHandleV1)

        /**
         * Delete this object
         *
         * Destroys the manager. This request may be sent at any time by the client
         * and objects created by the manager will remain valid after its
         * destruction.
         */
        public fun destroy(resource: ExtForeignToplevelImageCaptureSourceManagerV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_foreign_toplevel_image_capture_source_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                val sourceId = args.newId()
                val toplevelHandle = args.obj(ExtForeignToplevelHandleV1::class.java)
                args.end()
                val source = client.register(ExtImageCaptureSourceV1.create(client, sourceId, version))
                if (Wire.tracing) Wire.request(this, "create_source", "source=${Wire.show(source)}, toplevel_handle=${Wire.show(toplevelHandle)}")
                handler.createSource(this, source, toplevelHandle)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_foreign_toplevel_image_capture_source_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtForeignToplevelImageCaptureSourceManagerV1>("ext_foreign_toplevel_image_capture_source_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtForeignToplevelImageCaptureSourceManagerV1 = ExtForeignToplevelImageCaptureSourceManagerV1(client, id, version)
    }
}
