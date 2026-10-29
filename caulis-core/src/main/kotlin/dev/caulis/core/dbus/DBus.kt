package dev.caulis.core.dbus

import dev.caulis.event.EventLoop
import dev.caulis.ffi.Fd
import dev.caulis.ffi.UnixSocket
import dev.caulis.core.rethrowIfFatal
import dev.caulis.log.Logger
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** An object path on the bus ("/org/freedesktop/login1"): its own type, as D-Bus has it. */
@JvmInline
value class ObjectPath(val value: String)

/** A message from the bus: a method's reply or error, or a signal. */
class DBusMessage internal constructor(
    val type: Int,
    val serial: Int,
    val replySerial: Int,
    val path: String?,
    val iface: String?,
    val member: String?,
    val errorName: String?,
    val sender: String?,
    val signature: String,
    /**
     * The values, in [signature]'s order: String, ObjectPath, Boolean, Int, Long, Double, List, Fd (h), ...
     * The unsigned kinds come as their signed ones: `u` as Int and `t` as Long (2^31 and more
     * as negative numbers: `toUInt()`/`toULong()` gives them back).
     */
    val body: List<Any?>,
    internal val flags: Int = 0,
) {
    val isError: Boolean get() = type == ERROR

    override fun toString() = "D-Bus ${listOf("?", "call", "reply", "error", "signal")[type.coerceIn(0, 4)]} " +
        "${iface.orEmpty()}.${member.orEmpty()}${errorName?.let { " $it" }.orEmpty()} $body"

    internal companion object {
        const val CALL = 1
        const val REPLY = 2
        const val ERROR = 3
        const val SIGNAL = 4
    }
}

/** A method of an exported object: what it takes and gives ([inSignature], [outSignature]) and what it does. */
class DBusMethod(
    val inSignature: String,
    val outSignature: String,
    /** Gets the call (its [DBusMessage.body]) and returns the answer's values; throws [DBusError] to answer with an error. */
    val handler: (DBusMessage) -> List<Any?>,
)

/** An error answered to a caller ([name] as D-Bus names them, "org.example.Error.Failed"). */
class DBusError(val name: String, message: String) : Exception(message)

/**
 * A small client (and server) of D-Bus — the system bus (logind: sleep, brightness, the session's lock) or
 * the session bus — on the compositor's event loop: no thread, no library. Enough of the wire
 * format for what a compositor asks: the basic types, arrays, structs, file descriptors.
 */
class DBusConnection private constructor(private val socket: UnixSocket, private val loop: EventLoop) : AutoCloseable {

    private val log = Logger("dbus")
    private var serial = 1
    private val replies = HashMap<Int, (DBusMessage) -> Unit>()
    private val signals = ArrayList<Pair<(DBusMessage) -> Boolean, (DBusMessage) -> Unit>>()
    private val incoming = ByteArrayOutputStream()
    private val incomingFds = ArrayDeque<Fd>()

    // Messages (or what is left of one) the bus has not taken yet, and their descriptors.
    private class Outgoing(var bytes: ByteArray, var fds: List<Fd>)

    private val outgoing = ArrayDeque<Outgoing>()
    private val buffer = Arena.ofShared().allocate(READ_SIZE)
    private var closed = false

    /** Our name on the bus (":1.42"). */
    var uniqueName: String? = null
        private set

    init {
        loop.add(socket.fd) { ready ->
            if (ready.hangUp || ready.error) {
                log.warn("The bus closed the connection")
                close()
                return@add
            }
            if (ready.writable) flush()
            if (ready.readable) readAll()
        }
        call("org.freedesktop.DBus", ObjectPath("/org/freedesktop/DBus"), "org.freedesktop.DBus", "Hello") { reply ->
            uniqueName = reply.body.firstOrNull() as? String
        }
    }

