// Generated from primary-selection-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wpprimaryselectionunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

public class ZwpPrimarySelectionDeviceV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwpPrimarySelectionDeviceV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwp_primary_selection_device_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Set the primary selection
         *
         * Replaces the current selection. The previous owner of the primary
         * selection will receive a wp_primary_selection_source.cancelled event.
         *
         * To unset the selection, set the source to NULL.
         */
        public fun setSelection(resource: ZwpPrimarySelectionDeviceV1, source: ZwpPrimarySelectionSourceV1?, serial: Int)

        /**
         * Destroy the primary selection device
         *
         * Destroy the primary selection device.
         */
        public fun destroy(resource: ZwpPrimarySelectionDeviceV1)
    }

    /**
     * Introduce a new wp_primary_selection_offer
     *
     * Introduces a new wp_primary_selection_offer object that may be used
     * to receive the current primary selection. Immediately following this
     * event, the new wp_primary_selection_offer object will send
     * wp_primary_selection_offer.offer events to describe the offered mime
     * types.
     */
    public fun sendDataOffer(offer: ZwpPrimarySelectionOfferV1) {
        if (!isAlive || !client.isConnected) return
        if (Wire.tracing) Wire.event(this, "data_offer", "offer=${Wire.show(offer)}")
        event(0).newId(offer).end()
    }

    /**
     * Advertise a new primary selection
     *
     * The wp_primary_selection_device.selection event is sent to notify the
     * client of a new primary selection. This event is sent after the
     * wp_primary_selection.data_offer event introducing this object, and after
     * the offer has announced its mimetypes through
     * wp_primary_selection_offer.offer.
     *
     * The data_offer is valid until a new offer or NULL is received
     * or until the client loses keyboard focus. The client must destroy the
     * previous selection data_offer, if any, upon receiving this event.
     */
    public fun sendSelection(id: ZwpPrimarySelectionOfferV1?) {
        if (!isAlive || !client.isConnected) return
        if (id != null && !id.isAlive) return
        if (Wire.tracing) Wire.event(this, "selection", "id=${Wire.show(id)}")
        event(1).obj(id).end()
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwp_primary_selection_device_v1 is not implemented")
        when (opcode) {
            0 -> {
                val source = args.objOrNull(ZwpPrimarySelectionSourceV1::class.java)
                val serial = args.uint()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_selection", "source=${Wire.show(source)}, serial=${Wire.show(serial)}")
                handler.setSelection(this, source, serial)
            }
            1 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwp_primary_selection_device_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwpPrimarySelectionDeviceV1>("zwp_primary_selection_device_v1", 1) {
        internal override fun create(client: Client, id: Int, version: Int): ZwpPrimarySelectionDeviceV1 = ZwpPrimarySelectionDeviceV1(client, id, version)
    }
}
