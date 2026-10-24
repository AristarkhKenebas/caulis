// Generated from wayland.xml. Do not edit: run ./gradlew :tessera-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.tessera.protocol.wayland

import dev.tessera.ffi.Fd
import dev.tessera.protocol.Client
import dev.tessera.protocol.MessageReader
import dev.tessera.protocol.ProtocolException
import dev.tessera.protocol.Resource
import dev.tessera.protocol.ResourceType

/**
 * Offer to transfer data
 *
 * The wl_data_source object is the source side of a wl_data_offer.
 * It is created by the source client in a data transfer and
 * provides a way to describe the offered data and a way to respond
 * to requests to transfer the data.
 */
public class WlDataSource private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlDataSource> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_data_source. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Add an offered mime type
         *
         * This request adds a mime type to the set of mime types
         * advertised to targets.  Can be called several times to offer
         * multiple types.
         */
        public fun offer(resource: WlDataSource, mimeType: String)

        /**
         * Destroy the data source
         *
         * Destroy the data source.
         */
        public fun destroy(resource: WlDataSource)

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
        public fun setActions(resource: WlDataSource, dndActions: Int)
    }

    /**
     * A target accepts an offered mime type
     *
     * Sent when a target accepts pointer_focus or motion events.  If
     * a target does not accept any of the offered types, type is NULL.
     *
     * Used for feedback during drag-and-drop.
     */
    public fun sendTarget(mimeType: String?) {
        if (!isAlive || !client.isConnected) return
        event(0).string(mimeType).end()
    }

    /**
     * Send the data
     *
     * Request for data from the client.  Send the data as the
     * specified mime type over the passed file descriptor, then
     * close it.
     */
    public fun sendSend(mimeType: String, fd: Fd) {
        if (!isAlive || !client.isConnected) return
        event(1).string(mimeType).fd(fd).end()
    }

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
    public fun sendCancelled() {
        if (!isAlive || !client.isConnected) return
        event(2).end()
    }

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
    public fun sendDndDropPerformed() {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        event(3).end()
    }

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
    public fun sendDndFinished() {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        event(4).end()
    }

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
    public fun sendAction(dndAction: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        event(5).uint(dndAction).end()
    }

    public object Error {
        /** action mask contains invalid values */
        public const val INVALID_ACTION_MASK: Int = 0
        /** source doesn't accept this request */
        public const val INVALID_SOURCE: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_data_source is not implemented")
        when (opcode) {
            0 -> {
                val mimeType = args.string()
                args.end()
                handler.offer(this, mimeType)
            }
            1 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            2 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_data_source.set_actions needs version 3")
                val dndActions = args.uint()
                args.end()
                handler.setActions(this, dndActions)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_data_source request $opcode")
        }
    }

    public companion object : ResourceType<WlDataSource>("wl_data_source", 4) {
        internal override fun create(client: Client, id: Int, version: Int): WlDataSource = WlDataSource(client, id, version)
    }
}
