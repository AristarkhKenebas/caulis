// Generated from ext-image-copy-capture-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.extimagecopycapturev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Cursor capture session
 *
 * This object represents a cursor capture session. It extends the base
 * capture session with cursor-specific metadata.
 */
public class ExtImageCopyCaptureCursorSessionV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ExtImageCopyCaptureCursorSessionV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun enter(proxy: ExtImageCopyCaptureCursorSessionV1) {}

        /**
         * Cursor left captured area
         *
         * Sent when a cursor leaves the captured area. No "position" or "hotspot"
         * event is generated for the cursor until the cursor enters the captured
         * area again.
         */
        public fun leave(proxy: ExtImageCopyCaptureCursorSessionV1) {}

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
        public fun position(proxy: ExtImageCopyCaptureCursorSessionV1, x: Int, y: Int) {}

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
        public fun hotspot(proxy: ExtImageCopyCaptureCursorSessionV1, x: Int, y: Int) {}
    }

    /**
     * Delete this object
     *
     * Destroys the session. This request can be sent at any time by the
     * client.
     *
     * This request doesn't affect ext_image_copy_capture_frame_v1 objects created by
     * this object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

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
    public fun getCaptureSession(): ExtImageCopyCaptureSessionV1 {
        val created = connection.createProxy(ExtImageCopyCaptureSessionV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_capture_session", "session=${Wire.show(created)}")
        request(1).newId(created).end()
        return created
    }

    public object Error {
        /** get_capture_session sent twice */
        public const val DUPLICATE_SESSION: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "enter", "")
                handler?.enter(this)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "leave", "")
                handler?.leave(this)
            }
            2 -> {
                val x = args.int()
                val y = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "position", "x=${Wire.show(x)}, y=${Wire.show(y)}")
                handler?.position(this, x, y)
            }
            3 -> {
                val x = args.int()
                val y = args.int()
                args.end()
                if (Wire.tracing) Wire.received(this, "hotspot", "x=${Wire.show(x)}, y=${Wire.show(y)}")
                handler?.hotspot(this, x, y)
            }
        }
    }

    public companion object : ProxyType<ExtImageCopyCaptureCursorSessionV1>("ext_image_copy_capture_cursor_session_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ExtImageCopyCaptureCursorSessionV1 = ExtImageCopyCaptureCursorSessionV1(connection, id, version)
    }
}
