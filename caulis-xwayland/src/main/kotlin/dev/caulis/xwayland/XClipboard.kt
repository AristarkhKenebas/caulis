package dev.caulis.xwayland

import dev.caulis.core.ClipboardData
import dev.caulis.core.DataDeviceManager
import dev.caulis.event.EventLoop
import dev.caulis.ffi.Errno
import dev.caulis.ffi.Fd
import dev.caulis.ffi.NativeException
import dev.caulis.ffi.c.xcb.Xcb
import dev.caulis.ffi.c.xcb.xcb_property_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_selection_clear_event_t
import dev.caulis.ffi.c.xcb.xcb_selection_notify_event_t
import dev.caulis.ffi.c.xcb.xcb_selection_request_event_t
import dev.caulis.ffi.c.xcb.xcb_xfixes_selection_notify_event_t
import dev.caulis.log.Logger
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE

/**
 * The clipboard between X11 and Wayland programs: copy in one, paste in the other.
 *
 * X11 → Wayland: XFixes tells us when an X program takes CLIPBOARD; we ask it for its
 * formats (TARGETS) and put an entry on the Wayland clipboard that, when pasted, asks
 * the X program for the data and writes it into the pasting program's pipe.
 *
 * Wayland → X11: when a Wayland program copies, our helper window takes CLIPBOARD; X
 * programs asking for it get the formats or the data, read from the copying program.
 *
 * Large data goes in pieces (ICCCM's INCR) both ways. Text formats are matched up
 * (UTF8_STRING, STRING, TEXT ↔ text/plain); other formats keep their MIME names.
 */
