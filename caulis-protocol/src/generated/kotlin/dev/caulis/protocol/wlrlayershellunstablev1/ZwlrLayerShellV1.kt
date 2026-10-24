// Generated from wlr-layer-shell-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.wlrlayershellunstablev1

import dev.caulis.protocol.Client
import dev.caulis.protocol.MessageReader
import dev.caulis.protocol.ProtocolException
import dev.caulis.protocol.Resource
import dev.caulis.protocol.ResourceType
import dev.caulis.protocol.wayland.WlDisplay
import dev.caulis.protocol.wayland.WlOutput
import dev.caulis.protocol.wayland.WlSurface

/**
 * Create surfaces that are layers of the desktop
 *
 * Clients can use this interface to assign the surface_layer role to
 * wl_surfaces. Such surfaces are assigned to a "layer" of the output and
 * rendered with a defined z-depth respective to each other. They may also be
 * anchored to the edges and corners of a screen and specify input handling
 * semantics. This interface should be suitable for the implementation of
 * many desktop shell components, and a broad number of other applications
 * that interact with the desktop.
 */
public class ZwlrLayerShellV1 private constructor(client: Client, id: Int, version: Int) : Resource(client, id, version) {

    override val type: ResourceType<ZwlrLayerShellV1> get() = Companion

    /** Handles the requests clients send to this object; set by the code that implements zwlr_layer_shell_v1. */
    public var requests: Requests? = null

    public interface Requests {
        /**
         * Create a layer_surface from a surface
         *
         * Create a layer surface for an existing surface. This assigns the role of
         * layer_surface, or raises a protocol error if another role is already
         * assigned.
         *
         * Creating a layer surface from a wl_surface which has a buffer attached
         * or committed is a client error, and any attempts by a client to attach
         * or manipulate a buffer prior to the first layer_surface.configure call
         * must also be treated as errors.
         *
         * After creating a layer_surface object and setting it up, the client
         * must perform an initial commit without any buffer attached.
         * The compositor will reply with a layer_surface.configure event.
         * The client must acknowledge it and is then allowed to attach a buffer
         * to map the surface.
         *
         * You may pass NULL for output to allow the compositor to decide which
         * output to use. Generally this will be the one that the user most
         * recently interacted with.
         *
         * Clients can specify a namespace that defines the purpose of the layer
         * surface.
         */
        public fun getLayerSurface(resource: ZwlrLayerShellV1, id: ZwlrLayerSurfaceV1, surface: WlSurface, output: WlOutput?, layer: Int, namespace: String)

        /**
         * Destroy the layer_shell object
         *
         * This request indicates that the client will not use the layer_shell
         * object any more. Objects that have been created through this instance
         * are not affected.
         *
         * Since version 3.
         */
        public fun destroy(resource: ZwlrLayerShellV1)
    }

    public object Error {
        /** wl_surface has another role */
        public const val ROLE: Int = 0
        /** layer value is invalid */
        public const val INVALID_LAYER: Int = 1
        /** wl_surface has a buffer attached or committed */
        public const val ALREADY_CONSTRUCTED: Int = 2
    }

    /**
     * Available layers for surfaces
     */
    public object Layer {
        public const val BACKGROUND: Int = 0
        public const val BOTTOM: Int = 1
        public const val TOP: Int = 2
        public const val OVERLAY: Int = 3
    }

    override fun dispatch(opcode: Int, args: MessageReader) {
        val handler = requests
            ?: throw ProtocolException(client.displayObject, WlDisplay.Error.IMPLEMENTATION, "zwlr_layer_shell_v1 is not implemented")
        when (opcode) {
            0 -> {
                val idId = args.newId()
                val surface = args.obj(WlSurface::class.java)
                val output = args.objOrNull(WlOutput::class.java)
                val layer = args.uint()
                val namespace = args.string()
                args.end()
                val id = client.register(ZwlrLayerSurfaceV1.create(client, idId, version))
                handler.getLayerSurface(this, id, surface, output, layer, namespace)
            }
            1 -> {
                if (version < 3) throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "zwlr_layer_shell_v1.destroy needs version 3")
                args.end()
                handler.destroy(this)
                client.destroyResource(this)
            }
            else -> throw ProtocolException(client.displayObject, WlDisplay.Error.INVALID_METHOD, "unknown zwlr_layer_shell_v1 request $opcode")
        }
    }

    public companion object : ResourceType<ZwlrLayerShellV1>("zwlr_layer_shell_v1", 5) {
        internal override fun create(client: Client, id: Int, version: Int): ZwlrLayerShellV1 = ZwlrLayerShellV1(client, id, version)
    }
}
