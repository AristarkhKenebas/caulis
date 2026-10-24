package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.Display
import dev.caulis.protocol.Resource
import dev.caulis.protocol.presentationtime.WpPresentationFeedback
import dev.caulis.protocol.wayland.WlBuffer
import dev.caulis.protocol.wayland.WlCallback
import dev.caulis.protocol.wayland.WlCompositor
import dev.caulis.protocol.wayland.WlOutput
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

    /** All surfaces of all clients, in creation order. */
    val surfaces = LinkedHashSet<Surface>()

    private val uploader = renderer?.let { SurfaceUploader(it) }
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
                    id.sendPreferredScale(fractionalScale())
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

    /** The scale as wp_fractional_scale wants it: in 120ths. */
    internal fun fractionalScale(): Int = Math.round(outputScale * 120).toInt()

    /** The whole-number scale for programs without fractional scaling (wl_output, wl_surface). */
    val integerScale: Int get() = Math.ceil(outputScale - 1e-9).toInt().coerceAtLeast(1)

    private val compositorRequests = object : WlCompositor.Requests {
        override fun createSurface(resource: WlCompositor, id: WlSurface) {
            val surface = Surface(id, this@Compositor)
            surfaces += surface
            id.onDestroy {
                surfaces -= surface
                surface.destroyed()
            }
            newSurfaceListeners.forEach { it(surface) }
        }

        override fun createRegion(resource: WlCompositor, id: WlRegion) {
            Region(id)
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

/**
 * A client's drawing area. Changes (a new buffer, damage, frame callbacks) are collected
 * as "pending" and only become "current" on commit, so the compositor never shows a
 * half-updated surface.
 */
class Surface internal constructor(val resource: WlSurface, private val compositor: Compositor) {

    /** What the surface is used as. A surface gets a role once and keeps it. */
    enum class Role { XDG_TOPLEVEL, XDG_POPUP, LAYER, LOCK, SUBSURFACE, CURSOR }

    var role: Role? = null
        private set

    /** The object that gives this surface its role (a Window, Popup or Subsurface). */
    var roleObject: Any? = null

    /** Changes the client is still preparing. */
    internal val pending = SurfaceState()

    /** The state of the last commit; this is what gets drawn. */
    val current = SurfaceState()

    /** The picture on the GPU, set when a buffer is committed (null if nothing to show). */
    var texture: dev.caulis.render.GpuTexture? = null
        internal set

    // The texture we copied shared-memory pictures into (owned by this surface).
    internal var uploadedTexture: dev.caulis.render.GpuTexture? = null

    // The GPU buffer shown right now; the client gets it back when another one replaces it.
    internal var shownDmabuf: DmabufBuffer? = null

    /** True if the picture has no transparency (XRGB): faster to draw, no blending. */
    var opaque = false
        internal set

    /**
     * Size in surface coordinates: the viewport's destination if the client set one (that is
     * how programs draw at fractional scales), else the buffer size divided by its scale.
     */
    val width: Int
        get() = when {
            current.bufferWidth == 0 -> 0
            current.viewportWidth > 0 -> current.viewportWidth
            current.viewportSource != null -> current.viewportSource!![2].toInt()
            else -> current.bufferWidth / current.scale
        }
    val height: Int
        get() = when {
            current.bufferHeight == 0 -> 0
            current.viewportHeight > 0 -> current.viewportHeight
            current.viewportSource != null -> current.viewportSource!![3].toInt()
            else -> current.bufferHeight / current.scale
        }

    /**
     * The part of the picture to show, as fractions of the texture (u, v, width, height), or
     * null for all of it (wp_viewport's source rectangle).
     */
    val textureSource: FloatArray?
        get() {
            val source = current.viewportSource ?: return null
            val w = current.bufferWidth.toDouble() / current.scale
            val h = current.bufferHeight.toDouble() / current.scale
            if (w <= 0 || h <= 0) return null
            return floatArrayOf((source[0] / w).toFloat(), (source[1] / h).toFloat(), (source[2] / w).toFloat(), (source[3] / h).toFloat())
        }

    // --- scale and outputs ----------------------------------------------------------------

    internal var viewport: WpViewport? = null
    internal var fractionalScale: WpFractionalScaleV1? = null
    private val enteredOutputs = HashSet<WlOutput>()
    private var toldBufferScale = 0

    internal fun attachViewport(resource: WpViewport) {
        viewport = resource
        resource.requests = object : WpViewport.Requests {
            override fun setSource(resource: WpViewport, x: Double, y: Double, width: Double, height: Double) {
                if (x == -1.0 && y == -1.0 && width == -1.0 && height == -1.0) {
                    pending.viewportSource = null
                } else if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                    resource.postError(WpViewport.Error.BAD_VALUE, "invalid source $x,$y ${width}x$height")
                    return
                } else {
                    pending.viewportSource = doubleArrayOf(x, y, width, height)
                }
                pending.viewportSet = true
            }

            override fun setDestination(resource: WpViewport, width: Int, height: Int) {
                if (width == -1 && height == -1) {
                    pending.viewportWidth = -1
                    pending.viewportHeight = -1
                } else if (width <= 0 || height <= 0) {
                    resource.postError(WpViewport.Error.BAD_VALUE, "invalid destination ${width}x$height")
                    return
                } else {
                    pending.viewportWidth = width
                    pending.viewportHeight = height
                }
                pending.viewportSet = true
            }

            override fun destroy(resource: WpViewport) {
                // Back to the buffer's own size from the next commit on.
                pending.viewportSource = null
                pending.viewportWidth = -1
                pending.viewportHeight = -1
                pending.viewportSet = true
            }
        }
    }

    // Tells the program which monitors it is on and their scale (once per new monitor object).
    internal fun tellScale() {
        if (!resource.isAlive) return
        for (output in Output.of(compositor.display).flatMap { it.resourcesOf(resource.client) }) {
            if (enteredOutputs.add(output)) resource.sendEnter(output)
        }
        if (resource.version >= 6 && toldBufferScale != compositor.integerScale) {
            toldBufferScale = compositor.integerScale
            resource.sendPreferredBufferScale(toldBufferScale)
        }
        fractionalScale?.takeIf { it.isAlive }?.sendPreferredScale(compositor.fractionalScale())
    }

    /**
     * Parts of the picture (surface coordinates) that are certainly not transparent: all
     * of it for pictures without an alpha channel, otherwise what the client declared.
     */
    fun opaqueRects(): List<Rect> {
        if (!hasContent) return emptyList()
        val whole = Rect(0, 0, width, height)
        if (opaque) return listOf(whole)
        return current.opaqueRegion?.solidRects()?.mapNotNull { it.intersect(whole) } ?: emptyList()
    }

    /**
     * True if the client committed a picture (and has not removed it with a null buffer).
     * Depends only on what the client did, not on drawing details, so it works the same
     * with and without a GPU.
     */
    val hasContent: Boolean get() = width > 0 && height > 0

    /**
     * This surface and its subsurfaces, bottom to top. The surface itself is one entry,
     * so subsurfaces can be below or above it.
     */
    val stack = ArrayList<Surface>().also { it += this }

    /** Set if this surface is a subsurface of another one. */
    var subsurface: Subsurface? = null
        internal set

    private val commitListeners = ArrayList<() -> Unit>()

    // Parts of the picture that changed and nobody has redrawn yet (surface coordinates).
    private val changes = ArrayList<Rect>()

    /**
     * Takes the parts of this surface (in surface coordinates) that changed since the last
     * call. The window manager redraws those, and only those, on screen.
     */
    fun takeChanges(): List<Rect> {
        if (changes.isEmpty()) return emptyList()
        val taken = changes.toList()
        changes.clear()
        return taken
    }

    // Remembers what a commit changed: the damaged parts, or everything if the size changed.
    private fun recordChanges(oldWidth: Int, oldHeight: Int) {
        if (width != oldWidth || height != oldHeight) {
            changes.clear()
            changes += Rect(0, 0, maxOf(width, oldWidth), maxOf(height, oldHeight))
            return
        }
        if (current.viewportSource != null || current.viewportWidth > 0) {
            // Buffer pixels and surface units differ by an arbitrary factor: redraw it all.
            if (current.damage.isNotEmpty()) changes += Rect(0, 0, width, height)
            return
        }
        val scale = current.scale
        for (damage in current.damage) {
            // Buffer pixels -> surface coordinates, rounded outwards so nothing is missed.
            val left = Math.floorDiv(damage.x, scale)
            val top = Math.floorDiv(damage.y, scale)
            val right = -Math.floorDiv(-(damage.x + damage.width), scale)
            val bottom = -Math.floorDiv(-(damage.y + damage.height), scale)
            Rect(left, top, right - left, bottom - top).intersect(Rect(0, 0, width, height))?.let { changes += it }
        }
        // Many small pieces cost more to track than to redraw: merge them into one.
        if (changes.size > MAX_CHANGE_RECTS) {
            val all = changes.reduce(Rect::union)
            changes.clear()
            changes += all
        }
    }

    /** Runs [listener] after every commit that made new state current. */
    fun onCommit(listener: () -> Unit) {
        commitListeners += listener
    }

    init {
        resource.data = this
        resource.requests = object : WlSurface.Requests {
            override fun destroy(resource: WlSurface) {}

            override fun attach(resource: WlSurface, buffer: WlBuffer?, x: Int, y: Int) {
                if (resource.version >= 5 && (x != 0 || y != 0)) {
                    resource.postError(WlSurface.Error.INVALID_OFFSET, "use wl_surface.offset for offsets")
                    return
                }
                pending.buffer = buffer
                pending.newBuffer = true
                pending.offsetX += x
                pending.offsetY += y
            }

            override fun damage(resource: WlSurface, x: Int, y: Int, width: Int, height: Int) {
                // Damage in surface coordinates; treated like buffer damage (good enough
                // without scaling; the renderer re-uploads the touched rows).
                if (width > 0 && height > 0) pending.damage += Rect(x * pending.scale, y * pending.scale, width * pending.scale, height * pending.scale)
            }

            override fun frame(resource: WlSurface, callback: WlCallback) {
                pending.frameCallbacks += callback
            }

            override fun setOpaqueRegion(resource: WlSurface, region: WlRegion?) {
                pending.opaqueRegion = region?.dataAs<Region>()?.snapshot()
                pending.opaqueRegionSet = true
            }

            override fun setInputRegion(resource: WlSurface, region: WlRegion?) {
                pending.inputRegion = region?.dataAs<Region>()?.snapshot()
                pending.inputRegionSet = true
            }

            override fun commit(resource: WlSurface) = this@Surface.commit()

            override fun setBufferTransform(resource: WlSurface, transform: Int) {
                if (transform !in WlOutput.Transform.NORMAL..WlOutput.Transform.FLIPPED_270) {
                    resource.postError(WlSurface.Error.INVALID_TRANSFORM, "transform $transform does not exist")
                    return
                }
                pending.transform = transform
            }

            override fun setBufferScale(resource: WlSurface, scale: Int) {
                if (scale < 1) {
                    resource.postError(WlSurface.Error.INVALID_SCALE, "scale must be at least 1, got $scale")
                    return
                }
                pending.scale = scale
            }

            override fun damageBuffer(resource: WlSurface, x: Int, y: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) pending.damage += Rect(x, y, width, height)
            }

            override fun offset(resource: WlSurface, x: Int, y: Int) {
                pending.offsetX += x
                pending.offsetY += y
            }

            override fun getRelease(resource: WlSurface, callback: WlCallback) {
                // Only exists in wl_surface version 7; we offer version 6, so clients cannot call it.
                callback.destroy()
            }
        }
    }

    /** Gives the surface a role; posts [errorCode] on [errorTarget] if it already has another one. */
    fun assignRole(newRole: Role, errorTarget: Resource, errorCode: Int): Boolean {
        val existing = role
        if (existing != null && existing != newRole) {
            errorTarget.postError(errorCode, "surface already has the role $existing")
            return false
        }
        role = newRole
        return true
    }

    internal fun isDescendantOf(other: Surface): Boolean {
        var parent = subsurface?.parent
        while (parent != null) {
            if (parent === other) return true
            parent = parent.subsurface?.parent
        }
        return false
    }

    private fun commit() {
        val sub = subsurface
        if (sub != null && sub.isSynchronized) {
            // Synchronized subsurfaces wait for their parent's commit (so a window and
            // its decorations always change in the same frame).
            sub.cache(pending)
            return
        }
        apply(pending)
    }

    /** Makes [state] current. Also applies cached state of synchronized subsurfaces. */
    internal fun apply(state: SurfaceState) {
        val oldWidth = width
        val oldHeight = height
        current.takeFrom(state)
        if (!checkViewport()) return
        if (enteredOutputs.isEmpty() || toldBufferScale == 0) tellScale()
        if (current.newBuffer) {
            recordChanges(oldWidth, oldHeight)
            compositor.uploadContent(this)
            current.newBuffer = false
        }
        current.damage.clear()
        for (child in stack) {
            if (child !== this) child.subsurface?.parentCommitted()
        }
        commitListeners.forEach { it() }
    }

    // The viewport must fit the buffer, and without a destination its source must be whole units.
    private fun checkViewport(): Boolean {
        val source = current.viewportSource ?: return true
        val port = viewport ?: return true
        if (current.bufferWidth == 0) return true
        val w = current.bufferWidth.toDouble() / current.scale
        val h = current.bufferHeight.toDouble() / current.scale
        if (source[0] + source[2] > w + 1e-6 || source[1] + source[3] > h + 1e-6) {
            port.postError(WpViewport.Error.OUT_OF_BUFFER, "the source rectangle is outside the ${w}x$h buffer")
            return false
        }
        if (current.viewportWidth <= 0 && (source[2] % 1.0 != 0.0 || source[3] % 1.0 != 0.0)) {
            port.postError(WpViewport.Error.BAD_SIZE, "without a destination the source size must be whole units")
            return false
        }
        return true
    }

    /** Tells the client it is a good moment to draw its next frame. [timeMillis] is a timestamp. */
    fun sendFrameDone(timeMillis: Int) {
        for (callback in current.frameCallbacks) {
            callback.sendDone(timeMillis)
            callback.destroy()
        }
        current.frameCallbacks.clear()
    }

    /** True if the point (in surface coordinates) is inside the surface's input area. */
    fun acceptsInput(x: Double, y: Double): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        val region = current.inputRegion ?: return true
        return region.contains(x.toInt(), y.toInt())
    }

    internal fun destroyed() {
        for (feedback in current.presentationFeedbacks) feedback.discard()
        current.presentationFeedbacks.clear()
        texture = null
        uploadedTexture?.close()
        uploadedTexture = null
        shownDmabuf?.stopShowing()
        shownDmabuf = null
        subsurface?.detach()
        for (child in stack.toList()) if (child !== this) child.subsurface?.detach()
    }

    private companion object {
        /** More changed rectangles than this are merged into one. */
        const val MAX_CHANGE_RECTS = 16
    }
}

