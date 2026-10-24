package dev.caulis.protocol

/**
 * A Wayland interface, like `wl_surface` version 7, and a way to create objects of it.
 *
 * Every generated class has one of these as its companion object, so `WlSurface` can be
 * used both as a type and as a description: `display.addGlobal(WlCompositor, 6) { ... }`.
 */
abstract class ResourceType<T : Resource>(
    /** Name in the protocol, e.g. "wl_surface". */
    val name: String,
    /** Newest version described by the protocol XML this code was generated from. */
    val version: Int,
) {
    /**
     * Creates the object for [client] under the protocol id [id]. Internal: objects are
     * always created through [Client], which registers them, so none can exist unregistered.
     */
    internal abstract fun create(client: Client, id: Int, version: Int): T

    override fun toString() = "$name v$version"
}
