package dev.caulis.wm

import dev.caulis.core.appearance.ColorSchedule
import dev.caulis.core.appearance.ColorScheme
import java.time.ZonedDateTime

/**
 * Which scheme programs are told at a moment: one switched to by hand (until the schedule's
 * next change), else the schedule's, else the configuration's [base].
 */
internal class ColorSchemes {
    var base = ColorScheme.DEFAULT
    var schedule: ColorSchedule? = null

    private var byHand: ColorScheme? = null
    private var byHandUntil: ZonedDateTime? = null

    /** [scheme] by hand from [now] (null: back to the schedule or the base). */
    fun switch(scheme: ColorScheme?, now: ZonedDateTime) {
        byHand = scheme
        byHandUntil = if (scheme == null) null else schedule?.at(now)?.second
    }

    /** The scheme at [now], and when to look again (null: it changes only when told). */
    fun at(now: ZonedDateTime): Pair<ColorScheme, ZonedDateTime?> {
        byHandUntil?.let { until -> if (!now.isBefore(until)) switch(null, now) }
        val planned = schedule?.at(now)
        return (byHand ?: planned?.first ?: base) to planned?.second
    }
}
