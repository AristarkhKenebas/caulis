package dev.caulis.protocol.client

/**
 * A Wayland interface seen from the client side. Every generated client class has one as
 * its companion object, so `registry.bind(name, WlCompositor, 6)` reads naturally.
 */
abstract class ProxyType<T : Proxy>(
    /** Name in the protocol, e.g. "wl_compositor". */
    val name: String,
    /** Newest version the generated code knows. */
    val version: Int,
) {
    internal abstract fun create(connection: Connection, id: Int, version: Int): T

    override fun toString() = "$name v$version"
}
