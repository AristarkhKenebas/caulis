package dev.caulis.compose

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import dev.caulis.core.Curve
import dev.caulis.core.ResolvedAnimation

/**
 * A [Curve] as Compose's [Easing], so a widget moves along the same curves as the compositor
 * (a curve of your own that fails is a straight line).
 */
fun Curve.easing(): Easing {
    val curve = this
    return Easing { t ->
        try {
            curve.at(t.toDouble()).toFloat()
        } catch (e: Exception) {
            t
        }
    }
}

/**
 * A named animation (see [dev.caulis.core.AnimationSettings]) for Compose: a tween of its
 * time along its curve — or, when it is off, [snap]: the value is there at once, no frame
 * in between.
 *
 * ```
 * val shown by animateFloatAsState(if (open) 1f else 0f, settings.resolve("popups").spec())
 * ```
 */
fun <T> ResolvedAnimation.spec(): FiniteAnimationSpec<T> =
    if (on) tween(millis.toInt(), easing = curve.easing()) else snap()
