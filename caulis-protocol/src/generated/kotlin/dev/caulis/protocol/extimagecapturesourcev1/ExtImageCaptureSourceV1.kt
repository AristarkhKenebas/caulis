// Generated from ext-image-capture-source-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extimagecapturesourcev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Opaque image capture source object
 *
 * The image capture source object is an opaque descriptor for a capturable
 * resource.  This resource may be any sort of entity from which an image
 * may be derived.
 *
 * Note, because ext_image_capture_source_v1 objects are created from multiple
 * independent factory interfaces, the ext_image_capture_source_v1 interface is
 * frozen at version 1.
 */
public class ExtImageCaptureSourceV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtImageCaptureSourceV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_image_capture_source_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Delete this object
         *
         * Destroys the image capture source. This request may be sent at any time
         * by the client.
         */
        public fun destroy(resource: ExtImageCaptureSourceV1)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_image_capture_source_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_image_capture_source_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtImageCaptureSourceV1>("ext_image_capture_source_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtImageCaptureSourceV1 = ExtImageCaptureSourceV1(client, id, version)
    }
}
