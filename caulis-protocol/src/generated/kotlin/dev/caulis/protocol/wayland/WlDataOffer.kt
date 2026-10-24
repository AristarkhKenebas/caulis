// Generated from wayland.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wayland

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType

/**
 * Offer to transfer data
 *
 * A wl_data_offer represents a piece of data offered for transfer
 * by another client (the source client).  It is used by the
 * copy-and-paste and drag-and-drop mechanisms.  The offer
 * describes the different mime types that the data can be
 * converted to and provides the mechanism for transferring the
 * data directly from the source client.
 */
public class WlDataOffer private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WlDataOffer> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wl_data_offer. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Accept one of the offered mime types
         *
         * Indicate that the client can accept the given mime type, or
         * NULL for not accepted.
         *
         * For objects of version 2 or older, this request is used by the
         * client to give feedback whether the client can receive the given
         * mime type, or NULL if none is accepted; the feedback does not
         * determine whether the drag-and-drop operation succeeds or not.
         *
         * For objects of version 3 or newer, this request determines the
         * final result of the drag-and-drop operation. If the end result
         * is that no mime types were accepted, the drag-and-drop operation
         * will be cancelled and the corresponding drag source will receive
         * wl_data_source.cancelled. Clients may still use this event in
         * conjunction with wl_data_source.action for feedback.
         */
        public fun accept(resource: WlDataOffer, serial: Int, mimeType: String?)

        /**
         * Request that the data is transferred
         *
         * To transfer the offered data, the client issues this request
         * and indicates the mime type it wants to receive.  The transfer
         * happens through the passed file descriptor (typically created
         * with the pipe system call).  The source client writes the data
         * in the mime type representation requested and then closes the
         * file descriptor.
         *
         * The receiving client reads from the read end of the pipe until
         * EOF and then closes its end, at which point the transfer is
         * complete.
         *
         * This request may happen multiple times for different mime types,
         * both before and after wl_data_device.drop. Drag-and-drop destination
         * clients may preemptively fetch data or examine it more closely to
         * determine acceptance.
         */
        public fun receive(resource: WlDataOffer, mimeType: String, fd: Fd)

        /**
         * Destroy data offer
         *
         * Destroy the data offer.
         */
        public fun destroy(resource: WlDataOffer)

        /**
         * The offer will no longer be used
         *
         * Notifies the compositor that the drag destination successfully
         * finished the drag-and-drop operation.
         *
         * Upon receiving this request, the compositor will emit
         * wl_data_source.dnd_finished on the drag source client.
         *
         * It is a client error to perform other requests than
         * wl_data_offer.destroy after this one. It is also an error to perform
         * this request after a NULL mime type has been set in
         * wl_data_offer.accept or no action was received through
         * wl_data_offer.action.
         *
         * If wl_data_offer.finish request is received for a non drag and drop
         * operation, the invalid_finish protocol error is raised.
         *
         * Since version 3.
         */
        public fun finish(resource: WlDataOffer)

        /**
         * Set the available/preferred drag-and-drop actions
         *
         * Sets the actions that the destination side client supports for
         * this operation. This request may trigger the emission of
         * wl_data_source.action and wl_data_offer.action events if the compositor
         * needs to change the selected action.
         *
         * This request can be called multiple times throughout the
         * drag-and-drop operation, typically in response to wl_data_device.enter
         * or wl_data_device.motion events.
         *
         * This request determines the final result of the drag-and-drop
         * operation. If the end result is that no action is accepted,
         * the drag source will receive wl_data_source.cancelled.
         *
         * The dnd_actions argument must contain only values expressed in the
         * wl_data_device_manager.dnd_actions enum, and the preferred_action
         * argument must only contain one of those values set, otherwise it
         * will result in a protocol error.
         *
         * While managing an "ask" action, the destination drag-and-drop client
         * may perform further wl_data_offer.receive requests, and is expected
         * to perform one last wl_data_offer.set_actions request with a preferred
         * action other than "ask" (and optionally wl_data_offer.accept) before
         * requesting wl_data_offer.finish, in order to convey the action selected
         * by the user. If the preferred action is not in the
         * wl_data_offer.source_actions mask, an error will be raised.
         *
         * If the "ask" action is dismissed (e.g. user cancellation), the client
         * is expected to perform wl_data_offer.destroy right away.
         *
         * This request can only be made on drag-and-drop offers, a protocol error
         * will be raised otherwise.
         *
         * Since version 3.
         */
        public fun setActions(resource: WlDataOffer, dndActions: Int, preferredAction: Int)
    }

    /**
     * Advertise offered mime type
     *
     * Sent immediately after creating the wl_data_offer object.  One
     * event per offered mime type.
     */
    public fun sendOffer(mimeType: String) {
        if (!isAlive || !client.isConnected) return
        event(0).string(mimeType).end()
    }

    /**
     * Notify the source-side available actions
     *
     * This event indicates the actions offered by the data source. It
     * will be sent immediately after creating the wl_data_offer object,
     * or anytime the source side changes its offered actions through
     * wl_data_source.set_actions.
     *
     * Since version 3.
     */
    public fun sendSourceActions(sourceActions: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        event(1).uint(sourceActions).end()
    }

    /**
     * Notify the selected action
     *
     * This event indicates the action selected by the compositor after
     * matching the source/destination side actions. Only one action (or
     * none) will be offered here.
     *
     * This event can be emitted multiple times during the drag-and-drop
     * operation in response to destination side action changes through
     * wl_data_offer.set_actions.
     *
     * This event will no longer be emitted after wl_data_device.drop
     * happened on the drag-and-drop destination, the client must
     * honor the last action received, or the last preferred one set
     * through wl_data_offer.set_actions when handling an "ask" action.
     *
     * Compositors may also change the selected action on the fly, mainly
     * in response to keyboard modifier changes during the drag-and-drop
     * operation.
     *
     * The most recent action received is always the valid one. Prior to
     * receiving wl_data_device.drop, the chosen action may change (e.g.
     * due to keyboard modifiers being pressed). At the time of receiving
     * wl_data_device.drop the drag-and-drop destination must honor the
     * last action received.
     *
     * Action changes may still happen after wl_data_device.drop,
     * especially on "ask" actions, where the drag-and-drop destination
     * may choose another action afterwards. Action changes happening
     * at this stage are always the result of inter-client negotiation, the
     * compositor shall no longer be able to induce a different action.
     *
     * Upon "ask" actions, it is expected that the drag-and-drop destination
     * may potentially choose a different action and/or mime type,
     * based on wl_data_offer.source_actions and finally chosen by the
     * user (e.g. popping up a menu with the available options). The
     * final wl_data_offer.set_actions and wl_data_offer.accept requests
     * must happen before the call to wl_data_offer.finish.
     *
     * Since version 3.
     */
    public fun sendAction(dndAction: Int) {
        if (!isAlive || !client.isConnected) return
        if (version < 3) return // the client is too old for this event
        event(2).uint(dndAction).end()
    }

    public object Error {
        /** finish request was called untimely */
        public const val INVALID_FINISH: Int = 0
        /** action mask contains invalid values */
        public const val INVALID_ACTION_MASK: Int = 1
        /** action argument has an invalid value */
        public const val INVALID_ACTION: Int = 2
        /** offer doesn't accept this request */
        public const val INVALID_OFFER: Int = 3
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wl_data_offer is not implemented")
        when (opcode) {
            0 -> {
                val serial = args.uint()
                val mimeType = args.stringOrNull()
                args.end()
                handler.accept(this, serial, mimeType)
            }
            1 -> {
                val mimeType = args.string()
                val fd = args.fd()
                args.end()
                handler.receive(this, mimeType, fd)
            }
            2 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            3 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_data_offer.finish needs version 3")
                args.end()
                handler.finish(this)
            }
            4 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "wl_data_offer.set_actions needs version 3")
                val dndActions = args.uint()
                val preferredAction = args.uint()
                args.end()
                handler.setActions(this, dndActions, preferredAction)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wl_data_offer request $opcode")
        }
    }

    public companion object : ResourceType<WlDataOffer>("wl_data_offer", 4) {
        internal override fun create(client: Client, id: Int, version: Int): WlDataOffer = WlDataOffer(client, id, version)
    }
}
