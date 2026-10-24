package dev.tessera.core

import dev.tessera.protocol.Display
import dev.tessera.protocol.Resource
import dev.tessera.protocol.wayland.WlBuffer
import dev.tessera.protocol.wayland.WlCallback
import dev.tessera.protocol.wayland.WlCompositor
import dev.tessera.protocol.wayland.WlOutput
import dev.tessera.protocol.wayland.WlRegion
import dev.tessera.protocol.wayland.WlSubcompositor
import dev.tessera.protocol.wayland.WlSubsurface
import dev.tessera.protocol.wayland.WlSurface
import dev.tessera.render.Renderer

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
    }

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
    enum class Role { XDG_TOPLEVEL, XDG_POPUP, SUBSURFACE, CURSOR }

    var role: Role? = null
        private set

    /** The object that gives this surface its role (a Window, Popup or Subsurface). */
    var roleObject: Any? = null

    /** Changes the client is still preparing. */
    internal val pending = SurfaceState()

    /** The state of the last commit; this is what gets drawn. */
    val current = SurfaceState()

    /** The picture on the GPU, set when a buffer is committed (null if nothing to show). */
    var texture: dev.tessera.render.GpuTexture? = null
        internal set

    // The texture we copied shared-memory pictures into (owned by this surface).
    internal var uploadedTexture: dev.tessera.render.GpuTexture? = null

    // The GPU buffer shown right now; the client gets it back when another one replaces it.
    internal var shownDmabuf: DmabufBuffer? = null

    /** True if the picture has no transparency (XRGB): faster to draw, no blending. */
    var opaque = false
        internal set

    /** Size in surface coordinates (buffer size divided by the buffer scale). */
    val width: Int get() = current.bufferWidth / current.scale
    val height: Int get() = current.bufferHeight / current.scale

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

            override fun setOpaqueRegion(resource: WlSurface, region: WlRegion?) {}

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
        current.takeFrom(state)
        if (current.newBuffer) {
            compositor.uploadContent(this)
            current.newBuffer = false
        }
        for (child in stack) {
            if (child !== this) child.subsurface?.parentCommitted()
        }
        commitListeners.forEach { it() }
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
        texture = null
        uploadedTexture?.close()
        uploadedTexture = null
        shownDmabuf?.stopShowing()
        shownDmabuf = null
        subsurface?.detach()
        for (child in stack.toList()) if (child !== this) child.subsurface?.detach()
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

    /** Moves the pending changes of [other] into this state and resets [other]. */
    internal fun takeFrom(other: SurfaceState) {
        if (other.newBuffer) {
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
        if (other.inputRegionSet) inputRegion = other.inputRegion

        other.buffer = null
        other.newBuffer = false
        other.offsetX = 0
        other.offsetY = 0
        other.damage.clear()
        other.frameCallbacks.clear()
        other.inputRegionSet = false
    }
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
