// Generated from presentation-time.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.presentationtime

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlSurface

/**
 * Timed presentation related wl_surface requests
 *
 * The main feature of this interface is accurate presentation
 * timing feedback to ensure smooth video playback while maintaining
 * audio/video synchronization. Some features use the concept of a
 * presentation clock, which is defined in the
 * presentation.clock_id event.
 *
 * A content update for a wl_surface is submitted by a
 * wl_surface.commit request. Request 'feedback' associates with
 * the wl_surface.commit and provides feedback on the content
 * update, particularly the final realized presentation time.
 *
 *
 *
 * When the final realized presentation time is available, e.g.
 * after a framebuffer flip completes, the requested
 * presentation_feedback.presented events are sent. The final
 * presentation time can differ from the compositor's predicted
 * display update time and the update's target time, especially
 * when the compositor misses its target vertical blanking period.
 */
public class WpPresentation private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WpPresentation> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wp_presentation. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Unbind from the presentation interface
         *
         * Informs the server that the client will no longer be using
         * this protocol object. Existing objects created by this object
         * are not affected.
         */
        public fun destroy(resource: WpPresentation)

        /**
         * Request presentation feedback information
         *
         * Request presentation feedback for the current content submission
         * on the given surface. This creates a new presentation_feedback
         * object, which will deliver the feedback information once. If
         * multiple presentation_feedback objects are created for the same
         * submission, they will all deliver the same information.
         *
         * For details on what information is returned, see the
         * presentation_feedback interface.
         */
        public fun feedback(resource: WpPresentation, surface: WlSurface, callback: WpPresentationFeedback)
    }

    /**
     * Clock ID for timestamps
     *
     * This event tells the client in which clock domain the
     * compositor interprets the timestamps used by the presentation
     * extension. This clock is called the presentation clock.
     *
     * The compositor sends this event when the client binds to the
     * presentation interface. The presentation clock does not change
     * during the lifetime of the client connection.
     *
     * The clock identifier is platform dependent. On POSIX platforms, the
     * identifier value is one of the clockid_t values accepted by
     * clock_gettime(). clock_gettime() is defined by POSIX.1-2001.
     *
     * Timestamps in this clock domain are expressed as tv_sec_hi,
     * tv_sec_lo, tv_nsec triples, each component being an unsigned
     * 32-bit value. Whole seconds are in tv_sec which is a 64-bit
     * value combined from tv_sec_hi and tv_sec_lo, and the
     * additional fractional part in tv_nsec as nanoseconds. Hence,
     * for valid timestamps tv_nsec must be in [0, 999999999].
     *
     * Note that clock_id applies only to the presentation clock,
     * and implies nothing about e.g. the timestamps used in the
     * Wayland core protocol input events.
     *
     * Compositors should prefer a clock which does not jump and is
     * not slewed e.g. by NTP. The absolute value of the clock is
     * irrelevant. Precision of one millisecond or better is
     * recommended. Clients must be able to query the current clock
     * value directly, not by asking the compositor.
     */
    public fun sendClockId(clkId: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(clkId).end()
    }

    /**
     * Fatal presentation errors
     */
    public object Error {
        /** invalid value in tv_nsec */
        public const val INVALID_TIMESTAMP: Int = 0
        /** invalid flag */
        public const val INVALID_FLAG: Int = 1
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wp_presentation is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val surface = args.obj(WlSurface::class.java)
                val callbackId = args.newId()
                args.end()
                val callback = client.register(WpPresentationFeedback.create(client, callbackId, version))
                handler.feedback(this, surface, callback)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wp_presentation request $opcode")
        }
    }

    public companion object : ResourceType<WpPresentation>("wp_presentation", 2) {
        internal override fun create(client: Client, id: Int, version: Int): WpPresentation = WpPresentation(client, id, version)
    }
}
