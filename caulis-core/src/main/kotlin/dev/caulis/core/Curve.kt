package dev.caulis.core

import kotlin.math.pow

/**
 * How an animation moves over its time: [at] gets how much of the time has passed (0 to 1)
 * and gives how far along it is (0 to 1; a little past 1 for a lively overshoot).
 *
 * ```
 * Curve.EASE_OUT                        // fast at first, gently slowing down (the default)
 * Curve.bezier(0.05, 0.9, 0.1, 1.05)    // as Hyprland's (and CSS) cubic-bezier
 * Curve.spring(stiffness = 300.0, damping = 18.0) // a spring: it takes as long as it needs
 * Curve { t -> t * t }                  // your own
 * ```
 */
fun interface Curve {
    fun at(t: Double): Double

    /** How long an animation along it takes when the animation does not say (a spring's own time), or null. */
    val naturalMillis: Long? get() = null

    /**
     * What a speed the moving thing already had when this animation began still adds to where it
     * is, [t] of the time gone: 0 at both ends, rising at first as fast as the time itself. The
     * thing is at `from + (to - from) * at(t) + speed * seconds * carried(t)` (speed in units a
     * second, seconds the animation's time): turning back mid way is then a soft curve, not a
     * corner. The usual shape dies out early; a spring answers with its own law.
     */
    fun carried(t: Double): Double {
        if (t <= 0.0 || t >= 1.0) return 0.0
        val rest = 1 - t
        return t * rest * rest * rest * rest
    }

    companion object {
        /**
         * A cubic Bézier curve from (0, 0) to (1, 1) with the control points ([x1], [y1]) and
         * ([x2], [y2]) — the same numbers as Hyprland's `bezier` and CSS's `cubic-bezier`. A
         * [y1] or [y2] past 0..1 overshoots.
         */
        @JvmStatic
        fun bezier(x1: Double, y1: Double, x2: Double, y2: Double): Curve {
            require(x1 in 0.0..1.0 && x2 in 0.0..1.0) { "x1 and x2 are 0 to 1 (time cannot go back), not $x1 and $x2" }
            require(y1.isFinite() && y2.isFinite()) { "y1 and y2 must be numbers, not $y1 and $y2" }
            fun along(a: Double, b: Double, s: Double) = 3 * a * s * (1 - s) * (1 - s) + 3 * b * s * s * (1 - s) + s * s * s
            return Curve { t ->
                if (t <= 0.0) return@Curve 0.0
                if (t >= 1.0) return@Curve 1.0
                // The point of the curve at time t: x(s) = t, by halving (x grows with s).
                var low = 0.0
                var high = 1.0
                var s = t
                for (i in 0 until 40) {
                    val x = along(x1, x2, s)
                    if (Math.abs(x - t) < 1e-7) break
                    if (x < t) low = s else high = s
                    s = (low + high) / 2
                }
                along(y1, y2, s)
            }
        }

        /**
         * A spring pulling to the end from rest: [stiffness] how strongly, [damping] how much
         * it is held back (less: it bounces past the end), [mass] how heavy. An animation along
         * it lasts until the spring is still ([naturalMillis]), unless it says otherwise.
         * Worked out by its law at each moment, nothing kept but its numbers.
         */
        @JvmStatic
        @JvmOverloads
        fun spring(stiffness: Double = 200.0, damping: Double = 20.0, mass: Double = 1.0): Curve {
            require(stiffness > 0 && damping > 0 && mass > 0) { "a spring's stiffness, damping and mass are more than 0" }
            return Spring(stiffness / mass, damping / mass)
        }

        /**
         * A spring of [dampingRatio] (1: no bounce and no slower than it has to be; less: it
         * bounces) and [stiffness], as niri and Compose say it.
         */
        @JvmStatic
        @JvmOverloads
        fun springRatio(dampingRatio: Double = 1.0, stiffness: Double = 800.0): Curve {
            require(dampingRatio > 0 && stiffness > 0) { "a spring's damping ratio and stiffness are more than 0" }
            return Spring(stiffness, 2 * dampingRatio * Math.sqrt(stiffness))
        }

        /**
         * A spring without a bounce fitted into the animation's time, whatever that is: it starts
         * from rest, is nine tenths there at two fifths of the time and comes to rest softly exactly
         * at the end. With [carried], a change of mind half way is one soft movement.
         */
        @JvmField val SETTLE: Curve = object : Curve {
            private val strength = 10.0
            private val end = 1 - (1 + strength) * Math.exp(-strength)

            override fun at(t: Double): Double {
                if (t <= 0.0) return 0.0
                if (t >= 1.0) return 1.0
                return (1 - (1 + strength * t) * Math.exp(-strength * t)) / end
            }

            override fun carried(t: Double): Double = if (t <= 0.0 || t >= 1.0) 0.0 else t * Math.exp(-strength * t)
        }

        /** The same speed all the way. */
        @JvmField val LINEAR = Curve { it }

        /** Fast at first, gently slowing down: things arrive softly (the default). */
        @JvmField val EASE_OUT = Curve { t -> 1 - (1 - t).pow(3) }

        /** Slow, fast, slow. */
        @JvmField val EASE_IN_OUT = Curve { t -> if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2 }

        /** Slow at both ends (smoothstep): how pictures melt into each other. */
        @JvmField val SMOOTH = Curve { t -> t * t * (3 - 2 * t) }

        /**
         * A ready curve by its name: "linear", "ease-out" (also "default"), "ease-in-out",
         * "smooth", "overshoot", "settle"; null for another name.
         */
        @JvmStatic
        fun named(name: String): Curve? = when (name.trim().lowercase()) {
            "linear" -> LINEAR
            "ease-out", "easeout", "default" -> EASE_OUT
            "ease-in-out", "easeinout" -> EASE_IN_OUT
            "smooth" -> SMOOTH
            "overshoot" -> OVERSHOOT
            "settle" -> SETTLE
            else -> null
        }

        /** Goes a little past the end and comes back: lively. */
        @JvmField val OVERSHOOT = Curve { t ->
            val c = 1.70158
            1 + (c + 1) * (t - 1).pow(3) + c * (t - 1).pow(2)
        }
    }
}