    /**
     * Calls [member] of [iface] on [path] at [destination] with [args] (as [signature] says);
     * [reply] gets the answer (or the error) on the loop.
     */
    fun call(
        destination: String, path: ObjectPath, iface: String, member: String,
        signature: String = "", args: List<Any?> = emptyList(), fds: List<Fd> = emptyList(),
        reply: ((DBusMessage) -> Unit)? = null,
    ) {
        if (closed) return
        val number = serial++
        if (reply != null) replies[number] = reply
        val fields = buildList {
            add(1 to Variant("o", path))
            add(2 to Variant("s", iface))
            add(3 to Variant("s", member))
            add(6 to Variant("s", destination))
            if (signature.isNotEmpty()) add(8 to Variant("g", signature))
            if (fds.isNotEmpty()) add(9 to Variant("u", fds.size))
        }
        send(DBusMessage.CALL, if (reply == null) NO_REPLY_EXPECTED else 0, number, fields, signature, args, fds)
    }

    // Our objects: path -> interface -> member -> method.
    private val exported = HashMap<String, MutableMap<String, Map<String, DBusMethod>>>()

    /**
     * Asks the bus for the well-known [name] ("org.example.Service"); [done] hears whether we
     * have it now (false: another program has it).
     */
    fun requestName(name: String, done: ((Boolean) -> Unit)? = null) {
        call("org.freedesktop.DBus", ObjectPath("/org/freedesktop/DBus"), "org.freedesktop.DBus", "RequestName", "su", listOf(name, DO_NOT_QUEUE)) { reply ->
            val ours = !reply.isError && (reply.body.firstOrNull() as? Int).let { it == PRIMARY_OWNER || it == ALREADY_OWNER }
            if (ours) log.info { "Bus name $name is ours" } else log.warn("Bus name $name is taken by another program")
            done?.invoke(ours)
        }
    }

    /**
     * Answers calls of [iface] on [path] with [methods] (by name) from now on; close the result
     * to stop. Introspection (busctl, gdbus, d-feet) and Ping are answered by themselves.
     */
    fun export(path: String, iface: String, methods: Map<String, DBusMethod>): AutoCloseable {
        exported.getOrPut(path) { LinkedHashMap() }[iface] = methods
        log.trace { "Exported $iface at $path" }
        return AutoCloseable {
            // (Only if it is still this one: an export of the same again since stays.)
            if (exported[path]?.get(iface) !== methods) return@AutoCloseable
            exported[path]?.remove(iface)
            if (exported[path]?.isEmpty() == true) exported -= path
            log.trace { "No longer exported: $iface at $path" }
        }
    }

    /** Sends the signal [member] of [iface] from [path] with [args] (as [signature] says) to whoever listens. */
    fun emitSignal(path: String, iface: String, member: String, signature: String = "", args: List<Any?> = emptyList()) {
        if (closed) return
        val fields = buildList {
            add(1 to Variant("o", ObjectPath(path)))
            add(2 to Variant("s", iface))
            add(3 to Variant("s", member))
            if (signature.isNotEmpty()) add(8 to Variant("g", signature))
        }
        send(DBusMessage.SIGNAL, NO_REPLY_EXPECTED, serial++, fields, signature, args, emptyList())
    }

