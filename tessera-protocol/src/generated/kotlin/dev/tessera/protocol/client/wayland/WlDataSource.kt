// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.client.wayland

import dev.tessera.ffi.Fd
import dev.tessera.protocol.client.Connection
import dev.tessera.protocol.client.EventReader
import dev.tessera.protocol.client.Proxy
import dev.tessera.protocol.client.ProxyType

/**
 * Offer to transfer data
 *
 * The wl_data_source object is the source side of a wl_data_offer.
 * It is created by the source client in a data transfer and
 * provides a way to describe the offered data and a way to respond
 * to requests to transfer the data.
 */
public class WlDataSource private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WlDataSource> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * A target accepts an offered mime type
         *
         * Sent when a target accepts pointer_focus or motion events.  If
         * a target does not accept any of the offered types, type is NULL.
         *
         * Used for feedback during drag-and-drop.
         */
        public fun target(proxy: WlDataSource, mimeType: String?) {}

        /**
         * Send the data
         *
         * Request for data from the client.  Send the data as the
         * specified mime type over the passed file descriptor, then
         * close it.
         */
        public fun send(proxy: WlDataSource, mimeType: String, fd: Fd) {}

        /**
         * Selection was cancelled
         *
         * This data source is no longer valid. There are several reasons why
         * this could happen:
         *
         * - The data source has been replaced by another data source.
         * - The drag-and-drop operation was performed, but the drop destination
         *   did not accept any of the mime types offered through
         *   wl_data_source.target.
         * - The drag-and-drop operation was performed, but the drop destination
         *   did not select any of the actions present in the mask offered through
         *   wl_data_source.action.
         * - The drag-and-drop operation was performed but didn't happen over a
         *   surface.
         * - The compositor cancelled the drag-and-drop operation (e.g. compositor
         *   dependent timeouts to avoid stale drag-and-drop transfers).
         *
         * The client should clean up and destroy this data source.
         *
         * For objects of version 2 or older, wl_data_source.cancelled will
         * only be emitted if the data source was replaced by another data
         * source.
         */
        public fun cancelled(proxy: WlDataSource) {}

        /**
         * The drag-and-drop operation physically finished
         *
         * The user performed the drop action. This event does not indicate
         * acceptance, wl_data_source.cancelled may still be emitted afterwards
         * if the drop destination does not accept any mime type.
         *
         * However, this event might not be received if the compositor cancelled
         * the drag-and-drop operation before this event could happen.
         *
         * Note that the data_source may still be used in the future and should
         * not be destroyed here.
         *
         * Since version 3.
         */
        public fun dndDropPerformed(proxy: WlDataSource) {}

        /**
         * The drag-and-drop operation concluded
         *
         * The drop destination finished interoperating with this data
         * source, so the client is now free to destroy this data source and
         * free all associated data.
         *
         * If the action used to perform the operation was "move", the
         * source can now delete the transferred data.
         *
         * Since version 3.
         */
        public fun dndFinished(proxy: WlDataSource) {}

        /**
         * Notify the selected action
         *
         * This event indicates the action selected by the compositor after
         * matching the source/destination side actions. Only one action (or
         * none) will be offered here.
         *
         * This event can be emitted multiple times during the drag-and-drop
         * operation, mainly in response to destination side changes through
         * wl_data_offer.set_actions, and as the data device enters/leaves
         * surfaces.
         *
         * It is only possible to receive this event after
         * wl_data_source.dnd_drop_performed if the drag-and-drop operation
         * ended in an "ask" action, in which case the final wl_data_source.action
         * event will happen immediately before wl_data_source.dnd_finished.
         *
         * Compositors may also change the selected action on the fly, mainly
         * in response to keyboard modifier changes during the drag-and-drop
         * operation.
         *
         * The most recent action received is always the valid one. The chosen
         * action may change alongside negotiation (e.g. an "ask" action can turn
         * into a "move" operation), so the effects of the final action must
         * always be applied in wl_data_source.dnd_finished.
         *
         * Clients can trigger cursor surface changes from this point, so
         * they reflect the current action.
         *
         * Since version 3.
         */
        public fun action(proxy: WlDataSource, dndAction: Int) {}
    }

    /**
     * Add an offered mime type
     *
     * This request adds a mime type to the set of mime types
     * advertised to targets.  Can be called several times to offer
     * multiple types.
     */
    public fun offer(mimeType: String): Unit {
        request(0).string(mimeType).end()
    }

    /**
     * Destroy the data source
     *
     * Destroy the data source.
     */
    public fun destroy(): Unit {
        request(1).end()
        connection.destroyProxy(this)
    }

    /**
     * Set the available drag-and-drop actions
     *
     * Sets the actions that the source side client supports for this
     * operation. This request may trigger wl_data_source.action and
     * wl_data_offer.action events if the compositor needs to change the
     * selected action.
     *
     * The dnd_actions argument must contain only values expressed in the
     * wl_data_device_manager.dnd_actions enum, otherwise it will result
     * in a protocol error.
     *
     * This request must be made once only, and can only be made on sources
     * used in drag-and-drop, so it must be performed before
     * wl_data_device.start_drag. Attempting to use the source other than
     * for drag-and-drop will raise a protocol error.
     *
     * Since version 3.
     */
    public fun setActions(dndActions: Int): Unit {
        check(version >= 3) { "wl_data_source.set_actions needs version 3, this object has $version" }
        request(2).uint(dndActions).end()
    }

    public object Error {
        /** action mask contains invalid values */
        public const val INVALID_ACTION_MASK: Int = 0
        /** source doesn't accept this request */
        public const val INVALID_SOURCE: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val mimeType = args.stringOrNull()
                args.end()
                handler?.target(this, mimeType)
            }
            1 -> {
                val mimeType = args.string()
                val fd = args.fd()
                args.end()
                handler?.send(this, mimeType, fd)
            }
            2 -> {
                args.end()
                handler?.cancelled(this)
            }
            3 -> {
                args.end()
                handler?.dndDropPerformed(this)
            }
            4 -> {
                args.end()
                handler?.dndFinished(this)
            }
            5 -> {
                val dndAction = args.uint()
                args.end()
                handler?.action(this, dndAction)
            }
        }
    }

    public companion object : ProxyType<WlDataSource>("wl_data_source", 4) {
        internal override fun create(connection: Connection, id: Int, version: Int): WlDataSource = WlDataSource(connection, id, version)
    }
}
