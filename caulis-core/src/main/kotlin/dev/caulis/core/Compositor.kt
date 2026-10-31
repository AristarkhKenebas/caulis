package dev.caulis.core

import dev.caulis.protocol.Display
import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import dev.caulis.protocol.wayland.WlCompositor
import dev.caulis.protocol.wayland.WlRegion
import dev.caulis.protocol.wayland.WlSubcompositor
import dev.caulis.protocol.wayland.WlSubsurface
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.fractionalscalev1.WpFractionalScaleManagerV1
import dev.caulis.protocol.fractionalscalev1.WpFractionalScaleV1
import dev.caulis.protocol.viewporter.WpViewport
import dev.caulis.protocol.viewporter.WpViewporter
import dev.caulis.render.Renderer

/**
 * wl_compositor and wl_subcompositor: clients create surfaces (the things that become
 * windows, menus, cursors) and attach surfaces to each other (subsurfaces).
 *
 * With a [renderer], every committed picture is copied to the GPU at once, so the client
 * gets its buffer back immediately. Without one (in tests) pictures are ignored.
 */
class Compositor(val display: Display, private val renderer: Renderer?) {

    // All surfaces of all clients, in creation order.
    private val surfaceSet = LinkedHashSet<Surface>()

    /** All surfaces of all clients, in creation order (a live, read-only view). */
    val surfaces: Set<Surface> = java.util.Collections.unmodifiableSet(surfaceSet)

    private val uploader = renderer?.let { SurfaceUploader(it) }

    // What a commit's picture has to wait for (see ClientFences), null: nothing is waited for.
    internal var clientFences: ((SurfaceState) -> List<dev.caulis.ffi.Fd>)? = null

    // Bytes of programs' pictures copied so far (for the render log).
    internal val bytesCopied: Long get() = uploader?.bytesCopied ?: 0L
    private val newSurfaceListeners = ArrayList<(Surface) -> Unit>()

    /** Runs [listener] for every surface a client creates. */
    fun onNewSurface(listener: (Surface) -> Unit) {
        newSurfaceListeners += listener
    }

    init {
        display.addGlobal(WlCompositor, VERSION) { compositor -> compositor.requests = compositorRequests }
        display.addGlobal(WlSubcompositor, 1) { subcompositor -> subcompositor.requests = subcompositorRequests }
        display.addGlobal(WpViewporter, 1) { viewporter ->
            viewporter.requests = object : WpViewporter.Requests {
                override fun getViewport(resource: WpViewporter, id: WpViewport, surface: WlSurface) {
                    val target = surface.dataAs<Surface>() ?: return
                    if (target.viewport?.isAlive == true) {
                        resource.postError(WpViewporter.Error.VIEWPORT_EXISTS, "the surface already has a viewport")
                        return
                    }
                    target.attachViewport(id)
                }

                override fun destroy(resource: WpViewporter) {}
            }
        }
        display.addGlobal(WpFractionalScaleManagerV1, 1) { manager ->
            manager.requests = object : WpFractionalScaleManagerV1.Requests {
                override fun getFractionalScale(resource: WpFractionalScaleManagerV1, id: WpFractionalScaleV1, surface: WlSurface) {
                    val target = surface.dataAs<Surface>() ?: return
                    if (target.fractionalScale?.isAlive == true) {
                        resource.postError(WpFractionalScaleManagerV1.Error.FRACTIONAL_SCALE_EXISTS, "the surface already has a fractional scale")
                        return
                    }
                    id.requests = object : WpFractionalScaleV1.Requests {
                        override fun destroy(resource: WpFractionalScaleV1) {}
                    }
                    target.fractionalScale = id
                    id.sendPreferredScale(target.preferredFraction())
                }

                override fun destroy(resource: WpFractionalScaleManagerV1) {}
            }
        }
    }

