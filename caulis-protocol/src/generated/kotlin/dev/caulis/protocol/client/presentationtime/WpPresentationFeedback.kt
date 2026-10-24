// Generated from presentation-time.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.presentationtime

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlOutput

/**
 * Presentation time feedback event
 *
 * A presentation_feedback object returns an indication that a
 * wl_surface content update has become visible to the user.
 * One object corresponds to one content update submission
 * (wl_surface.commit). There are two possible outcomes: the
 * content update is presented to the user, and a presentation
 * timestamp delivered; or, the user did not see the content
 * update because it was superseded or its surface destroyed,
 * and the content update is discarded.
 *
 * Once a presentation_feedback object has delivered a 'presented'
 * or 'discarded' event it is automatically destroyed.
 */
public class WpPresentationFeedback private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<WpPresentationFeedback> get() = Companion

    /** Receives the events the compositor sends to this object. */
    public var events: Events? = null

    public interface Events {
        /**
         * Presentation synchronized to this output
         *
         * As presentation can be synchronized to only one output at a
         * time, this event tells which output it was. This event is only
         * sent prior to the presented event.
         *
         * As clients may bind to the same global wl_output multiple
         * times, this event is sent for each bound instance that matches
         * the synchronized output. If a client has not bound to the
         * right wl_output global at all, this event is not sent.
         */
        public fun syncOutput(proxy: WpPresentationFeedback, output: WlOutput) {}

        /**
         * The content update was displayed
         *
         * The associated content update was displayed to the user at the
         * indicated time (tv_sec_hi/lo, tv_nsec). For the interpretation of
         * the timestamp, see presentation.clock_id event.
         *
         * The timestamp corresponds to the time when the content update
         * turned into light the first time on the surface's main output.
         * Compositors may approximate this from the framebuffer flip
         * completion events from the system, and the latency of the
         * physical display path if known.
         *
         * This event is preceded by all related sync_output events
         * telling which output's refresh cycle the feedback corresponds
         * to, i.e. the main output for the surface. Compositors are
         * recommended to choose the output containing the largest part
         * of the wl_surface, or keeping the output they previously
         * chose. Having a stable presentation output association helps
         * clients predict future output refreshes (vblank).
         *
         * The 'refresh' argument gives the compositor's prediction of how
         * many nanoseconds after tv_sec, tv_nsec the very next output
         * refresh may occur. This is to further aid clients in
         * predicting future refreshes, i.e., estimating the timestamps
         * targeting the next few vblanks. If such prediction cannot
         * usefully be done, the argument is zero.
         *
         * For version 2 and later, if the output does not have a constant
         * refresh rate, explicit video mode switches excluded, then the
         * refresh argument must be either an appropriate rate picked by the
         * compositor (e.g. fastest rate), or 0 if no such rate exists.
         * For version 1, if the output does not have a constant refresh rate,
         * the refresh argument must be zero.
         *
         * The 64-bit value combined from seq_hi and seq_lo is the value
         * of the output's vertical retrace counter when the content
         * update was first scanned out to the display. This value must
         * be compatible with the definition of MSC in
         * GLX_OML_sync_control specification. Note, that if the display
         * path has a non-zero latency, the time instant specified by
         * this counter may differ from the timestamp's.
         *
         * If the output does not have a concept of vertical retrace or a
         * refresh cycle, or the output device is self-refreshing without
         * a way to query the refresh count, then the arguments seq_hi
         * and seq_lo must be zero.
         */
        public fun presented(proxy: WpPresentationFeedback, tvSecHi: Int, tvSecLo: Int, tvNsec: Int, refresh: Int, seqHi: Int, seqLo: Int, flags: Int) {}

        /**
         * The content update was not displayed
         *
         * The content update was never displayed to the user.
         */
        public fun discarded(proxy: WpPresentationFeedback) {}
    }

    /** bitmask of flags in presented event */
    public object Kind {
        public const val VSYNC: Int = 1
        public const val HW_CLOCK: Int = 2
        public const val HW_COMPLETION: Int = 4
        public const val ZERO_COPY: Int = 8
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
        val handler = events
        when (opcode) {
            0 -> {
                val output = args.obj(WlOutput::class.java)
                args.end()
                handler?.syncOutput(this, output)
            }
            1 -> {
                val tvSecHi = args.uint()
                val tvSecLo = args.uint()
                val tvNsec = args.uint()
                val refresh = args.uint()
                val seqHi = args.uint()
                val seqLo = args.uint()
                val flags = args.uint()
                args.end()
                handler?.presented(this, tvSecHi, tvSecLo, tvNsec, refresh, seqHi, seqLo, flags)
            }
            2 -> {
                args.end()
                handler?.discarded(this)
            }
        }
    }

    public companion object : ProxyType<WpPresentationFeedback>("wp_presentation_feedback", 2) {
        internal override fun create(connection: Connection, id: Int, version: Int): WpPresentationFeedback = WpPresentationFeedback(connection, id, version)
    }
}
