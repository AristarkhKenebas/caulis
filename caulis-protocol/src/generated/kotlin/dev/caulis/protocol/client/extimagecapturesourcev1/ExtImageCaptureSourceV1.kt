// Generated from ext-image-capture-source-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extimagecapturesourcev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

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
public class ExtImageCaptureSourceV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtImageCaptureSourceV1> get() = Companion

    /**
     * Delete this object
     *
     * Destroys the image capture source. This request may be sent at any time
     * by the client.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ExtImageCaptureSourceV1>("ext_image_capture_source_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtImageCaptureSourceV1 = ExtImageCaptureSourceV1(connection, id, version)
    }
}