/** What every kind of client buffer (shared memory or GPU) tells about its size. */
interface ClientBuffer {
    val width: Int
    val height: Int
}

/** Everything a surface commit carries. */
class SurfaceState {
    var buffer: WlBuffer? = null
    internal var newBuffer = false
    var offsetX = 0
    var offsetY = 0
    var scale = 1
    var transform = WlOutput.Transform.NORMAL
    var bufferWidth = 0
    var bufferHeight = 0
    val damage = ArrayList<Rect>()
    val frameCallbacks = ArrayList<WlCallback>()
    var inputRegion: Region.Snapshot? = null
    internal var inputRegionSet = false

    /** wp_viewport: the part of the buffer shown (x, y, width, height in buffer pixels / scale), or null for all. */
    var viewportSource: DoubleArray? = null
    /** wp_viewport: the size on screen in surface units, or -1 for the buffer's own size. */
    var viewportWidth = -1
    var viewportHeight = -1
    internal var viewportSet = false

    /** Where the picture has no transparency (the client says so; helps skip hidden windows). */
    var opaqueRegion: Region.Snapshot? = null
    internal var opaqueRegionSet = false

    /** Programs waiting to hear when this picture reached the screen (presentation-time). */
    val presentationFeedbacks = ArrayList<WpPresentationFeedback>()

