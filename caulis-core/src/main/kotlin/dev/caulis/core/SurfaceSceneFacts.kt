package dev.caulis.core

import dev.caulis.geometry.Rect

/**
 * All that the desktop's scene (and the search for what is under the pointer) reads from a
 * surface, apart from the pixels of its picture. A commit that leaves all of it as it was changed
 * only pixels: the scene stays and only the changed parts are drawn again.
 *
 * When in doubt something belongs here: a fact too many costs one rebuilt scene; a fact missing
 * leaves a stale picture on screen.
 */
internal class SurfaceSceneFacts(private val surface: Surface) {
    private var width = 0
    private var height = 0
    private var hasPicture = false
    private var opaque = false
    private var scale = 1
    private var transform = 0
    private var offsetX = 0
    private var offsetY = 0
    private var viewportWidth = -1
    private var viewportHeight = -1
    private var viewportSource: DoubleArray? = null
    private var nativeScale: Double? = null
    private var opaqueRegion: Region.Snapshot? = null
    private var inputRegion: Region.Snapshot? = null
    private var geometry: Rect? = null

    // Its window's or menu's place and kind (an X11 program moves its window itself, and that was
    // shown with its next picture).
    private var roleX = 0
    private var roleY = 0
    private var roleKind = 0
    private val stack = ArrayList<Surface>()
    private var childPlaces = IntArray(0)

    /** True if something changed since the last call (the facts are then remembered anew). */
    fun changedSinceLastTime(): Boolean {
        val state = surface.current
        val children = childrenAsBefore()
        val same = children &&
            width == surface.width && height == surface.height && hasPicture == (surface.texture != null) && opaque == surface.opaque &&
            scale == state.scale && transform == state.transform && offsetX == state.offsetX && offsetY == state.offsetY &&
            viewportWidth == state.viewportWidth && viewportHeight == state.viewportHeight &&
            viewportSource.contentEquals(state.viewportSource) && nativeScale == surface.nativeScale &&
            sameRegion(opaqueRegion, state.opaqueRegion) && sameRegion(inputRegion, state.inputRegion) &&
            geometry == surface.shellSurface?.geometry &&
            roleX == roleXNow() && roleY == roleYNow() && roleKind == roleKindNow()
        if (same) return false
        width = surface.width
        height = surface.height
        hasPicture = surface.texture != null
        opaque = surface.opaque
        scale = state.scale
        transform = state.transform
        offsetX = state.offsetX
        offsetY = state.offsetY
        viewportWidth = state.viewportWidth
        viewportHeight = state.viewportHeight
        viewportSource = state.viewportSource
        nativeScale = surface.nativeScale
        opaqueRegion = state.opaqueRegion
        inputRegion = state.inputRegion
        geometry = surface.shellSurface?.geometry
        roleX = roleXNow()
        roleY = roleYNow()
        roleKind = roleKindNow()
        return true
    }

    private fun roleXNow(): Int = when (val role = surface.roleObject) {
        is Window -> role.x
        is Popup -> role.x
        else -> 0
    }

    private fun roleYNow(): Int = when (val role = surface.roleObject) {
        is Window -> role.y
        is Popup -> role.y
        else -> 0
    }

    private fun roleKindNow(): Int = (surface.roleObject as? Window)?.let { window ->
        (if (window.fullscreen) 1 else 0) or (if (window.maximized) 2 else 0) or (if (window.tiled) 4 else 0) or (if (window.mapped) 8 else 0)
    } ?: 0

    // Its subsurfaces in the same order at the same places? (If not, they are remembered anew.)
    private fun childrenAsBefore(): Boolean {
        val now = surface.stackList
        var same = stack == now && childPlaces.size == now.size * 2
        if (same) {
            for (i in now.indices) {
                val sub = now[i].takeIf { it !== surface }?.subsurface
                if (childPlaces[i * 2] != (sub?.x ?: 0) || childPlaces[i * 2 + 1] != (sub?.y ?: 0)) same = false
            }
        }
        if (same) return true
        stack.clear()
        stack += now
        childPlaces = IntArray(now.size * 2)
        for (i in now.indices) {
            val sub = now[i].takeIf { it !== surface }?.subsurface
            childPlaces[i * 2] = sub?.x ?: 0
            childPlaces[i * 2 + 1] = sub?.y ?: 0
        }
        return false
    }

    private fun sameRegion(a: Region.Snapshot?, b: Region.Snapshot?): Boolean =
        a === b || (a != null && b != null && a.sameAs(b))
}
