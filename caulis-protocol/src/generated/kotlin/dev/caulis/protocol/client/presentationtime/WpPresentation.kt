// Generated from presentation-time.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.presentationtime

import dev.caulis.protocol.Wire
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlSurface

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
public class WpPresentation private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WpPresentation> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
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
        public fun clockId(proxy: WpPresentation, clkId: Int) {}
    }

    /**
     * Unbind from the presentation interface
     *
     * Informs the server that the client will no longer be using
     * this protocol object. Existing objects created by this object
     * are not affected.
     */
    public fun destroy(): Unit {
        if (Wire.tracing) Wire.sent(this, "destroy", "")
        request(0).end()
        connection.destroyProxy(this)
    }

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
    public fun feedback(surface: WlSurface): WpPresentationFeedback {
        val created = connection.createProxy(WpPresentationFeedback, this.version)
        if (Wire.tracing) Wire.sent(this, "feedback", "surface=${Wire.show(surface)}, callback=${Wire.show(created)}")
        request(1).obj(surface).newId(created).end()
        return created
    }

    /** fatal presentation errors */
    public object Error {
        /** invalid value in tv_nsec */
        public const val INVALID_TIMESTAMP: Int = 0
        /** invalid flag */
        public const val INVALID_FLAG: Int = 1
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val clkId = args.uint()
                args.end()
                if (Wire.tracing) Wire.received(this, "clock_id", "clk_id=${Wire.show(clkId)}")
                handler?.clockId(this, clkId)
            }
        }
    }

    public companion object : ProxyType<WpPresentation>("wp_presentation", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): WpPresentation = WpPresentation(connection, id, version)
    }
}