    /** Moves the pending changes of [other] into this state and resets [other]. */
    internal fun takeFrom(other: SurfaceState) {
        if (other.newBuffer) {
            // The previous picture is replaced before it was shown: it never will be.
            for (feedback in presentationFeedbacks) feedback.discard()
            presentationFeedbacks.clear()
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
        damage += other.damage
        frameCallbacks += other.frameCallbacks
        presentationFeedbacks += other.presentationFeedbacks
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
        other.damage.clear()
        other.frameCallbacks.clear()
        other.presentationFeedbacks.clear()
        other.inputRegionSet = false
        other.opaqueRegionSet = false
        other.viewportSet = false
    }
}

/** Tells the program its picture was never shown, and frees the object. */
internal fun WpPresentationFeedback.discard() {
    if (!isAlive) return
    sendDiscarded()
    destroy()
}

/**
 * A surface attached to a parent surface (e.g. a terminal's title bar drawn as its own
 * surface). Its position is relative to the parent and changes with the parent's commit.
 */
class Subsurface internal constructor(resource: WlSubsurface, val surface: Surface, parent: Surface) {

    var parent: Surface? = parent
        private set

    /** Position relative to the parent's top left corner. */
    var x = 0
        private set
    var y = 0
        private set

    private var pendingX = 0
    private var pendingY = 0
    private var synchronized = true
    private var cached: SurfaceState? = null

