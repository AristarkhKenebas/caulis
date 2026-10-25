// Generated from wlr-data-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrdatacontrolunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Manage a data device for a seat
 *
 * This interface allows a client to manage a seat's selection.
 *
 * When the seat is destroyed, this object becomes inert.
 */
public class ZwlrDataControlDeviceV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrDataControlDeviceV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_data_control_device_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Copy data to the selection
         *
         * This request asks the compositor to set the selection to the data from
         * the source on behalf of the client.
         *
         * The given source may not be used in any further set_selection or
         * set_primary_selection requests. Attempting to use a previously used
         * source is a protocol error.
         *
         * To unset the selection, set the source to NULL.
         */
        public fun setSelection(resource: ZwlrDataControlDeviceV1, source: ZwlrDataControlSourceV1?)

        /**
         * Destroy this data device
         *
         * Destroys the data device object.
         */
        public fun destroy(resource: ZwlrDataControlDeviceV1)

        /**
         * Copy data to the primary selection
         *
         * This request asks the compositor to set the primary selection to the
         * data from the source on behalf of the client.
         *
         * The given source may not be used in any further set_selection or
         * set_primary_selection requests. Attempting to use a previously used
         * source is a protocol error.
         *
         * To unset the primary selection, set the source to NULL.
         *
         * The compositor will ignore this request if it does not support primary
         * selection.
         *
         * Since version 2.
         */
        public fun setPrimarySelection(resource: ZwlrDataControlDeviceV1, source: ZwlrDataControlSourceV1?)
    }

    /**
     * Introduce a new wlr_data_control_offer
     *
     * The data_offer event introduces a new wlr_data_control_offer object,
     * which will subsequently be used in either the
     * wlr_data_control_device.selection event (for the regular clipboard
     * selections) or the wlr_data_control_device.primary_selection event (for
     * the primary clipboard selections). Immediately following the
     * wlr_data_control_device.data_offer event, the new data_offer object
     * will send out wlr_data_control_offer.offer events to describe the MIME
     * types it offers.
     */
    public fun sendDataOffer(id: ZwlrDataControlOfferV1) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "data_offer", "id=${Wire.show(id)}")
        event(0).newId(id).end()
    }

    /**
     * Advertise new selection
     *
     * The selection event is sent out to notify the client of a new
     * wlr_data_control_offer for the selection for this device. The
     * wlr_data_control_device.data_offer and the wlr_data_control_offer.offer
     * events are sent out immediately before this event to introduce the data
     * offer object. The selection event is sent to a client when a new
     * selection is set. The wlr_data_control_offer is valid until a new
     * wlr_data_control_offer or NULL is received. The client must destroy the
     * previous selection wlr_data_control_offer, if any, upon receiving this
     * event.
     *
     * The first selection event is sent upon binding the
     * wlr_data_control_device object.
     */
    public fun sendSelection(id: ZwlrDataControlOfferV1?) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "selection", "id=${Wire.show(id)}")
        event(1).obj(id).end()
    }

    /**
     * This data control is no longer valid
     *
     * This data control object is no longer valid and should be destroyed by
     * the client.
     */
    public fun sendFinished() {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "finished", "")
        event(2).end()
    }

    /**
     * Advertise new primary selection
     *
     * The primary_selection event is sent out to notify the client of a new
     * wlr_data_control_offer for the primary selection for this device. The
     * wlr_data_control_device.data_offer and the wlr_data_control_offer.offer
     * events are sent out immediately before this event to introduce the data
     * offer object. The primary_selection event is sent to a client when a
     * new primary selection is set. The wlr_data_control_offer is valid until
     * a new wlr_data_control_offer or NULL is received. The client must
     * destroy the previous primary selection wlr_data_control_offer, if any,
     * upon receiving this event.
     *
     * If the compositor supports primary selection, the first
     * primary_selection event is sent upon binding the
     * wlr_data_control_device object.
     *
     * Since version 2.
     */
    public fun sendPrimarySelection(id: ZwlrDataControlOfferV1?) {
        if (!isAlive || !client.isConnected) return
        if (version < 2) return // the client is too old for this event
        if (Wire.tracing) Wire.event(this, "primary_selection", "id=${Wire.show(id)}")
        event(3).obj(id).end()
    }

    public object Error {
        /** source given to set_selection or set_primary_selection was already used before */
        public const val USED_SOURCE: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_data_control_device_v1 is not implemented")
        when (opcode) {
            0 -> {
                val source = args.objOrNull(ZwlrDataControlSourceV1::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_selection", "source=${Wire.show(source)}")
                handler.setSelection(this, source)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            2 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_data_control_device_v1.set_primary_selection needs version 2")
                val source = args.objOrNull(ZwlrDataControlSourceV1::class.java)
                args.end()
                if (Wire.tracing) Wire.request(this, "set_primary_selection", "source=${Wire.show(source)}")
                handler.setPrimarySelection(this, source)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_data_control_device_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrDataControlDeviceV1>("zwlr_data_control_device_v1", 2) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrDataControlDeviceV1 = ZwlrDataControlDeviceV1(client, id, version)
    }
}