/** The law of a spring (per unit of mass) from rest to 1: [k] how strongly it pulls, [c] how much it is held back. */
private class Spring(k: Double, c: Double) : Curve {
    private val a = c / 2 // How fast it dies out.
    private val w2 = k - a * a // Below 0: too held back to bounce; 0: just enough.
    private val w = Math.sqrt(Math.abs(w2))

    // Its whole length, seconds: until it is still (at most 5 seconds).
    private val seconds: Double = run {
        var s = 0.001
        var still = 0
        while (s < 5.0 && still < 20) {
            still = if (Math.abs(position(s) - 1) < 0.0005 && Math.abs(response(s)) * k < 0.01) still + 1 else 0
            s += 0.001
        }
        s
    }

    override val naturalMillis: Long = Math.round(seconds * 1000).coerceIn(1, 5000)

    // Where it is, [s] seconds in.
    private fun position(s: Double): Double = 1 - Math.exp(-a * s) * when {
        Math.abs(w2) < 1e-9 -> 1 + a * s
        w2 > 0 -> Math.cos(w * s) + a / w * Math.sin(w * s)
        else -> Math.cosh(w * s) + a / w * Math.sinh(w * s)
    }

    // What a speed of 1 at the start adds to where it is, [s] seconds in.
    private fun response(s: Double): Double = Math.exp(-a * s) * when {
        Math.abs(w2) < 1e-9 -> s
        w2 > 0 -> Math.sin(w * s) / w
        else -> Math.sinh(w * s) / w
    }

    override fun at(t: Double): Double = if (t >= 1.0) 1.0 else position(t.coerceAtLeast(0.0) * seconds)

    override fun carried(t: Double): Double = if (t <= 0.0 || t >= 1.0) 0.0 else response(t * seconds) / seconds
}