    /** True if commits wait for the parent (it or one of its ancestors is synchronized). */
    val isSynchronized: Boolean
        get() = synchronized || (parent?.subsurface?.isSynchronized ?: false)

    init {
        surface.subsurface = this
        surface.roleObject = this
        parent.stack += surface // new subsurfaces start on top of their parent

        resource.requests = object : WlSubsurface.Requests {
            override fun destroy(resource: WlSubsurface) = detach()

            override fun setPosition(resource: WlSubsurface, x: Int, y: Int) {
                pendingX = x
                pendingY = y
            }

            override fun placeAbove(resource: WlSubsurface, sibling: WlSurface) = place(resource, sibling, above = true)

            override fun placeBelow(resource: WlSubsurface, sibling: WlSurface) = place(resource, sibling, above = false)

            override fun setSync(resource: WlSubsurface) {
                synchronized = true
            }

            override fun setDesync(resource: WlSubsurface) {
                synchronized = false
                if (!isSynchronized) cached?.let { state ->
                    cached = null
                    surface.apply(state)
                }
            }
        }
        resource.onDestroy { detach() }
    }

    private fun place(resource: WlSubsurface, sibling: WlSurface, above: Boolean) {
        val stack = parent?.stack ?: return
        val target = sibling.dataAs<Surface>()
        if (target == null || target === surface || target !in stack) {
            resource.postError(WlSubsurface.Error.BAD_SURFACE, "the sibling is not a sibling of this subsurface")
            return
        }
        stack.remove(surface)
        val index = stack.indexOf(target)
        stack.add(if (above) index + 1 else index, surface)
    }