internal class XClipboard(
    private val x: XConnection,
    private val loop: EventLoop,
    private val clipboard: DataDeviceManager?,
    // Windows whose property changes we hear anyway (managed windows: we must not change their event mask).
    private val hearsPropertiesOf: (window: Int) -> Boolean,
) : AutoCloseable {

    private val log = Logger("clipboard")

    // Our window: it owns CLIPBOARD for Wayland programs and receives X programs' data.
    private val window = x.createHelperWindow()

    private val selection = x.atom("CLIPBOARD")
    private val targets = x.atom("TARGETS")
    private val timestamp = x.atom("TIMESTAMP")
    private val incr = x.atom("INCR")
    private val utf8 = x.atom("UTF8_STRING")
    private val text = x.atom("TEXT")
    private val string = Xcb.XCB_ATOM_STRING()
    private val targetsProperty = x.atom("_CAULIS_TARGETS")
    private val dataProperty = x.atom("_CAULIS_CLIPBOARD")

    private var listening: AutoCloseable? = null

    /** True for the helper windows the clipboard uses (they are not program windows). */
    fun owns(id: Int): Boolean = id == window

    init {
        x.changeAttributes(window, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE()))
        if (clipboard != null) {
            x.watchSelection(window, selection)
            listening = clipboard.onClipboardChanged(::waylandCopied)
            clipboard.clipboard?.let(::waylandCopied)
        }
    }

    fun handle(type: Int, event: MemorySegment) {
        if (clipboard == null) return
        when (type) {
            Xcb.XCB_SELECTION_REQUEST() -> requested(event)
            Xcb.XCB_SELECTION_NOTIFY() -> answered(event)
            Xcb.XCB_SELECTION_CLEAR() -> if (xcb_selection_clear_event_t.owner(event) == window) offered = null // another X program copied
            else -> ownerChanged(event) // XFixes
        }
    }

    /** A property changed: true if it is one of a transfer (and nobody else needs to hear it). */
    fun propertyChanged(event: MemorySegment): Boolean {
        val window = xcb_property_notify_event_t.window(event)
        val atom = xcb_property_notify_event_t.atom(event)
        val deleted = xcb_property_notify_event_t.state(event).toInt() == Xcb.XCB_PROPERTY_DELETE()
        if (window == this.window) {
            if (atom == dataProperty && !deleted) receiving?.takeIf { it.incremental }?.let(::nextPiece)
            return true
        }
        val transfer = sending.firstOrNull { it.requestor == window && it.property == atom } ?: return false
        if (deleted) sendPiece(transfer)
        return true
    }

    // ==== Wayland → X11 ======================================================================

    // What a Wayland program copied, while our window owns CLIPBOARD for it, and since when (X time).
    private var offered: ClipboardData? = null
    private var ownedSince = 0
    private var closed = false

    private fun waylandCopied(data: ClipboardData?) {
        if (data is FromX) return // it came from X: its owner is an X program
        if (data == null) {
            if (offered != null) {
                offered = null
                x.setSelectionOwner(XConnection.NONE, selection)
                x.flush()
            }
            return
        }
        offered = data
        x.setSelectionOwner(window, selection)
        x.flush()
        log.trace { "X11 programs can paste $data" }
    }

    // A piece-by-piece answer to an X program, as it reads (and deletes) each piece.
    private class Sending(val requestor: Int, val property: Int, val target: Int, val bytes: ByteArray) {
        var offset = 0
        var done = false
        var timeout: EventLoop.Timer? = null
    }

    private val sending = ArrayList<Sending>()

    private fun requested(event: MemorySegment) {
        val requestor = xcb_selection_request_event_t.requestor(event)
        val target = xcb_selection_request_event_t.target(event)
        val time = xcb_selection_request_event_t.time(event)
        // Old programs give no property: the answer then goes into the one named like the target.
        val property = xcb_selection_request_event_t.property(event).takeIf { it != XConnection.NONE } ?: target
        val data = offered
        fun answer(ok: Boolean) {
            x.sendSelectionNotify(requestor, selection, target, if (ok) property else XConnection.NONE, time)
            x.flush()
        }
        if (xcb_selection_request_event_t.selection(event) != selection || data == null) return answer(false)
        when (target) {
            targets -> {
                val atoms = (listOf(targets, timestamp) + data.mimeTypes.flatMap(::atomsFor)).distinct()
                x.setProperty(requestor, property, Xcb.XCB_ATOM_ATOM(), atoms.toIntArray())
                answer(true)
            }
            timestamp -> {
                x.setProperty(requestor, property, Xcb.XCB_ATOM_INTEGER(), intArrayOf(ownedSince))
                answer(true)
            }
            else -> {
                val mime = mimeFor(target, data.mimeTypes) ?: return answer(false)
                log.trace { "An X11 program pastes $mime" }
                readFromWayland(data, mime) { bytes ->
                    if (bytes == null || offered !== data) return@readFromWayland answer(false)
                    if (bytes.size <= MAX_PROPERTY) {
                        x.setBytes(requestor, property, target, bytes)
                    } else {
                        // Too big for one request: say how big, then hand it over in pieces.
                        if (!hearsPropertiesOf(requestor)) {
                            x.changeAttributes(requestor, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE()))
                        }
                        // A new request replaces an unfinished one into the same place.
                        sending.filter { it.requestor == requestor && it.property == property }.forEach(::dropSending)
                        val transfer = Sending(requestor, property, target, bytes)
                        sending += transfer
                        stillSending(transfer)
                        x.setProperty(requestor, property, incr, intArrayOf(bytes.size))
                    }
                    answer(true)
                }
            }
        }
    }

    private fun sendPiece(transfer: Sending) {
        if (transfer.done) {
            dropSending(transfer)
            return
        }
        stillSending(transfer)
        val length = minOf(PIECE, transfer.bytes.size - transfer.offset)
        x.setBytes(transfer.requestor, transfer.property, transfer.target, transfer.bytes, transfer.offset, length)
        transfer.offset += length
        transfer.done = length == 0 // an empty piece says "that was all"
        x.flush()
    }

    // A program that stops taking the pieces is given up on (the data is not kept forever).
    private fun stillSending(transfer: Sending) {
        transfer.timeout?.cancel()
        transfer.timeout = loop.after(TIMEOUT_MILLIS) {
            log.trace { "An X11 program stopped taking the clipboard" }
            dropSending(transfer)
        }
    }

    private fun dropSending(transfer: Sending) {
        transfer.timeout?.cancel()
        sending -= transfer
    }

    /** An X window was destroyed: what was being handed to it is dropped. */
    fun windowGone(id: Int) {
        sending.filter { it.requestor == id }.forEach(::dropSending)
    }

    // Reads what the Wayland program sends as [mime] through a pipe (without blocking), then calls [done].
    private fun readFromWayland(data: ClipboardData, mime: String, done: (ByteArray?) -> Unit) {
        val (readEnd, writeEnd) = Fd.pipe()
        readEnd.setNonBlocking()
        data.send(mime, writeEnd)
        val bytes = ByteArrayOutputStream()
        var finished = false
        fun finish(result: ByteArray?) {
            if (finished) return
            finished = true
            loop.remove(readEnd)
            readEnd.close()
            done(result)
        }
        val timeout = loop.after(TIMEOUT_MILLIS) {
            log.warn("The copying program did not send its clipboard in time")
            finish(null)
        }
        loop.add(readEnd) {
            val buffer = Arena.ofConfined()
            try {
                val chunk = buffer.allocate(PIECE.toLong())
                while (true) {
                    val n = try {
                        readEnd.read(chunk)
                    } catch (e: NativeException) {
                        if (e.errno == Errno.EAGAIN) break
                        if (e.errno == Errno.EINTR) continue
                        throw e
                    }
                    if (n == 0L) {
                        timeout.cancel()
                        finish(bytes.toByteArray())
                        break
                    }
                    bytes.write(chunk.asSlice(0, n).toArray(JAVA_BYTE))
                    if (bytes.size() > MAX_SIZE) {
                        timeout.cancel()
                        log.warn("The clipboard is too large for X11 programs (over ${MAX_SIZE / 1_000_000} MB)")
                        finish(null)
                        break
                    }
                }
            } catch (e: Exception) {
                timeout.cancel()
                log.warn("Reading the clipboard failed", e)
                finish(null)
            } finally {
                buffer.close()
            }
        }
    }

    // ==== X11 → Wayland ======================================================================

    /** On the Wayland clipboard: what an X program copied (the data is asked for when pasted). */
    private inner class FromX(override val mimeTypes: List<String>, val atomOf: Map<String, Int>) : ClipboardData {
        override fun send(mimeType: String, fd: Fd) {
            val target = atomOf[mimeType]
            if (target == null || closed) {
                fd.close()
                return
            }
            queue.addLast(Receiving(target, fd))
            if (receiving == null) receiveNext()
        }

        override fun toString() = "the clipboard of an X11 program"
    }

    private fun ownerChanged(event: MemorySegment) {
        if (xcb_xfixes_selection_notify_event_t.selection(event) != selection) return
        val owner = xcb_xfixes_selection_notify_event_t.owner(event)
        if (owner == window) {
            ownedSince = xcb_xfixes_selection_notify_event_t.selection_timestamp(event) // that is us, for a Wayland program
            return
        }
        if (owner == XConnection.NONE) {
            // The X program that had it quit: its clipboard is gone.
            if (clipboard?.clipboard is FromX) clipboard.setClipboard(null)
            return
        }
        // An X program copied: what can it give?
        x.convertSelection(window, selection, targets, targetsProperty, xcb_xfixes_selection_notify_event_t.selection_timestamp(event))
        x.flush()
    }

    // A paste by a Wayland program, served by the X owner; one at a time (they share our property).
    private class Receiving(val target: Int, val fd: Fd) {
        val bytes = ByteArrayOutputStream()
        var incremental = false
        var timeout: EventLoop.Timer? = null
    }

    private val queue = ArrayDeque<Receiving>()
    private var receiving: Receiving? = null

    private fun receiveNext() {
        val next = queue.removeFirstOrNull() ?: return
        receiving = next
        x.convertSelection(window, selection, next.target, dataProperty)
        x.flush()
        waitForPiece(next)
    }

    // The time limit is for each piece: a large clipboard may take long, a stuck one not.
    private fun waitForPiece(transfer: Receiving) {
        transfer.timeout?.cancel()
        transfer.timeout = loop.after(TIMEOUT_MILLIS) {
            log.warn("The X11 program did not send its clipboard in time")
            received(transfer, ok = false)
        }
    }

    private fun answered(event: MemorySegment) {
        if (xcb_selection_notify_event_t.requestor(event) != window) return
        val property = xcb_selection_notify_event_t.property(event)
        when (xcb_selection_notify_event_t.target(event)) {
            targets -> if (property != XConnection.NONE) offerFromX()
            else -> {
                val transfer = receiving ?: return
                if (property == XConnection.NONE) return received(transfer, ok = false)
                val value = x.property(window, dataProperty, maxBytes = MAX_SIZE, delete = true) ?: return received(transfer, ok = false)
                if (value.type == incr) {
                    transfer.incremental = true // the pieces follow, each as a new value of the property
                } else {
                    transfer.bytes.write(value.bytes)
                    received(transfer, ok = true)
                }
            }
        }
    }

    private fun nextPiece(transfer: Receiving) {
        val value = x.property(window, dataProperty, maxBytes = MAX_SIZE, delete = true) ?: return
        x.flush()
        if (value.bytes.isEmpty()) return received(transfer, ok = true) // the empty piece: all came
        transfer.bytes.write(value.bytes)
        waitForPiece(transfer)
        if (transfer.bytes.size() > MAX_SIZE) received(transfer, ok = false)
    }

    private fun received(transfer: Receiving, ok: Boolean) {
        if (receiving !== transfer) return
        transfer.timeout?.cancel()
        receiving = null
        if (ok) writeToWayland(transfer.fd, transfer.bytes.toByteArray()) else transfer.fd.close()
        receiveNext()
    }

    // The X program's formats (TARGETS) became the Wayland clipboard.
    private fun offerFromX() {
        val atoms = x.property(window, targetsProperty, delete = true)?.ints() ?: return
        val names = x.namesOf(atoms)
        val atomOf = LinkedHashMap<String, Int>()
        for ((atom, name) in atoms.zip(names)) {
            when {
                atom == utf8 -> { atomOf.putIfAbsent("text/plain;charset=utf-8", atom); atomOf.putIfAbsent("UTF8_STRING", atom) }
                atom == string || atom == text -> { atomOf.putIfAbsent("text/plain", atom); atomOf.putIfAbsent(name ?: continue, atom) }
                name != null && '/' in name -> atomOf.putIfAbsent(name, atom)
            }
        }
        // Programs that only offer the old text formats still give UTF-8 text (all X text today is).
        if ("text/plain;charset=utf-8" !in atomOf) atomOf["text/plain"]?.let { atomOf["text/plain;charset=utf-8"] = it }
        if (atomOf.isEmpty()) {
            log.trace { "An X11 program copied nothing Wayland programs know (${names.joinToString()})" }
            return
        }
        clipboard?.setClipboard(FromX(atomOf.keys.toList(), atomOf))
    }

    // Writes [bytes] into the pasting program's pipe as it takes them, then closes it.
    private fun writeToWayland(fd: Fd, bytes: ByteArray) {
        try {
            fd.setNonBlocking()
        } catch (e: Exception) {
            fd.close()
            return
        }
        var offset = 0
        val memory = Arena.ofShared()
        val data = memory.allocate(maxOf(1, bytes.size).toLong()).also { MemorySegment.copy(bytes, 0, it, JAVA_BYTE, 0, bytes.size) }
        fun close() {
            if (!fd.isOpen) return
            loop.remove(fd)
            fd.close()
            memory.close()
        }
        fun writeSome(): Boolean {
            while (offset < bytes.size) {
                val n = try {
                    fd.write(data.asSlice(offset.toLong(), (bytes.size - offset).toLong()))
                } catch (e: NativeException) {
                    if (e.errno == Errno.EAGAIN) return false
                    if (e.errno == Errno.EINTR) continue
                    log.trace { "The pasting program stopped reading: ${e.message}" }
                    offset = bytes.size
                    break
                }
                offset += n.toInt()
            }
            return true
        }
        if (writeSome()) {
            memory.close()
            fd.close()
            return
        }
        loop.add(fd, read = false, write = true) { if (writeSome()) close() }
        loop.after(WRITE_TIMEOUT_MILLIS) { close() } // a program that never reads
    }

    // ==== formats ============================================================================

    // The X formats a MIME type is offered as.
    private fun atomsFor(mime: String): List<Int> = when (mime) {
        "text/plain;charset=utf-8", "UTF8_STRING" -> listOf(utf8, string, text)
        "text/plain", "STRING", "TEXT" -> listOf(utf8, string, text)
        else -> if ('/' in mime) listOf(x.atom(mime)) else emptyList()
    }

    // Which of the Wayland formats answers an X program's request for [target].
    private fun mimeFor(target: Int, mimes: List<String>): String? =
        if (target == utf8 || target == string || target == text) {
            TEXT_TYPES.firstOrNull { it in mimes }
        } else {
            x.namesOf(intArrayOf(target)).first()?.takeIf { it in mimes }
        }

    override fun close() {
        closed = true
        listening?.close()
        // What an X program copied cannot be pasted once Xwayland is gone.
        if (clipboard?.clipboard is FromX) clipboard.setClipboard(null)
        for (transfer in queue) transfer.fd.close()
        queue.clear()
        receiving?.let { it.timeout?.cancel(); it.fd.close() }
        receiving = null
        sending.toList().forEach(::dropSending)
        offered = null
    }

    private companion object {
        // One property per request stays well below X's request size limit; more goes in pieces.
        const val MAX_PROPERTY = 256 * 1024 - 1024
        const val PIECE = 64 * 1024
        const val MAX_SIZE = 64 * 1024 * 1024
        const val TIMEOUT_MILLIS = 5000L
        const val WRITE_TIMEOUT_MILLIS = 60_000L
        val TEXT_TYPES = listOf("text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "STRING", "TEXT")
    }
}
