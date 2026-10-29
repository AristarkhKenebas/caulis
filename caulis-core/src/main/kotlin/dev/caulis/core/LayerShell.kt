package dev.caulis.core

import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.wlrlayershellunstablev1.ZwlrLayerShellV1
import dev.caulis.protocol.wlrlayershellunstablev1.ZwlrLayerSurfaceV1
import dev.caulis.protocol.xdgshell.XdgPopup

/**
 * wlr-layer-shell: panels, docks, bars, wallpapers, launchers and notifications — programs
 * that are part of the desktop rather than windows (Waybar, eww, swaybg, fuzzel, mako, a dock
 * made with Compose, ...). They pick a layer, anchor themselves to screen edges and may
 * reserve space ("exclusive zone") that windows then keep free.
 *
 * [listener] (the window manager) places them and decides who gets the keyboard.
 */
class LayerShell(display: Display) {

    interface Listener {
        /** A layer surface committed (also its first, empty commit, which asks for a size). */
        fun layerCommitted(layer: LayerSurface) {}

        /** A layer surface is gone (destroyed, or committed without a picture). */
        fun layerUnmapped(layer: LayerSurface) {}
    }

    var listener: Listener = object : Listener {}

    init {
        display.addPrivilegedGlobal(ZwlrLayerShellV1, VERSION, "panels") { shell ->
            shell.requests = object : ZwlrLayerShellV1.Requests {
                override fun getLayerSurface(resource: ZwlrLayerShellV1, id: ZwlrLayerSurfaceV1, surface: WlSurface, output: WlOutput?, layer: Int, namespace: String) {
                    if (layer !in 0..3) {
                        resource.postError(ZwlrLayerShellV1.Error.INVALID_LAYER, "no layer $layer")
                        return
                    }
                    val target = surface.dataAs<Surface>() ?: return
                    if (target.current.buffer != null) {
                        resource.postError(ZwlrLayerShellV1.Error.ALREADY_CONSTRUCTED, "surface already has a buffer")
                        return
                    }
                    if (!target.assignRole(Surface.Role.LAYER, resource, ZwlrLayerShellV1.Error.ROLE)) return
                    LayerSurface(id, target, this@LayerShell, Layer.entries[layer], resource.client.server.capForwarded(namespace), output?.dataAs<Output>())
                }

                override fun destroy(resource: ZwlrLayerShellV1) {}
            }
        }
    }

    /** The layers, bottom to top. */
    enum class Layer { BACKGROUND, BOTTOM, TOP, OVERLAY }

    companion object {
        const val VERSION = 5
    }
}