    /**
     * How many screen pixels one surface unit is (1.0, 1.25, 1.5, 2, ...), as programs are
     * told: those that know fractional scales draw at exactly this size, the others at the
     * next whole number (and are scaled down a little).
     */
    var outputScale: Double = 1.0
        set(value) {
            require(value in 0.5..4.0) { "a scale must be between 0.5 and 4, not $value" }
            if (value == field) return
            field = value
            for (surface in surfaces) surface.tellScale()
        }

    /**
     * The most rectangles a program's region (opaque, input, pointer confinement) is kept
     * exactly with (256 by default); past that it counts roughly (all that was added, as one
     * rectangle, and nothing of it as opaque). Each costs work every frame.
     */
    var maxRegionParts: Int = Region.MAX_PARTS
        set(value) {
            require(value in 1..100_000) { "maxRegionParts must be 1..100000, not $value" }
            field = value
        }

    /** The scale as wp_fractional_scale wants it: in 120ths. */
    internal fun fractionalScale(): Int = Math.round(outputScale * 120).toInt()

    /**
     * The monitors a surface not shown yet is on (the one where new windows open); the
     * window manager places shown ones itself (see Surface.placeOn).
     */
    var defaultOutputs: () -> List<Output> = { Output.of(display) }

    /**
     * Called just before a surface's picture goes (a commit without a buffer), while it can
     * still be drawn: the window manager keeps a last look for an animation.
     */
    var pictureGoing: (Surface) -> Unit = {}

    // A surface left the screen at once, without a commit of its parent (a subsurface
    // destroyed or taken off): the window manager draws anew (else its last picture stayed).
    internal var surfaceGone: (Surface) -> Unit = {}

    /** The whole-number scale for programs without fractional scaling (wl_output, wl_surface). */
    val integerScale: Int get() = Math.ceil(outputScale - 1e-9).toInt().coerceAtLeast(1)

    private val compositorRequests = object : WlCompositor.Requests {
        override fun createSurface(resource: WlCompositor, id: WlSurface) {
            val surface = Surface(id, this@Compositor)
            surfaceSet += surface
            id.onDestroy {
                surfaceSet -= surface
                surface.destroyed()
            }
            newSurfaceListeners.forEach { it(surface) }
        }

        override fun createRegion(resource: WlCompositor, id: WlRegion) {
            Region(id, maxRegionParts)
        }

        // Only exists in version 7 (destroys the client's handle); nothing to clean up.
        override fun release(resource: WlCompositor) {}
    }

    private val subcompositorRequests = object : WlSubcompositor.Requests {
        override fun destroy(resource: WlSubcompositor) {}

        override fun getSubsurface(resource: WlSubcompositor, id: WlSubsurface, surface: WlSurface, parent: WlSurface) {
            val child = surface.dataAs<Surface>()!!
            val parentSurface = parent.dataAs<Surface>()!!
            if (child === parentSurface || parentSurface.isDescendantOf(child)) {
                resource.postError(WlSubcompositor.Error.BAD_PARENT, "a surface cannot be its own parent")
                return
            }
            // One sub-surface at a time (a second one would leave the first's place behind).
            if (child.subsurface != null) {
                resource.postError(WlSubcompositor.Error.BAD_SURFACE, "the surface already is a sub-surface")
                return
            }
            if (!child.assignRole(Surface.Role.SUBSURFACE, resource, WlSubcompositor.Error.BAD_SURFACE)) return
            Subsurface(id, child, parentSurface)
        }
    }

    // Called by Surface when a new picture becomes current.
    internal fun uploadContent(surface: Surface) {
        uploader?.upload(surface)
    }

    companion object {
        const val VERSION = 6
    }
}

/** What every kind of client buffer (shared memory or GPU) tells about its size. */
interface ClientBuffer {
    val width: Int
    val height: Int
}

/** Tells the program its picture was never shown, and frees the object. */
internal fun WpPresentationFeedback.discard() {
    if (!isAlive) return
    sendDiscarded()
    destroy()
}
