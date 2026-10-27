package dev.caulis.core

import dev.caulis.protocol.Display
import dev.caulis.protocol.presentationtime.WpPresentation
import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import dev.caulis.protocol.wayland.WlSurface

/**
 * wp_presentation: programs (video players, games) ask "when exactly did my picture reach
 * the screen?" and get the moment of the monitor's refresh, its period and a frame number.
 * With that they can keep audio and video in sync and plan their next frame precisely.
 *
 * The answers are sent by the window manager when a frame is shown ([presented]).
 */
class Presentation(display: Display) {

    init {
        display.addGlobal(WpPresentation, VERSION) { presentation ->
            presentation.requests = object : WpPresentation.Requests {
                override fun destroy(resource: WpPresentation) {}

                override fun feedback(resource: WpPresentation, surface: WlSurface, callback: WpPresentationFeedback) {
                    val target = surface.dataAs<Surface>() ?: return callback.discard()
                    target.pending.feedbackList += callback
                }
            }
            // All times we report are from the monotonic clock (it never jumps).
            presentation.sendClockId(CLOCK_MONOTONIC)
        }
    }

    companion object {
        const val VERSION = 1
        private const val CLOCK_MONOTONIC = 1

        /**
         * Tells every program in [feedbacks] that its picture was shown at [nanos]
         * (monotonic clock). [refreshNanos] is the monitor's period (0 = unknown),
         * [sequence] the monitor's frame counter, [flags] how exact this is
         * ([WpPresentationFeedback.Kind]).
         */
        fun presented(feedbacks: List<WpPresentationFeedback>, nanos: Long, refreshNanos: Long, sequence: Long, flags: Int) {
            val seconds = nanos / 1_000_000_000
            val rest = (nanos % 1_000_000_000).toInt()
            for (feedback in feedbacks) {
                if (!feedback.isAlive) continue
                feedback.sendPresented(
                    (seconds ushr 32).toInt(), seconds.toInt(), rest, refreshNanos.toInt(),
                    (sequence ushr 32).toInt(), sequence.toInt(), flags,
                )
                feedback.destroy()
            }
        }
    }
}
