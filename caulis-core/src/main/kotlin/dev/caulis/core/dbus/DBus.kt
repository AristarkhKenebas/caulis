package dev.caulis.core.dbus

import dev.caulis.log.InternalCaulisApi
import dev.caulis.event.EventLoop
import dev.caulis.ffi.Fd
import dev.caulis.ffi.UnixSocket
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
@InternalCaulisApi
value class ObjectPath(val value: String)

/** A message from the bus: a method's reply or error, or a signal. */
@InternalCaulisApi
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
    /** The values, in [signature]'s order: String, ObjectPath, Boolean, Int, Long, Double, List, Fd (h), ... */
    val body: List<Any?>,
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

/**
 * A small client of D-Bus — the system bus (logind: sleep, brightness, the session's lock) or
 * the session bus — on the compositor's event loop: no thread, no library. Enough of the wire
 * format for what a compositor asks: the basic types, arrays, structs, file descriptors.
 */
@InternalCaulisApi
class DBusConnection private constructor(private val socket: UnixSocket, private val loop: EventLoop) : AutoCloseable {

    private val log = Logger("dbus")
    private var serial = 1
    private val replies = HashMap<Int, (DBusMessage) -> Unit>()
    private val signals = ArrayList<Pair<(DBusMessage) -> Boolean, (DBusMessage) -> Unit>>()
    private val incoming = ByteArrayOutputStream()
    private val incomingFds = ArrayDeque<Fd>()
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
            readAll()
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

    /** Runs [handler] for every signal [member] of [iface] (from [path], if given). Asks the bus for them. */
    fun onSignal(iface: String, member: String, path: String? = null, handler: (DBusMessage) -> Unit) {
        signals += { m: DBusMessage -> m.iface == iface && m.member == member && (path == null || m.path == path) } to handler
        val rule = "type='signal',interface='$iface',member='$member'" + (path?.let { ",path='$it'" } ?: "")
        call("org.freedesktop.DBus", ObjectPath("/org/freedesktop/DBus"), "org.freedesktop.DBus", "AddMatch", "s", listOf(rule)) { reply ->
            if (reply.isError) log.warn("The bus refused to send $iface.$member: ${reply.body.firstOrNull()}")
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
        Arena.ofConfined().use { arena ->
            val data = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.size)
            if (socket.send(data, fds) < 0) log.warn("The bus is not reading: a message was lost")
        }
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
            try {
                dispatch(parse(all.copyOf(length)))
            } catch (e: Exception) {
                log.warn("A message from the bus could not be read: $e")
            }
        }
    }

    private fun dispatch(message: DBusMessage) {
        when (message.type) {
            DBusMessage.REPLY, DBusMessage.ERROR -> replies.remove(message.replySerial)?.let { handler ->
                try {
                    handler(message)
                } catch (e: Exception) {
                    log.error("Handling $message failed", e)
                }
            }
            DBusMessage.SIGNAL -> for ((fits, handler) in signals.toList()) {
                if (!fits(message)) continue
                try {
                    handler(message)
                } catch (e: Exception) {
                    log.error("Handling $message failed", e)
                }
            }
        }
        // Descriptors nobody took (a reply's that was not asked for) are not kept.
        for (value in message.body) if (value is Fd && value !in taken) value.close()
        taken.clear()
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

    private fun parse(data: ByteArray): DBusMessage {
        val order = if (data[0] == 'l'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val reader = Reader(ByteBuffer.wrap(data).order(order), incomingFds)
        reader.byte()
        val type = reader.byte()
        reader.byte()
        reader.byte()
        reader.u32() // body length
        val serial = reader.u32()
        @Suppress("UNCHECKED_CAST")
        val fields = (reader.read("a(yv)") as List<List<Any?>>).associate { (it[0] as Int) to (it[1] as Variant).value }
        reader.align(8)
        val signature = fields[8] as String? ?: ""
        val body = reader.readAll(signature)
        return DBusMessage(
            type, serial, (fields[5] as Int?) ?: 0, (fields[1] as ObjectPath?)?.value, fields[2] as String?, fields[3] as String?,
            fields[4] as String?, fields[7] as String?, signature, body,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        loop.remove(socket.fd)
        socket.close()
        incomingFds.forEach { it.close() }
        incomingFds.clear()
    }

    companion object {
        private const val READ_SIZE = 64 * 1024L
        private const val NO_REPLY_EXPECTED = 1

        /** The system bus (logind, NetworkManager, BlueZ), or null if it cannot be reached. */
        @JvmStatic
        fun system(loop: EventLoop): DBusConnection? =
            connect(loop, System.getenv("DBUS_SYSTEM_BUS_ADDRESS") ?: "unix:path=/run/dbus/system_bus_socket")

        /** The session bus of this user, or null. */
        @JvmStatic
        fun session(loop: EventLoop): DBusConnection? =
            connect(loop, System.getenv("DBUS_SESSION_BUS_ADDRESS") ?: "unix:path=${System.getenv("XDG_RUNTIME_DIR")}/bus")

        private fun connect(loop: EventLoop, address: String): DBusConnection? {
            val log = Logger("dbus")
            val path = address.split(';').firstNotNullOfOrNull { part ->
                part.removePrefix("unix:").split(',').firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
            } ?: return null.also { log.warn("The bus address $address has no socket path") }
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

// A value inside a variant ("v"): its signature and itself.
internal class Variant(val signature: String, val value: Any?)

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
                for (item in value as List<*>) write(element, item)
                val length = out.size() - start
                val all = out.toByteArray()
                for (i in 0 until 4) all[lengthAt + i] = ((length ushr (8 * i)) and 0xff).toByte()
                out.reset()
                out.write(all)
            }
            '(' -> {
                pad(8)
                val inner = splitSignature(type.substring(1, type.length - 1))
                (value as List<*>).zip(inner).forEach { (item, t) -> write(t, item) }
            }
            else -> error("D-Bus type $type is not supported here")
        }
    }
}

// Reads values by their D-Bus signature.
private class Reader(private val data: ByteBuffer, private val fds: ArrayDeque<Fd>) {

    // The message's descriptors, in order (h is an index into them).
    private val messageFds = ArrayList<Fd>()

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
        'h' -> {
            val index = u32()
            while (messageFds.size <= index && fds.isNotEmpty()) messageFds += fds.removeFirst()
            messageFds.getOrNull(index)
        }
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