/** One panel, bar, wallpaper, ... (zwlr_layer_surface_v1). */
class LayerSurface internal constructor(
    val resource: ZwlrLayerSurfaceV1,
    val surface: Surface,
    private val shell: LayerShell,
    layer: LayerShell.Layer,
    /** What the program says it is, e.g. "waybar", "wallpaper", "notifications". */
    val namespace: String,
    /** The monitor the program wants it on, or null: the compositor chooses (the one with the focus). */
    val output: Output? = null,
) {
    /**
     * The state the program asked for (applied on its commit). Only the program's requests
     * change it; [current] is a copy that stays as it is until the next commit.
     */
    class State internal constructor(layer: LayerShell.Layer) {
        /** The layer it is drawn on (background, bottom, top, overlay). */
        var layer: LayerShell.Layer = layer
            internal set

        /** The size it asked for (0: as wide or high as the monitor between its anchors). */
        var width = 0
            internal set
        var height = 0
            internal set

        /** The edges it is anchored to (ZwlrLayerSurfaceV1.Anchor bits). */
        var anchor = 0
            internal set

        /** How much room at its edge windows leave free (0: none; -1: it covers everything, panels or not). */
        var exclusiveZone = 0
            internal set

        /** Its distance from the anchored edges. */
        var marginTop = 0
            internal set
        var marginRight = 0
            internal set
        var marginBottom = 0
            internal set
        var marginLeft = 0
            internal set

        /** Whether it takes the keyboard (ZwlrLayerSurfaceV1.KeyboardInteractivity). */
        var keyboard = ZwlrLayerSurfaceV1.KeyboardInteractivity.NONE
            internal set

        /** The edge its exclusive zone is at, if it is anchored to several (0: chosen from the anchors). */
        var exclusiveEdge = 0
            internal set

        internal fun copy() = State(layer).also {
            it.width = width
            it.height = height
            it.anchor = anchor
            it.exclusiveZone = exclusiveZone
            it.marginTop = marginTop
            it.marginRight = marginRight
            it.marginBottom = marginBottom
            it.marginLeft = marginLeft
            it.keyboard = keyboard
            it.exclusiveEdge = exclusiveEdge
        }
    }

    private val pending = State(layer)

    /** What is in effect since the last commit. */
    var current = pending.copy()
        private set

    /** Where the window manager put it (top left), in desktop pixels. */
    var x = 0
        internal set
    var y = 0
        internal set

    /** The size last sent with configure (what the program draws). */
    var configuredWidth = 0
        private set
    var configuredHeight = 0
        private set

    /** True once the program answered the first configure (it may show pictures from then on). */
    var acknowledged = false
        private set

    /** True while it shows a picture. */
    var mapped = false
        private set

    private var configureSent = false
    private var serial = 0

    init {
        resource.data = this
        surface.roleObject = this
        resource.requests = object : ZwlrLayerSurfaceV1.Requests {
            override fun setSize(resource: ZwlrLayerSurfaceV1, width: Int, height: Int) {
                pending.width = width.coerceAtLeast(0)
                pending.height = height.coerceAtLeast(0)
            }

            override fun setAnchor(resource: ZwlrLayerSurfaceV1, anchor: Int) {
                if (anchor and ALL_EDGES.inv() != 0) {
                    resource.postError(ZwlrLayerSurfaceV1.Error.INVALID_ANCHOR, "invalid anchor $anchor")
                    return
                }
                pending.anchor = anchor
            }

            override fun setExclusiveZone(resource: ZwlrLayerSurfaceV1, zone: Int) {
                pending.exclusiveZone = zone
            }

            override fun setMargin(resource: ZwlrLayerSurfaceV1, top: Int, right: Int, bottom: Int, left: Int) {
                pending.marginTop = top
                pending.marginRight = right
                pending.marginBottom = bottom
                pending.marginLeft = left
            }

            override fun setKeyboardInteractivity(resource: ZwlrLayerSurfaceV1, keyboardInteractivity: Int) {
                if (keyboardInteractivity !in 0..2) {
                    resource.postError(ZwlrLayerSurfaceV1.Error.INVALID_KEYBOARD_INTERACTIVITY, "invalid keyboard interactivity")
                    return
                }
                pending.keyboard = keyboardInteractivity
            }

            override fun getPopup(resource: ZwlrLayerSurfaceV1, popup: XdgPopup) {
                // A menu of this panel: it is placed relative to the panel.
                popup.dataAs<Popup>()?.layerParent = this@LayerSurface
            }

            override fun ackConfigure(resource: ZwlrLayerSurfaceV1, serial: Int) {
                // Serials go up: one never sent (or ahead of the last) is the program's mistake.
                if (!configureSent || Integer.compareUnsigned(serial, this@LayerSurface.serial) > 0) {
                    resource.postError(ZwlrLayerSurfaceV1.Error.INVALID_SURFACE_STATE, "serial $serial was never sent")
                    return
                }
                acknowledged = true
            }

            override fun destroy(resource: ZwlrLayerSurfaceV1) = unmap()

            override fun setLayer(resource: ZwlrLayerSurfaceV1, layer: Int) {
                if (layer !in 0..3) {
                    resource.postError(ZwlrLayerShellV1.Error.INVALID_LAYER, "no layer $layer")
                    return
                }
                pending.layer = LayerShell.Layer.entries[layer]
            }

            override fun setExclusiveEdge(resource: ZwlrLayerSurfaceV1, edge: Int) {
                if (edge != 0 && (edge and pending.anchor) == 0) {
                    resource.postError(ZwlrLayerSurfaceV1.Error.INVALID_EXCLUSIVE_EDGE, "the exclusive edge must be anchored")
                    return
                }
                pending.exclusiveEdge = edge
            }
        }
        resource.onDestroy { unmap() }
        surface.onCommit { committed() }
    }

    private fun committed() {
        if (!resource.isAlive) return
        // A width of 0 means "as wide as the screen": that needs both side edges anchored.
        val s = pending
        if ((s.width == 0 && (s.anchor and LEFT_RIGHT) != LEFT_RIGHT) || (s.height == 0 && (s.anchor and TOP_BOTTOM) != TOP_BOTTOM)) {
            resource.postError(ZwlrLayerSurfaceV1.Error.INVALID_SIZE, "a size of 0 needs both opposite edges anchored")
            return
        }
        if (surface.current.buffer != null && !acknowledged) {
            resource.postError(ZwlrLayerSurfaceV1.Error.INVALID_SURFACE_STATE, "a buffer was attached before the first configure was acknowledged")
            return
        }
        current = pending.copy()
        val wasMapped = mapped
        mapped = surface.hasContent && acknowledged
        if (wasMapped && !mapped) {
            unmap()
            return
        }
        shell.listener.layerCommitted(this)
    }

    /** Tells the program the size to draw in (0 is not sent: the window manager always decides). */
    fun configure(width: Int, height: Int) {
        if (configureSent && width == configuredWidth && height == configuredHeight) return
        configureSent = true
        configuredWidth = width
        configuredHeight = height
        resource.sendConfigure(++serial, width, height)
    }

    /** True if [configure] was called at least once. */
    val configured: Boolean get() = configureSent

    /** The program should go away (e.g. its monitor was unplugged). */
    fun close() = resource.sendClosed()

    private var gone = false

    private fun unmap() {
        if (gone) return
        val wasShown = mapped || configureSent
        mapped = false
        if (!resource.isAlive) gone = true
        if (wasShown) shell.listener.layerUnmapped(this)
        // After an unmap the program starts over with an empty commit and a new configure.
        configureSent = false
        acknowledged = false
    }

    override fun toString() = "LayerSurface($namespace, ${current.layer})"

    companion object {
        private const val TOP = ZwlrLayerSurfaceV1.Anchor.TOP
        private const val BOTTOM = ZwlrLayerSurfaceV1.Anchor.BOTTOM
        private const val LEFT = ZwlrLayerSurfaceV1.Anchor.LEFT
        private const val RIGHT = ZwlrLayerSurfaceV1.Anchor.RIGHT
        internal const val LEFT_RIGHT = LEFT or RIGHT
        internal const val TOP_BOTTOM = TOP or BOTTOM
        internal const val ALL_EDGES = TOP or BOTTOM or LEFT or RIGHT
    }
}
