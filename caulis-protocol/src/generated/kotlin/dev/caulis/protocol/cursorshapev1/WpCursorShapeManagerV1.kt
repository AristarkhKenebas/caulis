// Generated from cursor-shape-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.cursorshapev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.tabletv2.ZwpTabletToolV2
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlPointer

/**
 * Cursor shape manager
 *
 * This global offers an alternative, optional way to set cursor images. This
 * new way uses enumerated cursors instead of a wl_surface like
 * wl_pointer.set_cursor does.
 *
 * Warning! The protocol described in this file is currently in the testing
 * phase. Backward compatible changes may be added together with the
 * corresponding interface version bump. Backward incompatible changes can
 * only be done by creating a new major version of the extension.
 */
public class WpCursorShapeManagerV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WpCursorShapeManagerV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wp_cursor_shape_manager_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Destroy the manager
         *
         * Destroy the cursor shape manager.
         */
        public fun destroy(resource: WpCursorShapeManagerV1)

        /**
         * Manage the cursor shape of a pointer device
         *
         * Obtain a wp_cursor_shape_device_v1 for a wl_pointer object.
         *
         * When the pointer capability is removed from the wl_seat, the
         * wp_cursor_shape_device_v1 object becomes inert.
         */
        public fun getPointer(resource: WpCursorShapeManagerV1, cursorShapeDevice: WpCursorShapeDeviceV1, pointer: WlPointer)

        /**
         * Manage the cursor shape of a tablet tool device
         *
         * Obtain a wp_cursor_shape_device_v1 for a zwp_tablet_tool_v2 object.
         *
         * When the zwp_tablet_tool_v2 is removed, the wp_cursor_shape_device_v1
         * object becomes inert.
         */
        public fun getTabletToolV2(resource: WpCursorShapeManagerV1, cursorShapeDevice: WpCursorShapeDeviceV1, tabletTool: ZwpTabletToolV2)
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wp_cursor_shape_manager_v1 is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val cursorShapeDeviceId = args.newId()
                val pointer = args.obj(WlPointer::class.java)
                args.end()
                val cursorShapeDevice = client.register(WpCursorShapeDeviceV1.create(client, cursorShapeDeviceId, version))
                if (Wire.tracing) Wire.request(this, "get_pointer", "cursor_shape_device=${Wire.show(cursorShapeDevice)}, pointer=${Wire.show(pointer)}")
                handler.getPointer(this, cursorShapeDevice, pointer)
            }
            2 -> {
                val cursorShapeDeviceId = args.newId()
                val tabletTool = args.obj(ZwpTabletToolV2::class.java)
                args.end()
                val cursorShapeDevice = client.register(WpCursorShapeDeviceV1.create(client, cursorShapeDeviceId, version))
                if (Wire.tracing) Wire.request(this, "get_tablet_tool_v2", "cursor_shape_device=${Wire.show(cursorShapeDevice)}, tablet_tool=${Wire.show(tabletTool)}")
                handler.getTabletToolV2(this, cursorShapeDevice, tabletTool)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wp_cursor_shape_manager_v1 request $opcode")
        }
    }

    public companion object : ResourceType<WpCursorShapeManagerV1>("wp_cursor_shape_manager_v1", 2) {
        internal override fun create(client: Client, id: Int, version: Int): WpCursorShapeManagerV1 = WpCursorShapeManagerV1(client, id, version)
    }
}
