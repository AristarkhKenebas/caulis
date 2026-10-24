package dev.tessera.core

import dev.tessera.protocol.Display
import dev.tessera.protocol.wayland.WlBuffer
import dev.tessera.protocol.wayland.WlCallback
import dev.tessera.protocol.wayland.WlCompositor
import dev.tessera.protocol.wayland.WlOutput
import dev.tessera.protocol.wayland.WlRegion
import dev.tessera.protocol.wayland.WlSurface

/**
 * wl_compositor: lets clients create surfaces (the things that become windows) and regions.
 */
class Compositor(display: Display) {

    /** All surfaces of all clients, in creation order. */
    val surfaces = LinkedHashSet<Surface>()

    init {
        display.addGlobal(WlCompositor, VERSION) { compositor -> compositor.requests = requests }
    }

    private val requests = object : WlCompositor.Requests {
        override fun createSurface(resource: WlCompositor, id: WlSurface) {
            val surface = Surface(id)
            surfaces += surface
            id.onDestroy { surfaces -= surface }
        }

        override fun createRegion(resource: WlCompositor, id: WlRegion) {
            Region(id)
        }

        // Only exists in version 7 (destroys the client's handle); nothing to clean up.
        override fun release(resource: WlCompositor) {}
    }

    companion object {
        const val VERSION = 6
    }
}

/**
 * A client's drawing area. Changes (a new buffer, damage, frame callbacks) are collected
 * as "pending" and only become "current" on commit, so the compositor never sees a
 * half-updated surface.
 */
class Surface internal constructor(val resource: WlSurface) {

    /** The state the client is still preparing. */
    private val pending = State()

    /** The state of the last commit; this is what gets drawn. */
    var current = State()
        private set

    /** Waiting for "a good time to draw the next frame" (see [sendFrameDone]). */
    private val frameCallbacks = ArrayList<WlCallback>()

    class State {
        var buffer: WlBuffer? = null
        var bufferX = 0
        var bufferY = 0
        var scale = 1
        var transform = WlOutput.Transform.NORMAL
        val damage = ArrayList<Rect>()

        fun copy() = State().also {
            it.buffer = buffer
            it.bufferX = bufferX
            it.bufferY = bufferY
            it.scale = scale
            it.transform = transform
            it.damage += damage
        }
    }

    init {
        resource.requests = object : WlSurface.Requests {
            override fun destroy(resource: WlSurface) {}

            override fun attach(resource: WlSurface, buffer: WlBuffer?, x: Int, y: Int) {
                pending.buffer = buffer
                pending.bufferX = x
                pending.bufferY = y
            }

            override fun damage(resource: WlSurface, x: Int, y: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) pending.damage += Rect(x, y, width, height)
            }

            override fun frame(resource: WlSurface, callback: WlCallback) {
                frameCallbacks += callback
            }

            override fun setOpaqueRegion(resource: WlSurface, region: WlRegion?) {}

            override fun setInputRegion(resource: WlSurface, region: WlRegion?) {}

            override fun commit(resource: WlSurface) {
                current = pending.copy()
                pending.damage.clear()
            }

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
                pending.bufferX = x
                pending.bufferY = y
            }

            override fun getRelease(resource: WlSurface, callback: WlCallback) {
                // Only exists in wl_surface version 7; we offer version 6, so clients cannot call it.
                callback.destroy()
            }
        }
    }

    /** Tells the client it is a good moment to draw its next frame. [timeMillis] is a timestamp. */
    fun sendFrameDone(timeMillis: Int) {
        for (callback in frameCallbacks) {
            callback.sendDone(timeMillis)
            callback.destroy()
        }
        frameCallbacks.clear()
    }
}

/** A set of rectangles a client describes (e.g. which part of a surface accepts input). */
class Region internal constructor(resource: WlRegion) {
    val added = ArrayList<Rect>()
    val subtracted = ArrayList<Rect>()

    init {
        resource.requests = object : WlRegion.Requests {
            override fun destroy(resource: WlRegion) {}

            override fun add(resource: WlRegion, x: Int, y: Int, width: Int, height: Int) {
                added += Rect(x, y, width, height)
            }

            override fun subtract(resource: WlRegion, x: Int, y: Int, width: Int, height: Int) {
                subtracted += Rect(x, y, width, height)
            }
        }
    }
}
