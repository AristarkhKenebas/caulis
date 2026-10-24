// Generated from viewporter.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.viewporter

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.Wire
import dev.caulis.protocol.wayland.WlDisplay

/**
 * Crop and scale interface to a wl_surface
 *
 * An additional interface to a wl_surface object, which allows the
 * client to specify the cropping and scaling of the surface
 * contents.
 *
 * This interface works with two concepts: the source rectangle (src_x,
 * src_y, src_width, src_height), and the destination size (dst_width,
 * dst_height). The contents of the source rectangle are scaled to the
 * destination size, and content outside the source rectangle is ignored.
 * This state is double-buffered, see wl_surface.commit.
 *
 * The two parts of crop and scale state are independent: the source
 * rectangle, and the destination size. Initially both are unset, that
 * is, no scaling is applied. The whole of the current wl_buffer is
 * used as the source, and the surface size is as defined in
 * wl_surface.attach.
 *
 * If the destination size is set, it causes the surface size to become
 * dst_width, dst_height. The source (rectangle) is scaled to exactly
 * this size. This overrides whatever the attached wl_buffer size is,
 * unless the wl_buffer is NULL. If the wl_buffer is NULL, the surface
 * has no content and therefore no size. Otherwise, the size is always
 * at least 1x1 in surface local coordinates.
 *
 * If the source rectangle is set, it defines what area of the wl_buffer is
 * taken as the source. If the source rectangle is set and the destination
 * size is not set, then src_width and src_height must be integers, and the
 * surface size becomes the source rectangle size. This results in cropping
 * without scaling. If src_width or src_height are not integers and
 * destination size is not set, the bad_size protocol error is raised when
 * the surface state is applied.
 *
 * The coordinate transformations from buffer pixel coordinates up to
 * the surface-local coordinates happen in the following order:
 *   1. buffer_transform (wl_surface.set_buffer_transform)
 *   2. buffer_scale (wl_surface.set_buffer_scale)
 *   3. crop and scale (wp_viewport.set*)
 * This means, that the source rectangle coordinates of crop and scale
 * are given in the coordinates after the buffer transform and scale,
 * i.e. in the coordinates that would be the surface-local coordinates
 * if the crop and scale was not applied.
 *
 * If src_x or src_y are negative, the bad_value protocol error is raised.
 * Otherwise, if the source rectangle is partially or completely outside of
 * the non-NULL wl_buffer, then the out_of_buffer protocol error is raised
 * when the surface state is applied. A NULL wl_buffer does not raise the
 * out_of_buffer error.
 *
 * If the wl_surface associated with the wp_viewport is destroyed,
 * all wp_viewport requests except 'destroy' raise the protocol error
 * no_surface.
 *
 * If the wp_viewport object is destroyed, the crop and scale
 * state is removed from the wl_surface. The change will be applied
 * on the next wl_surface.commit.
 */
public class WpViewport private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<WpViewport> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements wp_viewport. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Remove scaling and cropping from the surface
         *
         * The associated wl_surface's crop and scale state is removed.
         * The change is applied on the next wl_surface.commit.
         */
        public fun destroy(resource: WpViewport)

        /**
         * Set the source rectangle for cropping
         *
         * Set the source rectangle of the associated wl_surface. See
         * wp_viewport for the description, and relation to the wl_buffer
         * size.
         *
         * If all of x, y, width and height are -1.0, the source rectangle is
         * unset instead. Any other set of values where width or height are zero
         * or negative, or x or y are negative, raise the bad_value protocol
         * error.
         *
         * The crop and scale state is double-buffered, see wl_surface.commit.
         */
        public fun setSource(resource: WpViewport, x: Double, y: Double, width: Double, height: Double)

        /**
         * Set the surface size for scaling
         *
         * Set the destination size of the associated wl_surface. See
         * wp_viewport for the description, and relation to the wl_buffer
         * size.
         *
         * If width is -1 and height is -1, the destination size is unset
         * instead. Any other pair of values for width and height that
         * contains zero or negative values raises the bad_value protocol
         * error.
         *
         * The crop and scale state is double-buffered, see wl_surface.commit.
         */
        public fun setDestination(resource: WpViewport, width: Int, height: Int)
    }

    public object Error {
        /** negative or zero values in width or height */
        public const val BAD_VALUE: Int = 0
        /** destination size is not integer */
        public const val BAD_SIZE: Int = 1
        /** source rectangle extends outside of the content area */
        public const val OUT_OF_BUFFER: Int = 2
        /** the wl_surface was destroyed */
        public const val NO_SURFACE: Int = 3
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "wp_viewport is not implemented")
        when (opcode) {
            0 -> {
                args.end()
                if (Wire.tracing) Wire.request(this, "destroy", "")
                handler.destroy(this)
                client.destroyResource(this)
            }
            1 -> {
                val x = args.fixed()
                val y = args.fixed()
                val width = args.fixed()
                val height = args.fixed()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_source", "x=${Wire.show(x)}, y=${Wire.show(y)}, width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler.setSource(this, x, y, width, height)
            }
            2 -> {
                val width = args.int()
                val height = args.int()
                args.end()
                if (Wire.tracing) Wire.request(this, "set_destination", "width=${Wire.show(width)}, height=${Wire.show(height)}")
                handler.setDestination(this, width, height)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown wp_viewport request $opcode")
        }
    }

    public companion object : ResourceType<WpViewport>("wp_viewport", 1) {
        internal override fun create(client: Client, id: Int, version: Int): WpViewport = WpViewport(client, id, version)
    }
}
