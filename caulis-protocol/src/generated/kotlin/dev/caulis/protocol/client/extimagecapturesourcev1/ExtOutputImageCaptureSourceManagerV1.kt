// Generated from ext-image-capture-source-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extimagecapturesourcev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlOutput

/**
 * Image capture source manager for outputs
 *
 * A manager for creating image capture source objects for wl_output objects.
 */
public class ExtOutputImageCaptureSourceManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtOutputImageCaptureSourceManagerV1> get() = Companion

    /**
     * Create source object for output
     *
     * Creates a source object for an output. Images captured from this source
     * will show the same content as the output. Some elements may be omitted,
     * such as cursors and overlays that have been marked as transparent to
     * capturing.
     */
    public fun createSource(output: WlOutput): ExtImageCaptureSourceV1 {
        val created = connection.createProxy(ExtImageCaptureSourceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_source", "source=${Wire.show(created)}, output=${Wire.show(output)}")
        request(0).newId(created).obj(output).end()
        return created
    }

    /**
     * Delete this object
     *
     * Destroys the manager. This request may be sent at any time by the client
     * and objects created by the manager will remain valid after its
     * destruction.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ExtOutputImageCaptureSourceManagerV1>("ext_output_image_capture_source_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtOutputImageCaptureSourceManagerV1 = ExtOutputImageCaptureSourceManagerV1(connection, id, version)
    }
}