    // A call to one of our objects: answered (a reply or an error), unless no answer is wanted.
    private fun answer(call: DBusMessage) {
        fun reply(signature: String, values: List<Any?>) {
            if (call.flags and NO_REPLY_EXPECTED != 0) return
            val fields = buildList {
                add(5 to Variant("u", call.serial))
                call.sender?.let { add(6 to Variant("s", it)) }
                if (signature.isNotEmpty()) add(8 to Variant("g", signature))
            }
            send(DBusMessage.REPLY, NO_REPLY_EXPECTED, serial++, fields, signature, values, emptyList())
        }
        fun error(name: String, text: String) {
            if (call.flags and NO_REPLY_EXPECTED != 0) return
            val fields = buildList {
                add(4 to Variant("s", name))
                add(5 to Variant("u", call.serial))
                call.sender?.let { add(6 to Variant("s", it)) }
                add(8 to Variant("g", "s"))
            }
            send(DBusMessage.ERROR, NO_REPLY_EXPECTED, serial++, fields, "s", listOf(text), emptyList())
        }
        val path = call.path ?: return error(UNKNOWN_OBJECT, "no path")
        val member = call.member ?: return error(UNKNOWN_METHOD, "no member")
        when {
            call.iface == "org.freedesktop.DBus.Peer" && member == "Ping" -> return reply("", emptyList())
            call.iface == "org.freedesktop.DBus.Introspectable" && member == "Introspect" -> return reply("s", listOf(introspect(path)))
        }
        val interfaces = exported[path] ?: return error(UNKNOWN_OBJECT, "no object $path")
        val method = if (call.iface != null) interfaces[call.iface]?.get(member) else interfaces.values.firstNotNullOfOrNull { it[member] }
        if (method == null) return error(UNKNOWN_METHOD, "no method ${call.iface ?: "?"}.$member on $path")
        if (call.signature != method.inSignature) return error(INVALID_ARGS, "$member takes (${method.inSignature}), not (${call.signature})")
        try {
            reply(method.outSignature, method.handler(call))
        } catch (e: DBusError) {
            error(e.name, e.message.orEmpty())
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            log.error("Answering $call failed", e)
            error(FAILED, e.toString())
        }
    }

