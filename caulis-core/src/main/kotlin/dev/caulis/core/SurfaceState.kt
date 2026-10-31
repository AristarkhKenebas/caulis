package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import dev.caulis.protocol.wayland.WlBuffer
import dev.caulis.protocol.wayland.WlCallback
import dev.caulis.protocol.wayland.WlOutput

/**
 * Everything a surface commit carries. The window manager reads it ([Surface.current]); only
 * the client's requests change it.
 */
class SurfaceState internal constructor() {
    /** The client's buffer (its picture), or null for none. */
    var buffer: WlBuffer? = null
        internal set
    internal var newBuffer = false

    /** wl_surface.offset (and attach's x, y): how far the picture moved from where it was. */
    var offsetX = 0
        internal set
    var offsetY = 0
        internal set

    /** wl_surface.set_buffer_scale: buffer pixels per surface unit. */
    var scale = 1
        internal set

    /** wl_surface.set_buffer_transform: how the buffer is turned (WlOutput.Transform). */
    var transform = WlOutput.Transform.NORMAL
        internal set

    /** The buffer's size in pixels (0 without one). */
    var bufferWidth = 0
        internal set
    var bufferHeight = 0
        internal set

    // The damaged parts (buffer pixels); see damage.
    internal val damageList = ArrayList<Rect>()

    // Some damage came in surface units (wl_surface.damage): only the scale turns it into
    // buffer pixels; a viewport or a turned buffer cannot be (see [applyTo]).
    internal var surfaceDamage = false

    /** The parts of the buffer the client redrew (buffer pixels). */
    val damage: List<Rect> get() = damageList
    internal val frameCallbackList = ArrayList<WlCallback>()

    /** Programs waiting for "a good moment to draw" ([Surface.sendFrameDone]). */
    val frameCallbacks: List<WlCallback> get() = frameCallbackList

    /** Where the surface takes pointer and touch input (null: everywhere). */
    var inputRegion: Region.Snapshot? = null
        internal set
    internal var inputRegionSet = false

    /** wp_viewport: the part of the buffer shown (x, y, width, height in buffer pixels / scale), or null for all. */
    var viewportSource: DoubleArray? = null
        internal set

    /** wp_viewport: the size on screen in surface units, or -1 for the buffer's own size. */
    var viewportWidth = -1
        internal set
    var viewportHeight = -1
        internal set
    internal var viewportSet = false

    /** Where the picture has no transparency (the client says so; helps skip hidden windows). */
    var opaqueRegion: Region.Snapshot? = null
        internal set
    internal var opaqueRegionSet = false
    internal val feedbackList = ArrayList<WpPresentationFeedback>()

    /** Programs waiting to hear when this picture reached the screen (presentation-time). */
    val presentationFeedbacks: List<WpPresentationFeedback> get() = feedbackList

    // A client could damage millions of little rectangles before it commits (or a synchronized
    // subsurface whose parent never commits, ever more): past [MAX_DAMAGE] they become the one
    // rectangle around them all, which redraws a little more but costs nothing to keep.
    internal fun addDamage(rect: Rect) {
        damageList += rect
        if (damageList.size > MAX_DAMAGE) collapseDamage()
    }

    private fun collapseDamage() {
        val all = damageList.reduce(Rect::union)
        damageList.clear()
        damageList += all
    }

    /** Moves the pending changes of [other] into this state and resets [other]. */
    internal fun takeFrom(other: SurfaceState) {
        if (other.newBuffer) {
            // The previous picture is replaced before it was shown: it never will be.
            for (feedback in feedbackList) feedback.discard()
            feedbackList.clear()
            buffer = other.buffer
            newBuffer = true
            val picture = other.buffer?.dataAs<ClientBuffer>()
            bufferWidth = picture?.width ?: 0
            bufferHeight = picture?.height ?: 0
        }
        offsetX = other.offsetX
        offsetY = other.offsetY
        scale = other.scale
        transform = other.transform
        damageList += other.damageList
        if (damageList.size > MAX_DAMAGE) collapseDamage()
        // Surface damage with a viewport or a turned buffer: the whole buffer is taken as
        // changed (else only a strip of it was uploaded, the rest stayed old).
        val viewported = (if (other.viewportSet) other.viewportSource != null || other.viewportWidth > 0 else viewportSource != null || viewportWidth > 0)
        if (other.surfaceDamage && (viewported || transform != WlOutput.Transform.NORMAL) && bufferWidth > 0 && bufferHeight > 0) {
            damageList.clear()
            damageList += Rect(0, 0, bufferWidth, bufferHeight)
        }
        other.surfaceDamage = false
        frameCallbackList += other.frameCallbackList
        feedbackList += other.feedbackList
        // "Was set" travels along, so a synchronized subsurface's cached state (applied later)
        // still knows these changed.
        if (other.inputRegionSet) {
            inputRegion = other.inputRegion
            inputRegionSet = true
        }
        if (other.opaqueRegionSet) {
            opaqueRegion = other.opaqueRegion
            opaqueRegionSet = true
        }
        if (other.viewportSet) {
            viewportSource = other.viewportSource
            viewportWidth = other.viewportWidth
            viewportHeight = other.viewportHeight
            viewportSet = true
        }

        other.buffer = null
        other.newBuffer = false
        other.offsetX = 0
        other.offsetY = 0
        other.damageList.clear()
        other.frameCallbackList.clear()
        other.feedbackList.clear()
        other.inputRegionSet = false
        other.opaqueRegionSet = false
        other.viewportSet = false
    }

    private companion object {
        /** More damaged rectangles than this become one. */
        const val MAX_DAMAGE = 64
    }
}
