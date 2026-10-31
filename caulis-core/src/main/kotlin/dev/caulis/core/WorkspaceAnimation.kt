package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * How a workspace change looks on one monitor: given how far along it is (0 to 1, through
 * the curve), the direction (1: to a higher number, -1: back) and the monitor, where the
 * new and the old workspace are drawn (moved by dx, dy units) and how strongly.
 */
fun interface WorkspaceAnimation {
    fun frame(progress: Double, direction: Int, area: Rect): WorkspaceFrame

    /** How long it takes in milliseconds (null: [Desktop.animationMillis]). */
    val millis: Long? get() = null

    /** Its curve (null: the one of [Animations]). */
    val curve: Curve? get() = null

    fun lasting(millis: Long): WorkspaceAnimation {
        require(millis in 0..10_000) { "an animation takes 0 to 10000 milliseconds, not $millis" }
        return Timed(this, millis, curve)
    }

    fun curved(curve: Curve): WorkspaceAnimation = Timed(this, millis, curve)

    companion object {
        /** At once. */
        @JvmField val NONE: WorkspaceAnimation = Timed(WorkspaceAnimation { _, _, _ -> WorkspaceFrame() }, 0, null)

        /** Side by side: the next workspace comes in from the right, the one before from the left. */
        @JvmField val SLIDE = WorkspaceAnimation { e, direction, area ->
            WorkspaceFrame(incomingDx = direction * (1 - e) * area.width, outgoingDx = -direction * e * area.width)
        }

        /** One above the other: the next comes in from below. */
        @JvmField val SLIDE_VERTICAL = WorkspaceAnimation { e, direction, area ->
            WorkspaceFrame(incomingDy = direction * (1 - e) * area.height, outgoingDy = -direction * e * area.height)
        }

        /** The old workspace fades into the new one. */
        @JvmField val FADE = WorkspaceAnimation { e, _, _ -> WorkspaceFrame(incomingAlpha = e.toFloat().coerceIn(0f, 1f), outgoingAlpha = (1 - e).toFloat().coerceIn(0f, 1f)) }

        /** A look by name, as Hyprland's: "slide", "slidevert" (also "slide-vertical"), "fade", "none"; null for another. */
        @JvmStatic
        fun named(style: String): WorkspaceAnimation? = when (style.trim().lowercase()) {
            "slide" -> SLIDE
            "slidevert", "slide-vertical", "slidevertical" -> SLIDE_VERTICAL
            "fade" -> FADE
            "none" -> NONE
            else -> null
        }
    }

    private class Timed(val inner: WorkspaceAnimation, override val millis: Long?, override val curve: Curve?) : WorkspaceAnimation {
        override fun frame(progress: Double, direction: Int, area: Rect) = inner.frame(progress, direction, area)
    }
}

/** Where the new and the old workspace are drawn during a change (see [WorkspaceAnimation]). */
class WorkspaceFrame @JvmOverloads constructor(
    val incomingDx: Double = 0.0,
    val incomingDy: Double = 0.0,
    val incomingAlpha: Float = 1f,
    val outgoingDx: Double = 0.0,
    val outgoingDy: Double = 0.0,
    val outgoingAlpha: Float = 1f,
)
