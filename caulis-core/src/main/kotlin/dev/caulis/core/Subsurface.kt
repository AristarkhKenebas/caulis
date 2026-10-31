package dev.caulis.core

import dev.caulis.protocol.wayland.WlSubsurface
import dev.caulis.protocol.wayland.WlSurface

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
        parent.stackList += surface // new subsurfaces start on top of their parent

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

    // Checked now, done at the parent's next commit (the order of subsurfaces is double-buffered).
    private fun place(resource: WlSubsurface, sibling: WlSurface, above: Boolean) {
        val parent = parent ?: return
        val target = sibling.dataAs<Surface>()
        if (target == null || target === surface || target !in parent.stackList) {
            resource.postError(WlSubsurface.Error.BAD_SURFACE, "the sibling is not a sibling of this subsurface")
            return
        }
        // One request a subsurface (its last): a program asking again and again without a
        // commit does not grow the list without end.
        parent.pendingPlacements.removeAll { it.first === surface }
        parent.pendingPlacements += Triple(surface, target, above)
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
        if (parent != null) surface.goneFromScreen()
        parent?.stackList?.remove(surface)
        parent = null
        if (surface.subsurface === this) surface.subsurface = null
    }
}
