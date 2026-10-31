package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.protocol.Resource
import dev.caulis.protocol.wayland.WlBuffer
import dev.caulis.protocol.wayland.WlCallback
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlRegion
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.fractionalscalev1.WpFractionalScaleV1
import dev.caulis.protocol.viewporter.WpViewport

/**
 * A client's drawing area. Changes (a new buffer, damage, frame callbacks) are collected
 * as "pending" and only become "current" on commit, so the compositor never shows a
 * half-updated surface.
 */
class Surface internal constructor(val resource: WlSurface, private val compositor: Compositor) {

    /** What the surface is used as. A surface gets a role once and keeps it. */
    enum class Role { XDG_TOPLEVEL, XDG_POPUP, LAYER, LOCK, SUBSURFACE, CURSOR, XWAYLAND, DND_ICON }

    var role: Role? = null
        private set

    /** The object that gives this surface its role (a Window, Popup or Subsurface). */
    var roleObject: Any? = null
        internal set

    /**
     * Xwayland's window [owner] (outside caulis-core) gives this surface its role: it is the
     * [roleObject] from now on. Only for surfaces with the role [Role.XWAYLAND].
     */
    fun claimXwaylandRole(owner: Any) {
        check(role == Role.XWAYLAND) { "only an Xwayland surface can be claimed, not one with the role $role" }
        roleObject = owner
    }

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
            nativeScale != null -> Math.round(current.bufferWidth / nativeScale!!).toInt()
            current.viewportWidth > 0 -> current.viewportWidth
            current.viewportSource != null -> current.viewportSource!![2].toInt()
            else -> turnedWidth / current.scale
        }
    val height: Int
        get() = when {
            current.bufferHeight == 0 -> 0
            nativeScale != null -> Math.round(current.bufferHeight / nativeScale!!).toInt()
            current.viewportHeight > 0 -> current.viewportHeight
            current.viewportSource != null -> current.viewportSource!![3].toInt()
            else -> turnedHeight / current.scale
        }

    // The buffer's size the way it is shown (a buffer turned sideways trades width and height).
    private val sideways: Boolean get() = current.transform % 2 == 1
    private val turnedWidth: Int get() = if (sideways) current.bufferHeight else current.bufferWidth
    private val turnedHeight: Int get() = if (sideways) current.bufferWidth else current.bufferHeight

    /**
     * The part of the picture to show, as fractions of the texture (u, v, width, height), or
     * null for all of it (wp_viewport's source rectangle).
     */
    val textureSource: FloatArray?
        get() {
            val source = current.viewportSource ?: return null
            val w = turnedWidth.toDouble() / current.scale
            val h = turnedHeight.toDouble() / current.scale
            if (w <= 0 || h <= 0) return null
            return floatArrayOf((source[0] / w).toFloat(), (source[1] / h).toFloat(), (source[2] / w).toFloat(), (source[3] / h).toFloat())
        }

    // --- scale and outputs ----------------------------------------------------------------

    /**
     * For surfaces whose pictures are always in screen pixels, whatever they say (Xwayland's:
     * X11 programs draw at the screen's real resolution): how many pixels one surface unit
     * is. Their size is the picture's divided by this, and pointer positions are sent to the
     * program in its pixels. Null for ordinary surfaces.
     */
    var nativeScale: Double? = null

    internal var viewport: WpViewport? = null
    internal var fractionalScale: WpFractionalScaleV1? = null
    private val enteredOutputs = HashSet<WlOutput>()
    private var toldBufferScale = 0
    private var toldFraction = 0

    // Where the window manager shows it: on which monitors, and the scale to draw at there
    // (the largest of theirs). Null: not shown yet (the compositor's default).
    private var placedOutputs: List<Output>? = null
    private var placedScale: Double? = null
    private var toldTurns: List<Int> = emptyList()

    /** The compositor shows this surface on [outputs], where its program should draw at [scale]. */
    internal fun placeOn(outputs: List<Output>, scale: Double) {
        // (A monitor turned meanwhile: the program hears the new preferred turn.)
        if (outputs == placedOutputs && scale == placedScale && outputs.map { it.transform.ordinal } == toldTurns) return
        toldTurns = outputs.map { it.transform.ordinal }
        placedOutputs = outputs
        placedScale = scale
        tellScale()
    }

    // The scale this surface should be drawn at (in 120ths for wp_fractional_scale).
    internal fun preferredFraction(): Int = Math.round((placedScale ?: compositor.outputScale) * 120).toInt()

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

    // Tells the program which monitors it is on (enter, leave) and the scale to draw at —
    // only what changed.
    // The buffer transform the program was told to prefer last (-1: none yet).
    private var toldBufferTransform = -1

    internal fun tellScale() {
        if (!resource.isAlive) return
        val wanted = (placedOutputs ?: compositor.defaultOutputs()).flatMap { it.resourcesOf(resource.client) }.toSet()
        for (output in enteredOutputs.toList()) {
            if (output in wanted && output.isAlive) continue
            enteredOutputs -= output
            if (output.isAlive) resource.sendLeave(output)
        }
        for (output in wanted) if (enteredOutputs.add(output)) resource.sendEnter(output)
        val scale = placedScale ?: compositor.outputScale
        val whole = Math.ceil(scale - 1e-9).toInt().coerceAtLeast(1)
        if (resource.version >= 6 && toldBufferScale != whole) {
            toldBufferScale = whole
            resource.sendPreferredBufferScale(whole)
        }
        // The monitors' turn (one that all of them share): a program drawing its buffer turned
        // so can be shown directly on a monitor standing on its side.
        val turn = (placedOutputs ?: compositor.defaultOutputs()).map { it.transform }.distinct().singleOrNull() ?: Transform.NORMAL
        if (resource.version >= 6 && toldBufferTransform != turn.ordinal) {
            toldBufferTransform = turn.ordinal
            resource.sendPreferredBufferTransform(turn.ordinal)
        }
        val fraction = preferredFraction()
        val fractional = fractionalScale?.takeIf { it.isAlive }
        if (fractional != null && toldFraction != fraction) {
            toldFraction = fraction
            fractional.sendPreferredScale(fraction)
        }
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

    // Its xdg_surface while that lives (a surface may only have one at a time).
    internal var shellSurface: ShellSurface? = null

    // This surface and its subsurfaces, bottom to top (see stack).
    internal val stackList = ArrayList<Surface>().also { it += this }

    /**
     * This surface and its subsurfaces, bottom to top. The surface itself is one entry,
     * so subsurfaces can be below or above it. (A live, read-only view.)
     */
    val stack: List<Surface> = java.util.Collections.unmodifiableList(stackList)

    // Subsurfaces asked to go above or below a sibling (place_above/below): done at the next
    // commit of this surface, as the protocol says (in the order asked; one a subsurface, its last).
    internal val pendingPlacements = ArrayList<Triple<Surface, Surface, Boolean>>()

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
        nativeScale?.let { s ->
            for (damage in current.damage) {
                val left = Math.floor(damage.x / s).toInt()
                val top = Math.floor(damage.y / s).toInt()
                val right = Math.ceil((damage.x + damage.width) / s).toInt()
                val bottom = Math.ceil((damage.y + damage.height) / s).toInt()
                Rect(left, top, right - left, bottom - top).intersect(Rect(0, 0, width, height))?.let { changes += it }
            }
            return
        }
        if (current.viewportSource != null || current.viewportWidth > 0 || current.transform != WlOutput.Transform.NORMAL) {
            // Buffer pixels and surface units differ by an arbitrary factor (or the buffer is turned): redraw it all.
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
                if (width > 0 && height > 0) {
                    pending.addDamage(Rect(x * pending.scale, y * pending.scale, width * pending.scale, height * pending.scale))
                    pending.surfaceDamage = true
                }
            }

            override fun frame(resource: WlSurface, callback: WlCallback) {
                pending.frameCallbackList += callback
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
                if (width > 0 && height > 0) pending.addDamage(Rect(x, y, width, height))
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
        if (state.newBuffer && state.buffer == null && texture != null) compositor.pictureGoing(this)
        val oldWidth = width
        val oldHeight = height
        current.takeFrom(state)
        if (!checkViewport()) return
        // Subsurfaces above or below each other as asked since the last commit.
        for ((child, sibling, above) in pendingPlacements) {
            if (child.subsurface?.parent !== this || sibling !in stackList) continue
            stackList.remove(child)
            val index = stackList.indexOf(sibling)
            stackList.add(if (above) index + 1 else index, child)
        }
        pendingPlacements.clear()
        if (enteredOutputs.isEmpty() || toldBufferScale == 0) tellScale()
        if (current.newBuffer) {
            recordChanges(oldWidth, oldHeight)
            compositor.uploadContent(this)
            current.newBuffer = false
        }
        current.damageList.clear()
        // (A copy: a child's cached commit may place its own children, never these.)
        for (child in stackList.toList()) {
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
        for (callback in current.frameCallbackList) {
            callback.sendDone(timeMillis)
            callback.destroy()
        }
        current.frameCallbackList.clear()
    }

    /** True if the point (in surface coordinates) is inside the surface's input area. */
    fun acceptsInput(x: Double, y: Double): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        val region = current.inputRegion ?: return true
        // A region of a surface in screen pixels is in its pixels too.
        val s = nativeScale ?: 1.0
        return region.contains((x * s).toInt(), (y * s).toInt())
    }

    internal fun destroyed() {
        if (subsurface != null || texture != null) compositor.surfaceGone(this)
        for (feedback in current.feedbackList) feedback.discard()
        current.feedbackList.clear()
        pendingPlacements.clear()
        texture = null
        uploadedTexture?.close()
        uploadedTexture = null
        shownDmabuf?.stopShowing()
        shownDmabuf = null
        subsurface?.detach()
        for (child in stackList.toList()) if (child !== this) child.subsurface?.detach()
    }

    // (For a subsurface taken off its parent: see Compositor.surfaceGone.)
    internal fun goneFromScreen() = compositor.surfaceGone(this)

    private companion object {
        /** More changed rectangles than this are merged into one. */
        const val MAX_CHANGE_RECTS = 16
    }
}
