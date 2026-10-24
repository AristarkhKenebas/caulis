// Generated from cursor-shape-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.cursorshapev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Cursor shape for a device
 *
 * This interface allows clients to set the cursor shape.
 */
public class WpCursorShapeDeviceV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WpCursorShapeDeviceV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wp_cursor_shape_device_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the cursor shape device
         *
         * Destroy the cursor shape device.
         *
         * The device cursor shape remains unchanged.
         */
        public fun destroy(resource: WpCursorShapeDeviceV1)

        /**
         * Set device cursor to the shape
         *
         * Sets the device cursor to the specified shape. The compositor will
         * change the cursor image based on the specified shape.
         *
         * The cursor actually changes only if the input device focus is one of
         * the requesting client's surfaces. If any, the previous cursor image
         * (surface or shape) is replaced.
         *
         * The "shape" argument must be a valid enum entry, otherwise the
         * invalid_shape protocol error is raised.
         *
         * This is similar to the wl_pointer.set_cursor and
         * zwp_tablet_tool_v2.set_cursor requests, but this request accepts a
         * shape instead of contents in the form of a surface. Clients can mix
         * set_cursor and set_shape requests.
         *
         * The serial parameter must match the latest wl_pointer.enter or
         * zwp_tablet_tool_v2.proximity_in serial number sent to the client.
         * Otherwise the request will be ignored.
         */
        public fun setShape(resource: WpCursorShapeDeviceV1, serial: Int, shape: Int)
    }

    /**
     * Cursor shapes
     */
    public object Shape {
        /** default cursor */
        public const val DEFAULT: Int = 1
        /** a context menu is available for the object under the cursor */
        public const val CONTEXT_MENU: Int = 2
        /** help is available for the object under the cursor */
        public const val HELP: Int = 3
        /** pointer that indicates a link or another interactive element */
        public const val POINTER: Int = 4
        /** progress indicator */
        public const val PROGRESS: Int = 5
        /** program is busy, user should wait */
        public const val WAIT: Int = 6
        /** a cell or set of cells may be selected */
        public const val CELL: Int = 7
        /** simple crosshair */
        public const val CROSSHAIR: Int = 8
        /** text may be selected */
        public const val TEXT: Int = 9
        /** vertical text may be selected */
        public const val VERTICAL_TEXT: Int = 10
        /** drag-and-drop: alias of/shortcut to something is to be created */
        public const val ALIAS: Int = 11
        /** drag-and-drop: something is to be copied */
        public const val COPY: Int = 12
        /** drag-and-drop: something is to be moved */
        public const val MOVE: Int = 13
        /** drag-and-drop: the dragged item cannot be dropped at the current cursor location */
        public const val NO_DROP: Int = 14
        /** drag-and-drop: the requested action will not be carried out */
        public const val NOT_ALLOWED: Int = 15
        /** drag-and-drop: something can be grabbed */
        public const val GRAB: Int = 16
        /** drag-and-drop: something is being grabbed */
        public const val GRABBING: Int = 17
        /** resizing: the east border is to be moved */
        public const val E_RESIZE: Int = 18
        /** resizing: the north border is to be moved */
        public const val N_RESIZE: Int = 19
        /** resizing: the north-east corner is to be moved */
        public const val NE_RESIZE: Int = 20
        /** resizing: the north-west corner is to be moved */
        public const val NW_RESIZE: Int = 21
        /** resizing: the south border is to be moved */
        public const val S_RESIZE: Int = 22
        /** resizing: the south-east corner is to be moved */
        public const val SE_RESIZE: Int = 23
        /** resizing: the south-west corner is to be moved */
        public const val SW_RESIZE: Int = 24
        /** resizing: the west border is to be moved */
        public const val W_RESIZE: Int = 25
        /** resizing: the east and west borders are to be moved */
        public const val EW_RESIZE: Int = 26
        /** resizing: the north and south borders are to be moved */
        public const val NS_RESIZE: Int = 27
        /** resizing: the north-east and south-west corners are to be moved */
        public const val NESW_RESIZE: Int = 28
        /** resizing: the north-west and south-east corners are to be moved */
        public const val NWSE_RESIZE: Int = 29
        /** resizing: that the item/column can be resized horizontally */
        public const val COL_RESIZE: Int = 30
        /** resizing: that the item/row can be resized vertically */
        public const val ROW_RESIZE: Int = 31
        /** something can be scrolled in any direction */
        public const val ALL_SCROLL: Int = 32
        /** something can be zoomed in */
        public const val ZOOM_IN: Int = 33
        /** something can be zoomed out */
        public const val ZOOM_OUT: Int = 34
        /** drag-and-drop: the user will select which action will be carried out (non-css value) */
        public const val DND_ASK: Int = 35
        /** resizing: something can be moved or resized in any direction (non-css value) */
        public const val ALL_RESIZE: Int = 36
    }

    public object Error {
        /** the specified shape value is invalid */
        public const val INVALID_SHAPE: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wp_cursor_shape_device_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val serial = args.uint()
                val shape = args.uint()
                args.end()
                handler.setShape(this, serial, shape)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wp_cursor_shape_device_v1 request $opcode")
        }
    }

    public companion object : ResourceType<WpCursorShapeDeviceV1>("wp_cursor_shape_device_v1", 2) {
        internal override fun create(client: Client, id: Int, version: Int): WpCursorShapeDeviceV1 = WpCursorShapeDeviceV1(client, id, version)
    }
}
