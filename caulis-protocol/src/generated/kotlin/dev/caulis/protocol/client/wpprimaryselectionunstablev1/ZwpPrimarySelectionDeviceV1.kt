// Generated from primary-selection-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wpprimaryselectionunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

public class ZwpPrimarySelectionDeviceV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwpPrimarySelectionDeviceV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Introduce a new wp_primary_selection_offer
         *
         * Introduces a new wp_primary_selection_offer object that may be used
         * to receive the current primary selection. Immediately following this
         * event, the new wp_primary_selection_offer object will send
         * wp_primary_selection_offer.offer events to describe the offered mime
         * types.
         */
        public fun dataOffer(proxy: ZwpPrimarySelectionDeviceV1, offer: ZwpPrimarySelectionOfferV1) {}

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
        public fun selection(proxy: ZwpPrimarySelectionDeviceV1, id: ZwpPrimarySelectionOfferV1?) {}
    }

    /**
     * Set the primary selection
     *
     * Replaces the current selection. The previous owner of the primary
     * selection will receive a wp_primary_selection_source.cancelled event.
     *
     * To unset the selection, set the source to NULL.
     */
    public fun setSelection(source: ZwpPrimarySelectionSourceV1?, serial: Int): Unit {
        if (Wire.tracing) Wire.sent(this, "set_selection", "source=${Wire.show(source)}, serial=${Wire.show(serial)}")
        request(0).obj(source).uint(serial).end()
    }

    /**
     * Destroy the primary selection device
     *
     * Destroy the primary selection device.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val offer = args.newId(ZwpPrimarySelectionOfferV1, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "data_offer", "offer=${Wire.show(offer)}")
                handler?.dataOffer(this, offer)
            }
            1 -> {
                val id = args.objOrNull(ZwpPrimarySelectionOfferV1::class.java)
                args.end()
                if (Wire.tracing) Wire.received(this, "selection", "id=${Wire.show(id)}")
                handler?.selection(this, id)
            }
        }
    }

    public companion object : ProxyType<ZwpPrimarySelectionDeviceV1>("zwp_primary_selection_device_v1", 1) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwpPrimarySelectionDeviceV1 = ZwpPrimarySelectionDeviceV1(connection, id, version)
    }
}
