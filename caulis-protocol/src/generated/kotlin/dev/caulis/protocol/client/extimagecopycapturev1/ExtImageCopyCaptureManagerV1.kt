// Generated from ext-image-copy-capture-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extimagecopycapturev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.extimagecapturesourcev1.ExtImageCaptureSourceV1
import dev.caulis.protocol.client.wayland.WlPointer

/**
 * Manager to inform clients and begin capturing
 *
 * This object is a manager which offers requests to start capturing from a
 * source.
 */
public class ExtImageCopyCaptureManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtImageCopyCaptureManagerV1> get() = Companion

    /**
     * Capture an image capture source
     *
     * Create a capturing session for an image capture source.
     *
     * If the paint_cursors option is set, cursors shall be composited onto
     * the captured frame. The cursor must not be composited onto the frame
     * if this flag is not set.
     *
     * If the options bitfield is invalid, the invalid_option protocol error
     * is sent.
     */
    public fun createSession(source: ExtImageCaptureSourceV1, options: Int): ExtImageCopyCaptureSessionV1 {
        val created = connection.createProxy(ExtImageCopyCaptureSessionV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_session", "session=${Wire.show(created)}, source=${Wire.show(source)}, options=${Wire.show(options)}")
        request(0).newId(created).obj(source).uint(options).end()
        return created
    }

    /**
     * Capture the pointer cursor of an image capture source
     *
     * Create a cursor capturing session for the pointer of an image capture
     * source.
     */
    public fun createPointerCursorSession(source: ExtImageCaptureSourceV1, pointer: WlPointer): ExtImageCopyCaptureCursorSessionV1 {
        val created = connection.createProxy(ExtImageCopyCaptureCursorSessionV1, this.version)
        if (Wire.tracing) Wire.sent(this, "create_pointer_cursor_session", "session=${Wire.show(created)}, source=${Wire.show(source)}, pointer=${Wire.show(pointer)}")
        request(1).newId(created).obj(source).obj(pointer).end()
        return created
    }

    /**
     * Destroy the manager
     *
     * Destroy the manager object.
     *
     * Other objects created via this interface are unaffected.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(2).end()
        connection.destroyProxy(this)
    }

    public object Error {
        /** invalid option flag */
        public const val INVALID_OPTION: Int = 1
    }

    public object Options {
        /** paint cursors onto captured frames */
        public const val PAINT_CURSORS: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ExtImageCopyCaptureManagerV1>("ext_image_copy_capture_manager_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtImageCopyCaptureManagerV1 = ExtImageCopyCaptureManagerV1(connection, id, version)
    }
}