    // What the object at [path] has, as introspection XML (its children too, so tools find it).
    private fun introspect(path: String): String = buildString {
        append("<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\" \"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">\n<node>\n")
        exported[path]?.forEach { (iface, methods) ->
            append("  <interface name=\"$iface\">\n")
            for ((name, m) in methods) {
                append("    <method name=\"$name\">")
                for (t in splitSignature(m.inSignature)) append("<arg direction=\"in\" type=\"$t\"/>")
                for (t in splitSignature(m.outSignature)) append("<arg direction=\"out\" type=\"$t\"/>")
                append("</method>\n")
            }
            append("  </interface>\n")
        }
        val prefix = if (path == "/") "/" else "$path/"
        exported.keys.filter { it.startsWith(prefix) && it != path }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
            .forEach { append("  <node name=\"$it\"/>\n") }
        append("</node>\n")
    }

    // Caulis 0.1.1's onSignal (returning nothing): code built against it calls it.
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    @JvmName("onSignal")
    fun onSignalWithoutAnswer(iface: String, member: String, path: String? = null, handler: (DBusMessage) -> Unit) {
        onSignal(iface, member, path, null, handler)
    }

    /**
     * Runs [handler] for every signal [member] of [iface] (from [path], if given). Asks the bus
     * for them; close the result to stop (the bus is told it need not send them any more).
     *
     * [sender]: only signals from this name — a unique one (":1.42") or a well-known one
     * ("org.freedesktop.login1": whoever owns it now; followed when the owner changes). Give it
     * whenever the signal matters: on the system bus any program may send a signal with any
     * interface and path (a forged PrepareForSleep). Null: from anyone.
     */
    @JvmOverloads
    fun onSignal(iface: String, member: String, path: String? = null, sender: String? = null, handler: (DBusMessage) -> Unit): AutoCloseable {
        // Who owns [sender] now (its unique name), when it is a well-known name; null: no one yet.
        // (The bus itself sends as "org.freedesktop.DBus", which no one else may own.)
        var owner: String? = sender?.takeIf { it.startsWith(":") || it == BUS }
        // Until the bus said who owns [sender], its signals wait here (a few at most).
        var waiting: ArrayList<DBusMessage>? = null
        val follow = ArrayList<AutoCloseable>()
        if (sender != null && owner == null) {
            waiting = ArrayList()
            // Told of new owners first, then asked for the owner now: the bus answers in order,
            // so no change of owner falls in between.
            follow += onSignal("org.freedesktop.DBus", "NameOwnerChanged", "/org/freedesktop/DBus", BUS) { m ->
                if (m.body.getOrNull(0) == sender) {
                    owner = (m.body.getOrNull(2) as String?)?.takeIf { it.isNotEmpty() }
                    log.trace { "$sender is now owned by ${owner ?: "no one"}" }
                }
            }
            call("org.freedesktop.DBus", ObjectPath("/org/freedesktop/DBus"), "org.freedesktop.DBus", "GetNameOwner", "s", listOf(sender)) { reply ->
                if (reply.isError) log.trace { "$sender has no owner on the bus yet" } else owner = reply.body.firstOrNull() as String?
                val early = waiting.orEmpty()
                waiting = null
                for (m in early) if (m.sender != null && m.sender == owner) handler(m)
            }
        }
        val listener: Pair<(DBusMessage) -> Boolean, (DBusMessage) -> Unit> = { m: DBusMessage ->
            m.iface == iface && m.member == member && (path == null || m.path == path) &&
                (sender == null || run {
                    val early = waiting
                    if (early != null) {
                        // (Who sent it is not known yet: kept until the bus says.)
                        if (early.size < EARLY_SIGNALS) early += m
                        false
                    } else {
                        (m.sender != null && m.sender == owner).also { fits ->
                            // (Not a warning: another listener may want it — a signal for everyone.)
                            if (!fits) log.trace { "Ignored a signal $iface.$member from ${m.sender}: it is not from $sender" }
                        }
                    }
                })
        } to handler
        signals += listener
        val rule = "type='signal',interface='$iface',member='$member'" + (path?.let { ",path='$it'" } ?: "") + (sender?.let { ",sender='$it'" } ?: "")
        call("org.freedesktop.DBus", ObjectPath("/org/freedesktop/DBus"), "org.freedesktop.DBus", "AddMatch", "s", listOf(rule)) { reply ->
            if (reply.isError) log.warn("The bus refused to send $iface.$member: ${reply.body.firstOrNull()}")
        }
        var stopped = false
        return AutoCloseable {
            if (stopped) return@AutoCloseable
            stopped = true
            signals.removeIf { it === listener }
            follow.forEach(AutoCloseable::close)
            log.trace { "No longer listening to $iface.$member" }
            // (The bus counts each AddMatch: one RemoveMatch takes this one away, others stay.)
            call("org.freedesktop.DBus", ObjectPath("/org/freedesktop/DBus"), "org.freedesktop.DBus", "RemoveMatch", "s", listOf(rule))
        }
    }

    private fun send(type: Int, flags: Int, number: Int, fields: List<Pair<Int, Variant>>, signature: String, args: List<Any?>, fds: List<Fd>) {
        val body = Writer()
        body.writeAll(signature, args)
        val header = Writer()
        header.byte('l'.code)
        header.byte(type)
        header.byte(flags)
        header.byte(1)
        header.u32(body.size)
        header.u32(number)
        header.write("a(yv)", fields.map { (code, value) -> listOf(code, value) })
        header.pad(8)
        val bytes = header.bytes() + body.bytes()
        // (Sent in order: after what is still waiting; its descriptors copied, the caller keeps theirs.)
        outgoing.addLast(Outgoing(bytes, fds.map { it.duplicate() }))
        flush()
    }

    // Sends what waits, as far as the bus takes it now; the rest when it can take more.
    private fun flush() {
        while (!closed) {
            val next = outgoing.firstOrNull() ?: break
            val sent = Arena.ofConfined().use { arena ->
                val data = arena.allocate(next.bytes.size.toLong())
                MemorySegment.copy(next.bytes, 0, data, JAVA_BYTE, 0, next.bytes.size)
                try {
                    socket.send(data, next.fds)
                } catch (e: Exception) {
                    log.warn("Writing to the bus failed: $e")
                    close()
                    return
                }
            }
            if (sent < 0) break // full for now
            next.fds.forEach { it.close() } // (they went with the first byte)
            next.fds = emptyList()
            if (sent < next.bytes.size) {
                next.bytes = next.bytes.copyOfRange(sent.toInt(), next.bytes.size)
                break
            }
            outgoing.removeFirst()
        }
        if (!closed) loop.update(socket.fd, read = true, write = outgoing.isNotEmpty())
    }

    private fun readAll() {
        while (!closed) {
            val received = ArrayList<Fd>()
            val count = try {
                socket.receive(buffer, received)
            } catch (e: Exception) {
                log.warn("Reading from the bus failed: $e")
                close()
                return
            }
            incomingFds += received
            if (count < 0) break
            if (count == 0L) {
                close()
                return
            }
            incoming.write(buffer.asSlice(0, count).toArray(JAVA_BYTE))
        }
        while (true) {
            val all = incoming.toByteArray()
            val length = messageLength(all) ?: break
            if (all.size < length) break
            incoming.reset()
            incoming.write(all, length, all.size - length)
            // The descriptors that came with this message (its header says how many).
            val fds = ArrayList<Fd>()
            try {
                repeat(fdCount(all)) { incomingFds.removeFirstOrNull()?.let(fds::add) }
                dispatch(parse(all.copyOf(length), fds))
            } catch (e: Throwable) {
                e.rethrowIfFatal()
                log.warn("A message from the bus could not be read: $e")
            } finally {
                // Descriptors nobody took (a reply's that was not asked for) are not kept.
                for (fd in fds) if (fd !in taken) fd.close()
                taken.clear()
            }
        }
    }

    private fun dispatch(message: DBusMessage) {
        when (message.type) {
            DBusMessage.REPLY, DBusMessage.ERROR -> replies.remove(message.replySerial)?.let { handler ->
                try {
                    handler(message)
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    log.error("Handling $message failed", e)
                }
            }
            DBusMessage.CALL -> answer(message)
            DBusMessage.SIGNAL -> for ((fits, handler) in signals.toList()) {
                if (!fits(message)) continue
                try {
                    handler(message)
                } catch (e: Throwable) {
                    e.rethrowIfFatal()
                    log.error("Handling $message failed", e)
                }
            }
        }
    }

    // Descriptors a handler keeps (see keep).
    private val taken = HashSet<Fd>()

    /** In a handler: [fd] from the message is yours now (else it is closed after the handler). */
    fun keep(fd: Fd) {
        taken += fd
    }

    // The whole length of the message at the start of [data], or null if its header is not all there.
    private fun messageLength(data: ByteArray): Int? {
        if (data.size < 16) return null
        val order = if (data[0] == 'l'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val b = ByteBuffer.wrap(data).order(order)
        val bodyLength = b.getInt(4)
        val fieldsLength = b.getInt(12)
        val headerLength = 16 + fieldsLength
        return ((headerLength + 7) and 7.inv()) + bodyLength
    }

    // How many descriptors came with the message at the start of [data] (its UNIX_FDS field).
    private fun fdCount(data: ByteArray): Int {
        val order = if (data[0] == 'l'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val reader = Reader(ByteBuffer.wrap(data).order(order), emptyList())
        reader.position(12)
        @Suppress("UNCHECKED_CAST")
        val fields = reader.read("a(yv)") as List<List<Any?>>
        return fields.firstOrNull { it[0] == 9 }?.let { ((it[1] as Variant).value as Int).coerceIn(0, 253) } ?: 0
    }

    private fun parse(data: ByteArray, fds: List<Fd>): DBusMessage {
        val order = if (data[0] == 'l'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val reader = Reader(ByteBuffer.wrap(data).order(order), fds)
        reader.byte()
        val type = reader.byte()
        reader.byte()
        reader.byte()
        reader.u32() // body length
        val serial = reader.u32()
        val flags = data[2].toInt() and 0xff
        @Suppress("UNCHECKED_CAST")
        val fields = (reader.read("a(yv)") as List<List<Any?>>).associate { (it[0] as Int) to (it[1] as Variant).value }
        reader.align(8)
        val signature = fields[8] as String? ?: ""
        val body = reader.readAll(signature)
        return DBusMessage(
            type, serial, (fields[5] as Int?) ?: 0, (fields[1] as ObjectPath?)?.value, fields[2] as String?, fields[3] as String?,
            fields[4] as String?, fields[7] as String?, signature, body, flags,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        loop.remove(socket.fd)
        socket.close()
        incomingFds.forEach { it.close() }
        outgoing.forEach { message -> message.fds.forEach { it.close() } }
        outgoing.clear()
        incomingFds.clear()
    }

    companion object {
        private const val READ_SIZE = 64 * 1024L
        private const val BUS = "org.freedesktop.DBus"
        // Signals from a well-known name kept until the bus says who owns it (more are dropped).
        private const val EARLY_SIGNALS = 64
        private const val NO_REPLY_EXPECTED = 1
        private const val DO_NOT_QUEUE = 4
        private const val PRIMARY_OWNER = 1
        private const val ALREADY_OWNER = 4
        private const val UNKNOWN_OBJECT = "org.freedesktop.DBus.Error.UnknownObject"
        private const val UNKNOWN_METHOD = "org.freedesktop.DBus.Error.UnknownMethod"
        private const val INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs"
        private const val FAILED = "org.freedesktop.DBus.Error.Failed"

        /** The system bus (logind, NetworkManager, BlueZ), or null if it cannot be reached. */
        @JvmStatic
        fun system(loop: EventLoop): DBusConnection? =
            connect(loop, System.getenv("DBUS_SYSTEM_BUS_ADDRESS") ?: "unix:path=/run/dbus/system_bus_socket")

        /** The session bus of this user, or null. */
        @JvmStatic
        fun session(loop: EventLoop): DBusConnection? {
            val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")?.takeIf { it.isNotBlank() }
                ?: System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.startsWith("/") }?.let { "unix:path=$it/bus" }
                ?: return null.also { Logger("dbus").info { "No session bus (neither DBUS_SESSION_BUS_ADDRESS nor XDG_RUNTIME_DIR is set)" } }
            return connect(loop, address)
        }

        /** The bus at [address] ("unix:path=/tmp/x/bus": a bus of your own, a test's private one), or null. */
        @JvmStatic
        fun open(loop: EventLoop, address: String): DBusConnection? = connect(loop, address)

        private fun connect(loop: EventLoop, address: String): DBusConnection? {
            val log = Logger("dbus")
            // (Values in an address may have %xx in them: "/run/user/1000/my%20bus".)
            val path = address.split(';').firstNotNullOfOrNull { part ->
                part.removePrefix("unix:").split(',').firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
            }?.let(::unescape) ?: return null.also { log.warn("The bus address $address has no socket path (only unix:path= is understood)") }
            if (!Files.exists(Path.of(path))) return null.also { log.info { "No bus at $path" } }
            val socket = try {
                UnixSocket.connect(Path.of(path))
            } catch (e: Exception) {
                return null.also { log.warn("The bus at $path cannot be reached: $e") }
            }
            return try {
                authenticate(socket)
                DBusConnection(socket, loop)
            } catch (e: Exception) {
                socket.close()
                log.warn("The bus at $path did not let us in: $e")
                null
            }
        }

        // A D-Bus address value with its %xx escapes undone.
        private fun unescape(value: String): String {
            if ('%' !in value) return value
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 < value.length) {
                    value.substring(i + 1, i + 3).toIntOrNull(16)?.let { bytes.write(it); i += 3; continue }
                }
                bytes.write(c.toString().toByteArray())
                i++
            }
            return bytes.toString(Charsets.UTF_8)
        }

        // EXTERNAL authentication (the kernel tells the bus who we are), with descriptor passing.
        private fun authenticate(socket: UnixSocket) {
            val uid = readUid()
            val hex = uid.toString().toByteArray().joinToString("") { "%02x".format(it) }
            line(socket, "\u0000AUTH EXTERNAL $hex\r\n")
            val ok = answer(socket)
            check(ok.startsWith("OK")) { "AUTH: $ok" }
            line(socket, "NEGOTIATE_UNIX_FD\r\n")
            val fds = answer(socket)
            check(fds.startsWith("AGREE_UNIX_FD")) { "no descriptor passing: $fds" }
            line(socket, "BEGIN\r\n")
        }

        private fun readUid(): Int =
            Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()

        private fun line(socket: UnixSocket, text: String) = Arena.ofConfined().use { arena ->
            val bytes = text.toByteArray()
            val data = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.size)
            check(socket.send(data) == bytes.size.toLong()) { "could not write to the bus" }
        }

        // One line from the bus while authenticating (it answers at once; at most a second).
        private fun answer(socket: UnixSocket): String = Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(512)
            val text = StringBuilder()
            val until = System.nanoTime() + 1_000_000_000
            while (!text.endsWith("\r\n")) {
                check(System.nanoTime() < until) { "the bus did not answer" }
                val count = socket.receive(buffer.asSlice(0, 1), ArrayList())
                when {
                    count < 0 -> Thread.sleep(2)
                    count == 0L -> error("the bus closed the connection")
                    else -> text.append(buffer.get(JAVA_BYTE, 0).toInt().toChar())
                }
            }
            text.toString().trim()
        }
    }
}