    // Keeps a commit until the parent commits.
    internal fun cache(state: SurfaceState) {
        val target = cached ?: SurfaceState().also { cached = it }
        target.takeFrom(state)
    }

    // The parent committed: the new position and any cached state take effect now.
    internal fun parentCommitted() {
        x = pendingX
        y = pendingY
        cached?.let { state ->
            cached = null
            surface.apply(state)
        }
    }

    internal fun detach() {
        parent?.stack?.remove(surface)
        parent = null
        surface.subsurface = null
    }
}

/** A set of rectangles a client describes (e.g. which part of a surface accepts input). */
class Region internal constructor(resource: WlRegion) {

    private val parts = ArrayList<Pair<Rect, Boolean>>() // rectangle, added (true) or subtracted

    init {
        resource.data = this
        resource.requests = object : WlRegion.Requests {
            override fun destroy(resource: WlRegion) {}

            override fun add(resource: WlRegion, x: Int, y: Int, width: Int, height: Int) {
                parts += Rect(x, y, width, height) to true
            }

            override fun subtract(resource: WlRegion, x: Int, y: Int, width: Int, height: Int) {
                parts += Rect(x, y, width, height) to false
            }
        }
    }

    /** A copy that stays the same when the client changes the region later. */
    fun snapshot() = Snapshot(parts.toList())

    class Snapshot(private val parts: List<Pair<Rect, Boolean>>) {
        /**
         * Rectangles that are certainly inside the region: added ones that no later
         * subtraction touches. (Careful rather than exact: it may leave parts out.)
         */
        fun solidRects(): List<Rect> = parts.withIndex().filter { (index, part) ->
            part.second && parts.subList(index + 1, parts.size).none { !it.second && it.first.intersect(part.first) != null }
        }.map { it.value.first }

        /** The last rectangle covering the point decides (later operations win). */
        fun contains(x: Int, y: Int): Boolean {
            var inside = false
            for ((rect, added) in parts) {
                if (x >= rect.x && y >= rect.y && x < rect.x + rect.width && y < rect.y + rect.height) inside = added
            }
            return inside
        }
    }
}
