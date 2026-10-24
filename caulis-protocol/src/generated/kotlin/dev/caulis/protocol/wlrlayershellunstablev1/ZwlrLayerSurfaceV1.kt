// Generated from wlr-layer-shell-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrlayershellunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.xdgshell.XdgPopup

/**
 * Layer metadata interface
 *
 * An interface that may be implemented by a wl_surface, for surfaces that
 * are designed to be rendered as a layer of a stacked desktop-like
 * environment.
 *
 * Layer surface state (layer, size, anchor, exclusive zone,
 * margin, interactivity) is double-buffered, and will be applied at the
 * time wl_surface.commit of the corresponding wl_surface is called.
 *
 * Attaching a null buffer to a layer surface unmaps it.
 *
 * Unmapping a layer_surface means that the surface cannot be shown by the
 * compositor until it is explicitly mapped again. The layer_surface
 * returns to the state it had right after layer_shell.get_layer_surface.
 * The client can re-map the surface by performing a commit without any
 * buffer attached, waiting for a configure event and handling it as usual.
 */
public class ZwlrLayerSurfaceV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrLayerSurfaceV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_layer_surface_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Sets the size of the surface
         *
         * Sets the size of the surface in surface-local coordinates. The
         * compositor will display the surface centered with respect to its
         * anchors.
         *
         * If you pass 0 for either value, the compositor will assign it and
         * inform you of the assignment in the configure event. You must set your
         * anchor to opposite edges in the dimensions you omit; not doing so is a
         * protocol error. Both values are 0 by default.
         *
         * Size is double-buffered, see wl_surface.commit.
         */
        public fun setSize(resource: ZwlrLayerSurfaceV1, width: Int, height: Int)

        /**
         * Configures the anchor point of the surface
         *
         * Requests that the compositor anchor the surface to the specified edges
         * and corners. If two orthogonal edges are specified (e.g. 'top' and
         * 'left'), then the anchor point will be the intersection of the edges
         * (e.g. the top left corner of the output); otherwise the anchor point
         * will be centered on that edge, or in the center if none is specified.
         *
         * Anchor is double-buffered, see wl_surface.commit.
         */
        public fun setAnchor(resource: ZwlrLayerSurfaceV1, anchor: Int)

        /**
         * Configures the exclusive geometry of this surface
         *
         * Requests that the compositor avoids occluding an area with other
         * surfaces. The compositor's use of this information is
         * implementation-dependent - do not assume that this region will not
         * actually be occluded.
         *
         * A positive value is only meaningful if the surface is anchored to one
         * edge or an edge and both perpendicular edges. If the surface is not
         * anchored, anchored to only two perpendicular edges (a corner), anchored
         * to only two parallel edges or anchored to all edges, a positive value
         * will be treated the same as zero.
         *
         * A positive zone is the distance from the edge in surface-local
         * coordinates to consider exclusive.
         *
         * Surfaces that do not wish to have an exclusive zone may instead specify
         * how they should interact with surfaces that do. If set to zero, the
         * surface indicates that it would like to be moved to avoid occluding
         * surfaces with a positive exclusive zone. If set to -1, the surface
         * indicates that it would not like to be moved to accommodate for other
         * surfaces, and the compositor should extend it all the way to the edges
         * it is anchored to.
         *
         * For example, a panel might set its exclusive zone to 10, so that
         * maximized shell surfaces are not shown on top of it. A notification
         * might set its exclusive zone to 0, so that it is moved to avoid
         * occluding the panel, but shell surfaces are shown underneath it. A
         * wallpaper or lock screen might set their exclusive zone to -1, so that
         * they stretch below or over the panel.
         *
         * The default value is 0.
         *
         * Exclusive zone is double-buffered, see wl_surface.commit.
         */
        public fun setExclusiveZone(resource: ZwlrLayerSurfaceV1, zone: Int)

        /**
         * Sets a margin from the anchor point
         *
         * Requests that the surface be placed some distance away from the anchor
         * point on the output, in surface-local coordinates. Setting this value
         * for edges you are not anchored to has no effect.
         *
         * The exclusive zone includes the margin.
         *
         * Margin is double-buffered, see wl_surface.commit.
         */
        public fun setMargin(resource: ZwlrLayerSurfaceV1, top: Int, right: Int, bottom: Int, left: Int)

        /**
         * Requests keyboard events
         *
         * Set how keyboard events are delivered to this surface. By default,
         * layer shell surfaces do not receive keyboard events; this request can
         * be used to change this.
         *
         * This setting is inherited by child surfaces set by the get_popup
         * request.
         *
         * Layer surfaces receive pointer, touch, and tablet events normally. If
         * you do not want to receive them, set the input region on your surface
         * to an empty region.
         *
         * Keyboard interactivity is double-buffered, see wl_surface.commit.
         */
        public fun setKeyboardInteractivity(resource: ZwlrLayerSurfaceV1, keyboardInteractivity: Int)

        /**
         * Assign this layer_surface as an xdg_popup parent
         *
         * This assigns an xdg_popup's parent to this layer_surface.  This popup
         * should have been created via xdg_surface::get_popup with the parent set
         * to NULL, and this request must be invoked before committing the popup's
         * initial state.
         *
         * See the documentation of xdg_popup for more details about what an
         * xdg_popup is and how it is used.
         */
        public fun getPopup(resource: ZwlrLayerSurfaceV1, popup: XdgPopup)

        /**
         * Ack a configure event
         *
         * When a configure event is received, if a client commits the
         * surface in response to the configure event, then the client
         * must make an ack_configure request sometime before the commit
         * request, passing along the serial of the configure event.
         *
         * If the client receives multiple configure events before it
         * can respond to one, it only has to ack the last configure event.
         *
         * A client is not required to commit immediately after sending
         * an ack_configure request - it may even ack_configure several times
         * before its next surface commit.
         *
         * A client may send multiple ack_configure requests before committing, but
         * only the last request sent before a commit indicates which configure
         * event the client really is responding to.
         */
        public fun ackConfigure(resource: ZwlrLayerSurfaceV1, serial: Int)

        /**
         * Destroy the layer_surface
         *
         * This request destroys the layer surface.
         */
        public fun destroy(resource: ZwlrLayerSurfaceV1)

        /**
         * Change the layer of the surface
         *
         * Change the layer that the surface is rendered on.
         *
         * Layer is double-buffered, see wl_surface.commit.
         *
         * Since version 2.
         */
        public fun setLayer(resource: ZwlrLayerSurfaceV1, layer: Int)

        /**
         * Set the edge the exclusive zone will be applied to
         *
         * Requests an edge for the exclusive zone to apply. The exclusive
         * edge will be automatically deduced from anchor points when possible,
         * but when the surface is anchored to a corner, it will be necessary
         * to set it explicitly to disambiguate, as it is not possible to deduce
         * which one of the two corner edges should be used.
         *
         * The edge must be one the surface is anchored to, otherwise the
         * invalid_exclusive_edge protocol error will be raised.
         *
         * Since version 5.
         */
        public fun setExclusiveEdge(resource: ZwlrLayerSurfaceV1, edge: Int)
    }

    /**
     * Suggest a surface change
     *
     * The configure event asks the client to resize its surface.
     *
     * Clients should arrange their surface for the new states, and then send
     * an ack_configure request with the serial sent in this configure event at
     * some point before committing the new surface.
     *
     * The client is free to dismiss all but the last configure event it
     * received.
     *
     * The width and height arguments specify the size of the window in
     * surface-local coordinates.
     *
     * The size is a hint, in the sense that the client is free to ignore it if
     * it doesn't resize, pick a smaller size (to satisfy aspect ratio or
     * resize in steps of NxM pixels). If the client picks a smaller size and
     * is anchored to two opposite anchors (e.g. 'top' and 'bottom'), the
     * surface will be centered on this axis.
     *
     * If the width or height arguments are zero, it means the client should
     * decide its own window dimension.
     */
    public fun sendConfigure(serial: Int, width: Int, height: Int) {
        if (!isAlive || !client.isConnected) return
        event(0).uint(serial).uint(width).uint(height).end()
    }

    /**
     * Surface should be closed
     *
     * The closed event is sent by the compositor when the surface will no
     * longer be shown. The output may have been destroyed or the user may
     * have asked for it to be removed. Further changes to the surface will be
     * ignored. The client should destroy the resource after receiving this
     * event, and create a new surface if they so choose.
     */
    public fun sendClosed() {
        if (!isAlive || !client.isConnected) return
        event(1).end()
    }

    /**
     * Types of keyboard interaction possible for a layer shell surface
     */
    public object KeyboardInteractivity {
        public const val NONE: Int = 0
        public const val EXCLUSIVE: Int = 1
        public const val ON_DEMAND: Int = 2
    }

    public object Error {
        /** provided surface state is invalid */
        public const val INVALID_SURFACE_STATE: Int = 0
        /** size is invalid */
        public const val INVALID_SIZE: Int = 1
        /** anchor bitfield is invalid */
        public const val INVALID_ANCHOR: Int = 2
        /** keyboard interactivity is invalid */
        public const val INVALID_KEYBOARD_INTERACTIVITY: Int = 3
        /** exclusive edge is invalid given the surface anchors */
        public const val INVALID_EXCLUSIVE_EDGE: Int = 4
    }

    /**
     * Bit flags: combine with `or`.
     */
    public object Anchor {
        /** the top edge of the anchor rectangle */
        public const val TOP: Int = 1
        /** the bottom edge of the anchor rectangle */
        public const val BOTTOM: Int = 2
        /** the left edge of the anchor rectangle */
        public const val LEFT: Int = 4
        /** the right edge of the anchor rectangle */
        public const val RIGHT: Int = 8
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_layer_surface_v1 is not implemented")
        when (opcode) {
            0 -> {
                val width = args.uint()
                val height = args.uint()
                args.end()
                handler.setSize(this, width, height)
            }
            1 -> {
                val anchor = args.uint()
                args.end()
                handler.setAnchor(this, anchor)
            }
            2 -> {
                val zone = args.int()
                args.end()
                handler.setExclusiveZone(this, zone)
            }
            3 -> {
                val top = args.int()
                val right = args.int()
                val bottom = args.int()
                val left = args.int()
                args.end()
                handler.setMargin(this, top, right, bottom, left)
            }
            4 -> {
                val keyboardInteractivity = args.uint()
                args.end()
                handler.setKeyboardInteractivity(this, keyboardInteractivity)
            }
            5 -> {
                val popup = args.obj(XdgPopup::class.java)
                args.end()
                handler.getPopup(this, popup)
            }
            6 -> {
                val serial = args.uint()
                args.end()
                handler.ackConfigure(this, serial)
            }
            7 -> {
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            8 -> {
                if (version < 2) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_layer_surface_v1.set_layer needs version 2")
                val layer = args.uint()
                args.end()
                handler.setLayer(this, layer)
            }
            9 -> {
                if (version < 5) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_layer_surface_v1.set_exclusive_edge needs version 5")
                val edge = args.uint()
                args.end()
                handler.setExclusiveEdge(this, edge)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_layer_surface_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrLayerSurfaceV1>("zwlr_layer_surface_v1", 5) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrLayerSurfaceV1 = ZwlrLayerSurfaceV1(client, id, version)
    }
}
