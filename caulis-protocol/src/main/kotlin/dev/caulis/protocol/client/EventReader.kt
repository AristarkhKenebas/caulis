package dev.caulis.protocol.client

import dev.caulis.ffi.Fd
import dev.caulis.protocol.WireReader

/** Reads the arguments of one event the other compositor sent. */
class EventReader internal constructor(private val connection: Connection) : WireReader() {

    override fun fail(message: String): Nothing = throw ConnectionException("broken event: $message")

    /** A file descriptor; the caller owns it and must close it. */
    fun fd(): Fd = connection.takeReceivedFd() ?: fail("a file descriptor was expected but none came")

    /** An existing object. If we already destroyed it, the event is dropped (it is outdated). */
    fun <T : Proxy> obj(type: Class<T>): T = objOrNull(type) ?: throw StaleEvent()

    fun <T : Proxy> objOrNull(type: Class<T>): T? {
        val id = int()
        if (id == 0) return null
        val proxy = connection.findProxy(id) ?: return null
        if (!type.isInstance(proxy)) fail("object $id is a ${proxy.type.name}, expected ${type.simpleName}")
        return type.cast(proxy)
    }

    /** A new object the compositor creates for us (e.g. a clipboard offer). */
    fun <T : Proxy> newId(type: ProxyType<T>, version: Int): T = connection.registerServerProxy(type, int(), version)
}

/** Thrown inside event reading when an event refers to an object we already destroyed. */
internal class StaleEvent : RuntimeException(null, null, false, false)

/** The connection to the other compositor broke or it sent something invalid. */
class ConnectionException(message: String) : RuntimeException(message)
