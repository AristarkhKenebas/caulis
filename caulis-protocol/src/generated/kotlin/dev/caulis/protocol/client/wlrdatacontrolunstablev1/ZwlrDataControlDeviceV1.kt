// Generated from wlr-data-control-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrdatacontrolunstablev1

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType

/**
 * Manage a data device for a seat
 *
 * This interface allows a client to manage a seat's selection.
 *
 * When the seat is destroyed, this object becomes inert.
 */
public class ZwlrDataControlDeviceV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrDataControlDeviceV1> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun dataOffer(proxy: ZwlrDataControlDeviceV1, id: ZwlrDataControlOfferV1) {}

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
        public fun selection(proxy: ZwlrDataControlDeviceV1, id: ZwlrDataControlOfferV1?) {}

        /**
         * This data control is no longer valid
         *
         * This data control object is no longer valid and should be destroyed by
         * the client.
         */
        public fun finished(proxy: ZwlrDataControlDeviceV1) {}

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
        public fun primarySelection(proxy: ZwlrDataControlDeviceV1, id: ZwlrDataControlOfferV1?) {}
    }

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
    public fun setSelection(source: ZwlrDataControlSourceV1?): Unit {
        if (Wire.tracing) Wire.sent(this, "set_selection", "source=${Wire.show(source)}")
        request(0).obj(source).end()
    }

    /**
     * Destroy this data device
     *
     * Destroys the data device object.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(1).end()
        connection.destroyProxy(this)
    }

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
    public fun setPrimarySelection(source: ZwlrDataControlSourceV1?): Unit {
        check(version >= 2) { "zwlr_data_control_device_v1.set_primary_selection needs version 2, this object has $version" }
        if (Wire.tracing) Wire.sent(this, "set_primary_selection", "source=${Wire.show(source)}")
        request(2).obj(source).end()
    }

    public object Error {
        /** source given to set_selection or set_primary_selection was already used before */
        public const val USED_SOURCE: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val id = args.newId(ZwlrDataControlOfferV1, version)
                args.end()
                if (Wire.tracing) Wire.received(this, "data_offer", "id=${Wire.show(id)}")
                handler?.dataOffer(this, id)
            }
            1 -> {
                val id = args.objOrNull(ZwlrDataControlOfferV1::class.java)
                args.end()
                if (Wire.tracing) Wire.received(this, "selection", "id=${Wire.show(id)}")
                handler?.selection(this, id)
            }
            2 -> {
                args.end()
                if (Wire.tracing) Wire.received(this, "finished", "")
                handler?.finished(this)
            }
            3 -> {
                val id = args.objOrNull(ZwlrDataControlOfferV1::class.java)
                args.end()
                if (Wire.tracing) Wire.received(this, "primary_selection", "id=${Wire.show(id)}")
                handler?.primarySelection(this, id)
            }
        }
    }

    public companion object : ProxyType<ZwlrDataControlDeviceV1>("zwlr_data_control_device_v1", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrDataControlDeviceV1 = ZwlrDataControlDeviceV1(connection, id, version)
    }
}
