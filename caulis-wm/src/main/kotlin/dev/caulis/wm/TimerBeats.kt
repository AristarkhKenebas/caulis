package dev.caulis.wm

/**
 * How long until a repeating timer's next turn, at [nowMillis]. Timers of a second or more keep to
 * a common beat (the multiples of their period): ten once-a-second timers wake the processor once
 * a second, not ten times. The first turn never comes before a whole period has passed.
 */
internal fun timerDelayMillis(every: Long, nowMillis: Long, first: Boolean): Long {
    if (every < 1000) return every
    val toBeat = every - nowMillis % every
    return if (first) every + toBeat % every else toBeat
}
