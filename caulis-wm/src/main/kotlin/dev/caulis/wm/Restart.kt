package dev.caulis.wm

import kotlin.time.Duration

/**
 * When a program of [Autostart] that ended is started again: never ([NEVER], the default),
 * when it crashed ([onCrash]), whenever it ended ([always]), or when your own rule says so
 * ([whenEnded]) — at most [maxTimes] times within [withinMillis] (then it is given up, and
 * the log says so).
 *
 * A crash is any end but a clean one (status 0) and being stopped from outside (Ctrl+C,
 * `kill`: statuses 130, 137 and 143) — a segfault (139) or an abort (134) count.
 */
class Restart private constructor(
    /** How many times at most it is started again within [withinMillis]. */
    val maxTimes: Int,
    /** The time the restarts are counted in, in milliseconds. */
    val withinMillis: Long,
    private val wanted: (status: Int) -> Boolean,
    private val name: String,
) {
    init {
        require(maxTimes >= 0) { "restart: maxTimes must not be negative ($maxTimes)" }
        require(withinMillis > 0) { "restart: within must be more than 0 ($withinMillis ms)" }
    }

    /** True if a program that ended with [status] is to be started again (the limit aside). */
    fun wants(status: Int): Boolean = maxTimes > 0 && wanted(status)

    override fun toString() = if (maxTimes == 0) "never" else "$name (at most $maxTimes times in ${withinMillis / 1000} s)"

    companion object {
        /** Never started again (the default). */
        @JvmField
        val NEVER = Restart(0, 1, { false }, "never")

        /** Statuses of a program stopped from outside (Ctrl+C, killed, terminated): no crash. */
        private val STOPPED = setOf(130, 137, 143)

        /** Started again when it crashed (see [Restart]), at most [maxTimes] times within [withinMillis]. */
        @JvmStatic
        @JvmOverloads
        fun onCrash(maxTimes: Int = 3, withinMillis: Long = 60_000): Restart =
            Restart(maxTimes, withinMillis, { status -> status != 0 && status !in STOPPED }, "on crash")

        /** [onCrash] with the time as a Duration: `onCrash(maxTimes = 3, within = 1.minutes)`. */
        @JvmSynthetic
        fun onCrash(maxTimes: Int = 3, within: Duration): Restart = onCrash(maxTimes, within.inWholeMilliseconds)

        /** Started again whenever it ended (also cleanly), at most [maxTimes] times within [withinMillis]. */
        @JvmStatic
        @JvmOverloads
        fun always(maxTimes: Int = 3, withinMillis: Long = 60_000): Restart = Restart(maxTimes, withinMillis, { true }, "always")

        /** [always] with the time as a Duration. */
        @JvmSynthetic
        fun always(maxTimes: Int = 3, within: Duration): Restart = always(maxTimes, within.inWholeMilliseconds)

        /** Started again when [wanted] says so for its exit status, at most [maxTimes] times within [withinMillis]. */
        @JvmStatic
        fun whenEnded(maxTimes: Int, withinMillis: Long, wanted: java.util.function.IntPredicate): Restart =
            Restart(maxTimes, withinMillis, { wanted.test(it) }, "your rule")
    }
}
