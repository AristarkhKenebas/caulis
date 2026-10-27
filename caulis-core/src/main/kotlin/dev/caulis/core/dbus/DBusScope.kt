package dev.caulis.core.dbus

import dev.caulis.ffi.Fd
import dev.caulis.log.Logger

/**
 * A [DBusConnection] as one part of a program uses it: what it exports and the signals it
 * listens to are remembered, and [close] takes them all away — the connection itself stays
 * open for the others. A compositor's configuration gets one, so a reload does not leave the
 * old configuration's objects and listeners behind.
 */
class DBusScope(
    /** The shared connection underneath. */
    val connection: DBusConnection,
) : AutoCloseable {

    private val log = Logger("dbus")
    private val owned = ArrayList<AutoCloseable>()
    private var closed = false

    /** Our name on the bus (":1.42"). */
    val uniqueName: String? get() = connection.uniqueName

    /** See [DBusConnection.call]. */
    @JvmOverloads
    fun call(
        destination: String, path: ObjectPath, iface: String, member: String,
        signature: String = "", args: List<Any?> = emptyList(), fds: List<Fd> = emptyList(),
        reply: ((DBusMessage) -> Unit)? = null,
    ) = connection.call(destination, path, iface, member, signature, args, fds, reply)

    /** See [DBusConnection.requestName] (a name stays ours after [close]: the next scope may ask again). */
    @JvmOverloads
    fun requestName(name: String, done: ((Boolean) -> Unit)? = null) = connection.requestName(name, done)

    /** See [DBusConnection.export]; taken away by [close] too. */
    fun export(path: String, iface: String, methods: Map<String, DBusMethod>): AutoCloseable =
        own(connection.export(path, iface, methods))

    /** See [DBusConnection.emitSignal]. */
    @JvmOverloads
    fun emitSignal(path: String, iface: String, member: String, signature: String = "", args: List<Any?> = emptyList()) =
        connection.emitSignal(path, iface, member, signature, args)

    /** See [DBusConnection.onSignal]; stopped by [close] too. */
    @JvmOverloads
    fun onSignal(iface: String, member: String, path: String? = null, sender: String? = null, handler: (DBusMessage) -> Unit): AutoCloseable =
        own(connection.onSignal(iface, member, path, sender, handler))

    /** See [DBusConnection.keep]. */
    fun keep(fd: Fd) = connection.keep(fd)

    // Remembers [resource]; closing what comes back closes it and forgets it.
    private fun own(resource: AutoCloseable): AutoCloseable {
        if (closed) {
            // (A scope that is closed keeps nothing: what is asked of it ends at once.)
            log.warn("A D-Bus object or listener was asked of a configuration that was replaced; it is not kept")
            resource.close()
            return AutoCloseable {}
        }
        owned += resource
        return AutoCloseable {
            owned -= resource
            resource.close()
        }
    }

    /** Takes away everything exported and listened to through this scope. */
    override fun close() {
        if (closed) return
        closed = true
        if (owned.isNotEmpty()) log.info { "Taking away ${owned.size} D-Bus objects and listeners of a configuration" }
        owned.toList().forEach { runCatching { it.close() } }
        owned.clear()
    }
}