/** A value inside a variant ("v"): its [signature] and itself (`Variant("i", 40)` in an `a{sv}`). */
class Variant(val signature: String, val value: Any?) {
    override fun toString() = "Variant($signature, $value)"
}

// Writes values by their D-Bus signature (little-endian), aligned as D-Bus wants.
private class Writer {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()

    fun bytes(): ByteArray = out.toByteArray()

    fun pad(to: Int) {
        while (out.size() % to != 0) out.write(0)
    }

    fun byte(value: Int) = out.write(value and 0xff)

    fun u32(value: Int) {
        pad(4)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xff)
    }

    private fun u64(value: Long) {
        pad(8)
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xff).toInt())
    }

    private fun string(value: String) {
        require('\u0000' !in value) { "a D-Bus string cannot hold a zero character" }
        val bytes = value.toByteArray()
        u32(bytes.size)
        out.write(bytes)
        out.write(0)
    }

    private fun signature(value: String) {
        val bytes = value.toByteArray()
        byte(bytes.size)
        out.write(bytes)
        out.write(0)
    }

    fun writeAll(signature: String, values: List<Any?>) {
        val types = splitSignature(signature)
        require(types.size == values.size) { "signature $signature has ${types.size} values, not ${values.size}" }
        types.zip(values).forEach { (type, value) -> write(type, value) }
    }

    fun write(type: String, value: Any?) {
        when (type[0]) {
            'y' -> byte((value as Number).toInt())
            'b' -> u32(if (value as Boolean) 1 else 0)
            'n', 'q' -> {
                pad(2)
                val v = (value as Number).toInt()
                out.write(v and 0xff)
                out.write((v ushr 8) and 0xff)
            }
            'i', 'u' -> u32((value as Number).toInt())
            'h' -> u32((value as Number).toInt()) // (an index into the message's descriptors)
            'x', 't' -> u64((value as Number).toLong())
            'd' -> u64(java.lang.Double.doubleToLongBits((value as Number).toDouble()))
            's' -> string(value as String)
            'o' -> string((value as ObjectPath).value)
            'g' -> signature(value as String)
            'v' -> {
                val variant = value as Variant
                signature(variant.signature)
                write(variant.signature, variant.value)
            }
            'a' -> {
                val element = type.substring(1)
                pad(4)
                val lengthAt = out.size()
                u32(0)
                pad(alignment(element[0]))
                val start = out.size()
                // A dict ("a{sv}") from a Map too.
                val items = if (element[0] == '{' && value is Map<*, *>) value.entries.toList() else value as List<*>
                for (item in items) write(element, item)
                val length = out.size() - start
                val all = out.toByteArray()
                for (i in 0 until 4) all[lengthAt + i] = ((length ushr (8 * i)) and 0xff).toByte()
                out.reset()
                out.write(all)
            }
            '(' -> {
                pad(8)
                val inner = splitSignature(type.substring(1, type.length - 1))
                val items = value as List<*>
                require(items.size == inner.size) { "the struct $type has ${inner.size} values, not ${items.size}" }
                items.zip(inner).forEach { (item, t) -> write(t, item) }
            }
            '{' -> {
                pad(8)
                val (keyType, valueType) = splitSignature(type.substring(1, type.length - 1))
                val (k, v) = when (value) {
                    is Map.Entry<*, *> -> value.key to value.value
                    is Pair<*, *> -> value.first to value.second
                    is List<*> -> value[0] to value[1]
                    else -> error("a dict entry is a Map entry, a Pair or a list of two, not $value")
                }
                write(keyType, k)
                write(valueType, v)
            }
            else -> error("D-Bus type $type is not supported here")
        }
    }
}

