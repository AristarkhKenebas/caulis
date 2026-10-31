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
         */
        @JvmStatic
        @JvmOverloads
        fun spring(stiffness: Double = 200.0, damping: Double = 20.0, mass: Double = 1.0): Curve {
            require(stiffness > 0 && damping > 0 && mass > 0) { "a spring's stiffness, damping and mass are more than 0" }
            // Moved step by step (half a millisecond) until it rests; at most 5 seconds.
            val step = 0.0005
            val samples = ArrayList<Double>()
            var x = 0.0
            var v = 0.0
            var still = 0
            while (samples.size < 10_000) {
                samples += x
                val a = (-stiffness * (x - 1) - damping * v) / mass
                v += a * step
                x += v * step
                still = if (Math.abs(x - 1) < 0.001 && Math.abs(v) < 0.01) still + 1 else 0
                if (still > 20) break
            }
            samples += 1.0
            val millis = Math.round(samples.size * step * 1000).coerceIn(1, 5000)
            return object : Curve {
                override val naturalMillis: Long = millis
                override fun at(t: Double): Double {
                    if (t >= 1.0) return 1.0
                    val at = t.coerceAtLeast(0.0) * (samples.size - 1)
                    val i = at.toInt()
                    return samples[i] + (samples[i + 1] - samples[i]) * (at - i)
                }
            }
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
         * "smooth", "overshoot"; null for another name.
         */
        @JvmStatic
        fun named(name: String): Curve? = when (name.trim().lowercase()) {
            "linear" -> LINEAR
            "ease-out", "easeout", "default" -> EASE_OUT
            "ease-in-out", "easeinout" -> EASE_IN_OUT
            "smooth" -> SMOOTH
            "overshoot" -> OVERSHOOT
            else -> null
        }

        /** Goes a little past the end and comes back: lively. */
        @JvmField val OVERSHOOT = Curve { t ->
            val c = 1.70158
            1 + (c + 1) * (t - 1).pow(3) + c * (t - 1).pow(2)
        }
    }
}
