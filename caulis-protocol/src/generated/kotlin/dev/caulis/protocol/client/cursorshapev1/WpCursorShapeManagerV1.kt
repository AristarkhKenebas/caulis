// Generated from cursor-shape-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.cursorshapev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.tabletv2.ZwpTabletToolV2
import dev.caulis.protocol.client.wayland.WlPointer

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
public class WpCursorShapeManagerV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WpCursorShapeManagerV1> get() = Companion

    /**
     * Destroy the manager
     *
     * Destroy the cursor shape manager.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

    /**
     * Manage the cursor shape of a pointer device
     *
     * Obtain a wp_cursor_shape_device_v1 for a wl_pointer object.
     *
     * When the pointer capability is removed from the wl_seat, the
     * wp_cursor_shape_device_v1 object becomes inert.
     */
    public fun getPointer(pointer: WlPointer): WpCursorShapeDeviceV1 {
        val created = connection.createProxy(WpCursorShapeDeviceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_pointer", "cursor_shape_device=${Wire.show(created)}, pointer=${Wire.show(pointer)}")
        request(1).newId(created).obj(pointer).end()
        return created
    }

    /**
     * Manage the cursor shape of a tablet tool device
     *
     * Obtain a wp_cursor_shape_device_v1 for a zwp_tablet_tool_v2 object.
     *
     * When the zwp_tablet_tool_v2 is removed, the wp_cursor_shape_device_v1
     * object becomes inert.
     */
    public fun getTabletToolV2(tabletTool: ZwpTabletToolV2): WpCursorShapeDeviceV1 {
        val created = connection.createProxy(WpCursorShapeDeviceV1, this.version)
        if (Wire.tracing) Wire.sent(this, "get_tablet_tool_v2", "cursor_shape_device=${Wire.show(created)}, tablet_tool=${Wire.show(tabletTool)}")
        request(2).newId(created).obj(tabletTool).end()
        return created
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<WpCursorShapeManagerV1>("wp_cursor_shape_manager_v1", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): WpCursorShapeManagerV1 = WpCursorShapeManagerV1(connection, id, version)
    }
}
