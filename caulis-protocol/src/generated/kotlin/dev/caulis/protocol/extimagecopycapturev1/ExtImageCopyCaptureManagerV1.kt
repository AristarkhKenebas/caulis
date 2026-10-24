// Generated from ext-image-copy-capture-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extimagecopycapturev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.extimagecapturesourcev1.ExtImageCaptureSourceV1
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlPointer

/**
 * Manager to inform clients and begin capturing
 *
 * This object is a manager which offers requests to start capturing from a
 * source.
 */
public class ExtImageCopyCaptureManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtImageCopyCaptureManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_image_copy_capture_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
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
        public fun createSession(resource: ExtImageCopyCaptureManagerV1, session: ExtImageCopyCaptureSessionV1, source: ExtImageCaptureSourceV1, options: Int)

        /**
         * Capture the pointer cursor of an image capture source
         *
         * Create a cursor capturing session for the pointer of an image capture
         * source.
         */
        public fun createPointerCursorSession(resource: ExtImageCopyCaptureManagerV1, session: ExtImageCopyCaptureCursorSessionV1, source: ExtImageCaptureSourceV1, pointer: WlPointer)

        /**
         * Destroy the manager
         *
         * Destroy the manager object.
         *
         * Other objects created via this interface are unaffected.
         */
        public fun destroy(resource: ExtImageCopyCaptureManagerV1)
    }

    public object Error {
        /** invalid option flag */
        public const val INVALID_OPTION: Int = 1
    }

    /**
     * Bit flags: combine with `or`.
     */
    public object Options {
        /** paint cursors onto captured frames */
        public const val PAINT_CURSORS: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_image_copy_capture_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                val sessionId = args.newId()
                val source = args.obj(ExtImageCaptureSourceV1::class.java)
                val options = args.uint()
                args.end()
                val session = client.register(ExtImageCopyCaptureSessionV1.create(client, sessionId, version))
                if (Wire.tracing) Wire.request(this, "create_session", "session=${Wire.show(session)}, source=${Wire.show(source)}, options=${Wire.show(options)}")
                handler.createSession(this, session, source, options)
            }
            1 -> {
                val sessionId = args.newId()
                val source = args.obj(ExtImageCaptureSourceV1::class.java)
                val pointer = args.obj(WlPointer::class.java)
                args.end()
                val session = client.register(ExtImageCopyCaptureCursorSessionV1.create(client, sessionId, version))
                if (Wire.tracing) Wire.request(this, "create_pointer_cursor_session", "session=${Wire.show(session)}, source=${Wire.show(source)}, pointer=${Wire.show(pointer)}")
                handler.createPointerCursorSession(this, session, source, pointer)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_image_copy_capture_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtImageCopyCaptureManagerV1>("ext_image_copy_capture_manager_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtImageCopyCaptureManagerV1 = ExtImageCopyCaptureManagerV1(client, id, version)
    }
}