// Reads values by their D-Bus signature.
private class Reader(private val data: ByteBuffer, private val messageFds: List<Fd>) {

    fun position(at: Int) {
        data.position(at)
    }

    fun align(to: Int) {
        val at = data.position()
        val aligned = (at + to - 1) / to * to
        data.position(aligned)
    }

    fun byte(): Int = data.get().toInt() and 0xff

    fun u32(): Int {
        align(4)
        return data.getInt()
    }

    fun readAll(signature: String): List<Any?> = splitSignature(signature).map { read(it) }

    fun read(type: String): Any? = when (type[0]) {
        'y' -> byte()
        'b' -> u32() != 0
        'n' -> { align(2); data.getShort().toInt() }
        'q' -> { align(2); data.getShort().toInt() and 0xffff }
        'i', 'u' -> u32()
        'h' -> messageFds.getOrNull(u32()) // (an index into the message's descriptors)
        'x', 't' -> { align(8); data.getLong() }
        'd' -> { align(8); data.getDouble() }
        's' -> string()
        'o' -> ObjectPath(string())
        'g' -> signature()
        'v' -> {
            val signature = signature()
            Variant(signature, read(signature))
        }
        'a' -> {
            val element = type.substring(1)
            val length = u32()
            align(alignment(element[0]))
            val end = data.position() + length
            buildList { while (data.position() < end) add(read(element)) }
        }
        '(', '{' -> {
            align(8)
            splitSignature(type.substring(1, type.length - 1)).map { read(it) }
        }
        else -> error("D-Bus type $type is not supported here")
    }

    private fun string(): String {
        val length = u32()
        val bytes = ByteArray(length)
        data.get(bytes)
        data.get() // the zero
        return String(bytes)
    }

    private fun signature(): String {
        val length = byte()
        val bytes = ByteArray(length)
        data.get(bytes)
        data.get()
        return String(bytes)
    }
}

// How a value of [type] is aligned.
private fun alignment(type: Char): Int = when (type) {
    'y', 'g', 'v' -> 1
    'n', 'q' -> 2
    'b', 'i', 'u', 'h', 's', 'o', 'a' -> 4
    else -> 8 // x t d ( {
}

// "sa{sv}(ii)" -> ["s", "a{sv}", "(ii)"].
private fun splitSignature(signature: String): List<String> {
    val types = ArrayList<String>()
    var i = 0
    fun one(): String {
        val start = i
        when (signature[i]) {
            'a' -> {
                i++
                one()
            }
            '(', '{' -> {
                var depth = 0
                do {
                    when (signature[i]) {
                        '(', '{' -> depth++
                        ')', '}' -> depth--
                    }
                    i++
                } while (depth > 0)
            }
            else -> i++
        }
        return signature.substring(start, i)
    }
    while (i < signature.length) types += one()
    return types
}
