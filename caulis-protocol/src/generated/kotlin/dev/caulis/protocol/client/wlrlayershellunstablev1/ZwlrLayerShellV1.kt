// Generated from wlr-layer-shell-unstable-v1.xml. Do not edit: run ./gradlew :caulis-protocol:generateProtocols
@file:Suppress("unused", "UNUSED_PARAMETER", "RedundantVisibilityModifier")

package dev.caulis.protocol.client.wlrlayershellunstablev1

import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.EventReader
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.wayland.WlOutput
import dev.caulis.protocol.client.wayland.WlSurface

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
public class ZwlrLayerShellV1 private constructor(connection: Connection, id: Int, version: Int) : Proxy(connection, id, version) {

    override val type: ProxyType<ZwlrLayerShellV1> get() = Companion

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
    public fun getLayerSurface(surface: WlSurface, output: WlOutput?, layer: Int, namespace: String): ZwlrLayerSurfaceV1 {
        val created = connection.createProxy(ZwlrLayerSurfaceV1, this.version)
        request(0).newId(created).obj(surface).obj(output).uint(layer).string(namespace).end()
        return created
    }

    /**
     * Destroy the layer_shell object
     *
     * This request indicates that the client will not use the layer_shell
     * object any more. Objects that have been created through this instance
     * are not affected.
     *
     * Since version 3.
     */
    public fun destroy(): Unit {
        check(version >= 3) { "zwlr_layer_shell_v1.destroy needs version 3, this object has $version" }
        request(1).end()
        connection.destroyProxy(this)
    }

    public object Error {
        /** wl_surface has another role */
        public const val ROLE: Int = 0
        /** layer value is invalid */
        public const val INVALID_LAYER: Int = 1
        /** wl_surface has a buffer attached or committed */
        public const val ALREADY_CONSTRUCTED: Int = 2
    }

    /** available layers for surfaces */
    public object Layer {
        public const val BACKGROUND: Int = 0
        public const val BOTTOM: Int = 1
        public const val TOP: Int = 2
        public const val OVERLAY: Int = 3
    }

    override fun dispatchEvent(opcode: Int, args: EventReader) {
    }

    public companion object : ProxyType<ZwlrLayerShellV1>("zwlr_layer_shell_v1", 5) {
        internal override fun create(connection: Connection, id: Int, version: Int): ZwlrLayerShellV1 = ZwlrLayerShellV1(connection, id, version)
    }
}
