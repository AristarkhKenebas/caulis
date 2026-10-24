package dev.caulis.core.input

import dev.caulis.core.cursor.CursorTheme
import dev.caulis.protocol.Display
import dev.caulis.protocol.cursorshapev1.WpCursorShapeDeviceV1
import dev.caulis.protocol.cursorshapev1.WpCursorShapeManagerV1

/**
 * cursor-shape-v1: programs ask for a cursor by name ("text", "pointer", ...) instead of
 * drawing it themselves, so every program shows the same cursor theme at the same size —
 * the one the compositor is set to. GTK 4, Qt 6, Firefox, foot and others use it.
 */
class CursorShape(display: Display, private val seat: Seat) {

    init {
        display.addGlobal(WpCursorShapeManagerV1, VERSION) { manager ->
            manager.requests = object : WpCursorShapeManagerV1.Requests {
                override fun getPointer(resource: WpCursorShapeManagerV1, cursorShapeDevice: WpCursorShapeDeviceV1, pointer: dev.caulis.protocol.wayland.WlPointer) =
                    serve(cursorShapeDevice)

                // Drawing tablets are not supported yet; their cursor requests are accepted and ignored.
                override fun getTabletToolV2(resource: WpCursorShapeManagerV1, cursorShapeDevice: WpCursorShapeDeviceV1, tabletTool: dev.caulis.protocol.tabletv2.ZwpTabletToolV2) =
                    serve(cursorShapeDevice, tablet = true)

                override fun destroy(resource: WpCursorShapeManagerV1) {}
            }
        }
    }

    private fun serve(device: WpCursorShapeDeviceV1, tablet: Boolean = false) {
        device.requests = object : WpCursorShapeDeviceV1.Requests {
            override fun setShape(resource: WpCursorShapeDeviceV1, serial: Int, shape: Int) {
                // Shapes 35 and 36 came with version 2.
                val known = if (resource.version >= 2) CursorTheme.SHAPES.size else 34
                if (shape !in 1..known) {
                    resource.postError(WpCursorShapeDeviceV1.Error.INVALID_SHAPE, "no cursor shape $shape")
                    return
                }
                if (!tablet) seat.setCursorShape(resource.client, CursorTheme.SHAPES[shape - 1])
            }

            override fun destroy(resource: WpCursorShapeDeviceV1) {}
        }
    }

    companion object {
        const val VERSION = 2
    }
}
