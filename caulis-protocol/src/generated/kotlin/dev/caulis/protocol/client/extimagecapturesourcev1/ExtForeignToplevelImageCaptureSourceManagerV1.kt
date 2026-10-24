// Generated from ext-image-capture-source-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extimagecapturesourcev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.extforeigntoplevellistv1.ExtForeignToplevelHandleV1

/**
 * Image capture source manager for foreign toplevels
 *
 * A manager for creating image capture source objects for
 * ext_foreign_toplevel_handle_v1 objects.
 */
public class ExtForeignToplevelImageCaptureSourceManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtForeignToplevelImageCaptureSourceManagerV1> get() = Companion

    /**
     * Create source object for foreign toplevel
     *
     * Creates a source object for a foreign toplevel handle. Images captured
     * from this source will show the same content as the toplevel.
     */
    public fun createSource(toplevelHandle: ExtForeignToplevelHandleV1): ExtImageCaptureSourceV1 {
        val created = connection.createProxy(ExtImageCaptureSourceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_source", "source=${Wire.show(created)}, toplevel_handle=${Wire.show(toplevelHandle)}")
        request(0).newId(created).obj(toplevelHandle).end()
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

    public companion object : ProxyType<ExtForeignToplevelImageCaptureSourceManagerV1>("ext_foreign_toplevel_image_capture_source_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtForeignToplevelImageCaptureSourceManagerV1 = ExtForeignToplevelImageCaptureSourceManagerV1(connection, id, version)
    }
}
