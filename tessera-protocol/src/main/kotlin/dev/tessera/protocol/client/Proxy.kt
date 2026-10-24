package dev.tessera.protocol.client

import dev.tessera.protocol.MessageWriter

/**
 * Our handle to an object that lives in another compositor (the client side of the
 * protocol; Tessera uses it to run as a window inside an existing session).
 *
 * Generated subclasses add functions for the requests (`surface.attach(buffer, 0, 0)`)
 * and an `Events` interface whose functions are called when the compositor sends events.
 */
abstract class Proxy(
    val connection: Connection,
    val id: Int,
    val version: Int,
) {
    abstract val type: ProxyType<*>

    /** False once destroyed. */
    var isAlive: Boolean = true
        internal set

    /** Called by generated code for each event the compositor sent to this object. */
    internal abstract fun dispatchEvent(opcode: Int, args: EventReader)

    /** Generated request functions use this to write a request. */
    protected fun request(opcode: Int): MessageWriter {
        check(isAlive) { "$this was already destroyed" }
        return connection.beginRequest(this, opcode)
    }

    override fun toString() = "${type.name}@$id"
}
