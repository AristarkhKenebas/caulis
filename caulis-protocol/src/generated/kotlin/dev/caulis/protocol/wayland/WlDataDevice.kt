// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType

/**
 * Data transfer device
 *
 * There is one wl_data_device per seat which can be obtained
 * from the global wl_data_device_manager singleton.
 *
 * A wl_data_device provides access to inter-client data transfer
 * mechanisms such as copy-and-paste and drag-and-drop.
 */
public class WlDataDevice private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlDataDevice> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_data_device. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Start drag-and-drop operation
         *
         * This request asks the compositor to start a drag-and-drop
         * operation on behalf of the client.
         *
         * The source argument is the data source that provides the data
         * for the eventual data transfer. If source is NULL, enter, leave
         * and motion events are sent only to the client that initiated the
         * drag and the client is expected to handle the data passing
         * internally. If source is destroyed, the drag-and-drop session will be
         * cancelled.
         *
         * The origin surface is the surface where the drag originates and
         * the client must have an active implicit grab that matches the
         * serial.
         *
         * The icon surface is an optional (can be NULL) surface that
         * provides an icon to be moved around with the cursor.  Initially,
         * the top-left corner of the icon surface is placed at the cursor
         * hotspot, but subsequent wl_surface.offset requests can move the
         * relative position. Attach requests must be confirmed with
         * wl_surface.commit as usual. The icon surface is given the role of
         * a drag-and-drop icon. If the icon surface already has another role,
         * it raises a protocol error.
         *
         * The input region is ignored for wl_surfaces with the role of a
         * drag-and-drop icon.
         *
         * The given source may not be used in any further set_selection or
         * start_drag requests. Attempting to reuse a previously-used source
         * may send a used_source error.
         */
        public fun startDrag(resource: WlDataDevice, source: WlDataSource?, origin: WlSurface, icon: WlSurface?, serial: Int)

        /**
         * Copy data to the selection
         *
         * This request asks the compositor to set the selection
         * to the data from the source on behalf of the client.
         *
         * To unset the selection, set the source to NULL.
         *
         * The given source may not be used in any further set_selection or
         * start_drag requests. Attempting to reuse a previously-used source
         * may send a used_source error.
         */
        public fun setSelection(resource: WlDataDevice, source: WlDataSource?, serial: Int)

        /**
         * Destroy data device
         *
         * This request destroys the data device.
         *
         * Since version 2.
         */
        public fun release(resource: WlDataDevice)
    }

    /**
     * Introduce a new wl_data_offer
     *
     * The data_offer event introduces a new wl_data_offer object,
     * which will subsequently be used in either the
     * data_device.enter event (for drag-and-drop) or the
     * data_device.selection event (for selections).  Immediately
     * following the data_device.data_offer event, the new data_offer
     * object will send out data_offer.offer events to describe the
     * mime types it offers.
     */
    public fun sendDataOffer(id: WlDataOffer) {
        if (!isAlive || !client.isConnected) return
        event(0).newId(id).end()
    }

    /**
     * Initiate drag-and-drop session
     *
     * This event is sent when an active drag-and-drop pointer enters
     * a surface owned by the client.  The position of the pointer at
     * enter time is provided by the x and y arguments, in surface-local
     * coordinates.
     */
    public fun sendEnter(serial: Int, surface: WlSurface, x: Double, y: Double, id: WlDataOffer?) {
        if (!isAlive || !client.isConnected) return
        event(1).uint(serial).obj(surface).fixed(x).fixed(y).obj(id).end()
    }

    /**
     * End drag-and-drop session
     *
     * This event is sent when the drag-and-drop pointer leaves the
     * surface and the session ends.  The client must destroy the
     * wl_data_offer introduced at enter time at this point.
     */
    public fun sendLeave() {
        if (!isAlive || !client.isConnected) return
        event(2).end()
    }

    /**
     * Drag-and-drop session motion
     *
     * This event is sent when the drag-and-drop pointer moves within
     * the currently focused surface. The new position of the pointer
     * is provided by the x and y arguments, in surface-local
     * coordinates.
     */
    public fun sendMotion(time: Int, x: Double, y: Double) {
        if (!isAlive || !client.isConnected) return
        event(3).uint(time).fixed(x).fixed(y).end()
    }

    /**
     * End drag-and-drop session successfully
     *
     * The event is sent when a drag-and-drop operation is ended
     * because the implicit grab is removed.
     *
     * The drag-and-drop destination is expected to honor the last action
     * received through wl_data_offer.action, if the resulting action is
     * "copy" or "move", the destination can still perform
     * wl_data_offer.receive requests, and is expected to end all
     * transfers with a wl_data_offer.finish request.
     *
     * If the resulting action is "ask", the action will not be considered
     * final. The drag-and-drop destination is expected to perform one last
     * wl_data_offer.set_actions request, or wl_data_offer.destroy in order
     * to cancel the operation.
     */
    public fun sendDrop() {
        if (!isAlive || !client.isConnected) return
        event(4).end()
    }

    /**
     * Advertise new selection
     *
     * The selection event is sent out to notify the client of a new
     * wl_data_offer for the selection for this device.  The
     * data_device.data_offer and the data_offer.offer events are
     * sent out immediately before this event to introduce the data
     * offer object.  The selection event is sent to a client
     * immediately before receiving keyboard focus and when a new
     * selection is set while the client has keyboard focus.  The
     * data_offer is valid until a new data_offer or NULL is received
     * or until the client loses keyboard focus.  Switching surface with
     * keyboard focus within the same client doesn't mean a new selection
     * will be sent.  The client must destroy the previous selection
     * data_offer, if any, upon receiving this event.
     */
    public fun sendSelection(id: WlDataOffer?) {
        if (!isAlive || !client.isConnected) return
        event(5).obj(id).end()
    }

    public object Error {
        /** given wl_surface has another role */
        public const val ROLE: Int = 0
        /** source has already been used */
        public const val USED_SOURCE: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_data_device is not implemented")
        when (opcode) {
            0 -> {
                val source = args.objOrNull(WlDataSource::class.java)
                val origin = args.obj(WlSurface::class.java)
                val icon = args.objOrNull(WlSurface::class.java)
                val serial = args.uint()
                args.end()
                handler.startDrag(this, source, origin, icon, serial)
            }
            1 -> {
                val source = args.objOrNull(WlDataSource::class.java)
                val serial = args.uint()
                args.end()
                handler.setSelection(this, source, serial)
            }
            2 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_data_device.release needs version 2")
                args.end()
                handler.release(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_data_device request $opcode")
        }
    }

    public companion object : ResourceType<WlDataDevice>("wl_data_device", 4) {
        internal override fun create(client: Client, id: Int, version: Int): WlDataDevice = WlDataDevice(client, id, version)
    }
}
