// Generated from ext-image-copy-capture-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.extimagecopycapturev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Cursor capture session
 *
 * This object represents a cursor capture session. It extends the base
 * capture session with cursor-specific metadata.
 */
public class ExtImageCopyCaptureCursorSessionV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ExtImageCopyCaptureCursorSessionV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements ext_image_copy_capture_cursor_session_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Delete this object
         *
         * Destroys the session. This request can be sent at any time by the
         * client.
         *
         * This request doesn't affect ext_image_copy_capture_frame_v1 objects created by
         * this object.
         */
        public fun destroy(resource: ExtImageCopyCaptureCursorSessionV1)

        /**
         * Get image copy capturer session
         *
         * Gets the image copy capture session for this cursor session.
         *
         * The session will produce frames of the cursor image. The compositor may
         * pause the session when the cursor leaves the captured area.
         *
         * This request must not be sent more than once, or else the
         * duplicate_session protocol error is raised.
         */
        public fun getCaptureSession(resource: ExtImageCopyCaptureCursorSessionV1, session: ExtImageCopyCaptureSessionV1)
    }

    /**
     * Cursor entered captured area
     *
     * Sent when a cursor enters the captured area. It shall be generated
     * before the "position" and "hotspot" events when and only when a cursor
     * enters the area.
     *
     * The cursor enters the captured area when the cursor image intersects
     * with the captured area. Note, this is different from e.g.
     * wl_pointer.enter.
     */
    public fun sendEnter() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "enter", "")
        event(0).end()
    }

    /**
     * Cursor left captured area
     *
     * Sent when a cursor leaves the captured area. No "position" or "hotspot"
     * event is generated for the cursor until the cursor enters the captured
     * area again.
     */
    public fun sendLeave() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "leave", "")
        event(1).end()
    }

    /**
     * Position changed
     *
     * Cursors outside the image capture source do not get captured and no
     * event will be generated for them.
     *
     * The given position is the position of the cursor's hotspot and it is
     * relative to the main buffer's top left corner in transformed buffer
     * pixel coordinates. The coordinates may be negative or greater than the
     * main buffer size.
     */
    public fun sendPosition(x: Int, y: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "position", "x=${Wire.show(x)}, y=${Wire.show(y)}")
        event(2).int(x).int(y).end()
    }

    /**
     * Hotspot changed
     *
     * The hotspot describes the offset between the cursor image and the
     * position of the input device.
     *
     * The given coordinates are the hotspot's offset from the origin in
     * buffer coordinates.
     *
     * Clients should not apply the hotspot immediately: the hotspot becomes
     * effective when the next ext_image_copy_capture_frame_v1.ready event is received.
     *
     * Compositors may delay this event until the client captures a new frame.
     */
    public fun sendHotspot(x: Int, y: Int) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "hotspot", "x=${Wire.show(x)}, y=${Wire.show(y)}")
        event(3).int(x).int(y).end()
    }

    public object Error {
        /** get_capture_session sent twice */
        public const val DUPLICATE_SESSION: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "ext_image_copy_capture_cursor_session_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val sessionId = args.newId()
                args.end()
                val session = client.register(ExtImageCopyCaptureSessionV1.create(client, sessionId, version))
                if (Wire.tracing) Wire.request(this, "get_capture_session", "session=${Wire.show(session)}")
                handler.getCaptureSession(this, session)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown ext_image_copy_capture_cursor_session_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ExtImageCopyCaptureCursorSessionV1>("ext_image_copy_capture_cursor_session_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ExtImageCopyCaptureCursorSessionV1 = ExtImageCopyCaptureCursorSessionV1(client, id, version)
    }
}
