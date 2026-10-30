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
 * Data streams through both ways: small data in one answer, large data in pieces
 * (ICCCM's INCR) handed on as they come — the copying side is read only as fast as the
 * pasting side takes it, so only a few pieces are ever held, however large the clipboard.
 * Text formats are matched up (UTF8_STRING, STRING, TEXT ↔ text/plain); other formats
 * keep their MIME names.
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
    private var closed = false

    /** True for the helper windows the clipboard uses (they are not program windows). */
    fun owns(id: Int): Boolean = id == window

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
            if (atom == dataProperty && !deleted) receiving?.takeIf { it.incremental }?.nextPiece()
            return true
        }
        val transfer = sending.firstOrNull { it.requestor == window && it.property == atom } ?: return false
        if (deleted) transfer.requestorTookPiece()
        return true
    }

    /** An X window was destroyed: what was being handed to it is dropped. */
    fun windowGone(id: Int) {
        sending.filter { it.requestor == id }.forEach { it.stop("its window is gone") }
    }

    // ==== Wayland → X11 ======================================================================

    // What a Wayland program copied, while our window owns CLIPBOARD for it, and since when (X time).
    private var offered: ClipboardData? = null
    private var ownedSince = 0

    private fun waylandCopied(data: ClipboardData?) {
        if (closed || isOurs(data)) return // copied in our X server: its owner is an X program there
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

    private val sending = ArrayList<Sending>()

    // From an X program of this X server (another one's is like a Wayland program's to us).
    private fun isOurs(data: ClipboardData?) = data is FromX && data.server === this

    private fun requested(event: MemorySegment) {
        val requestor = xcb_selection_request_event_t.requestor(event)
        val target = xcb_selection_request_event_t.target(event)
        val time = xcb_selection_request_event_t.time(event)
        // Old programs give no property: the answer then goes into the one named like the target.
        val property = xcb_selection_request_event_t.property(event).takeIf { it != XConnection.NONE } ?: target
        val data = offered
        if (xcb_selection_request_event_t.selection(event) != selection || data == null) return answer(requestor, target, XConnection.NONE, time)
        when (target) {
            targets -> {
                val atoms = (listOf(targets, timestamp) + data.mimeTypes.flatMap(::atomsFor)).distinct()
                x.setProperty(requestor, property, Xcb.XCB_ATOM_ATOM(), atoms.toIntArray())
                answer(requestor, target, property, time)
            }
            timestamp -> {
                x.setProperty(requestor, property, Xcb.XCB_ATOM_INTEGER(), intArrayOf(ownedSince))
                answer(requestor, target, property, time)
            }
            else -> {
                val mime = mimeFor(target, data.mimeTypes) ?: return answer(requestor, target, XConnection.NONE, time)
                log.trace { "An X11 program pastes $mime" }
                // A new request replaces an unfinished one into the same place.
                sending.filter { it.requestor == requestor && it.property == property }.forEach { it.stop("asked again") }
                sending += Sending(requestor, property, target, time, data, mime)
            }
        }
    }

    private fun answer(requestor: Int, target: Int, property: Int, time: Int) {
        x.sendSelectionNotify(requestor, selection, target, property, time)
        x.flush()
    }

    /**
     * One X program's paste of a Wayland program's clipboard: small data in one answer; more
     * as pieces, each handed over when the X program took (deleted) the one before.
     */
    private inner class Sending(
        val requestor: Int, val property: Int, val target: Int, val time: Int, data: ClipboardData, mime: String,
    ) {
        private val pieces = ArrayDeque<ByteArray>()
        private var held = 0
        private var ended = false
        private var answered = false   // the X program was told (all of it, or "pieces follow")
        private var wantsPiece = false // it took the last piece and waits for the next
        private var wantsPieceAfterLast = false // the closing empty piece was sent
        private var finished = false
        private var timeout: EventLoop.Timer? = null
        private val reader: PipeReader

        init {
            val (readEnd, writeEnd) = Fd.pipe()
            reader = PipeReader(readEnd, ::arrived, ::ended)
            data.send(mime, writeEnd)
            progressed()
        }

        private fun arrived(bytes: ByteArray) {
            pieces += bytes
            held += bytes.size
            progressed()
            when {
                !answered && held > MAX_PROPERTY -> beginPieces()
                answered && wantsPiece -> sendPiece()
            }
            if (held > MAX_HELD) reader.pause() // the X program is slower: the copying side waits
        }

        private fun ended(ok: Boolean) {
            if (!ok) return stop("the copying program failed")
            ended = true
            if (!answered) {
                // All of it fits in one answer.
                x.setBytes(requestor, property, target, take(held))
                answer(requestor, target, property, time)
                finish()
            } else if (wantsPiece) {
                sendPiece()
            }
        }

        // Too big for one answer: say "pieces follow" (and roughly how much); the X program
        // deletes the property to ask for the first.
        private fun beginPieces() {
            if (!hearsPropertiesOf(requestor)) {
                x.changeAttributes(requestor, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE()))
            }
            x.setProperty(requestor, property, incr, intArrayOf(held))
            answered = true
            answer(requestor, target, property, time)
        }

        fun requestorTookPiece() {
            if (finished) return
            if (ended && pieces.isEmpty() && wantsPieceAfterLast) return finish() // it took the closing empty piece
            wantsPiece = true
            sendPiece()
        }

        private fun sendPiece() {
            if (pieces.isEmpty() && !ended) return // the next comes when the copying side sends it
            val piece = take(minOf(PIECE, held))
            x.setBytes(requestor, property, target, piece)
            x.flush()
            wantsPiece = false
            progressed()
            if (piece.isEmpty()) wantsPieceAfterLast = true // the empty piece says "that was all"
            if (held < MAX_HELD / 2) reader.resume()
        }

        // The first [count] bytes held, as one array.
        private fun take(count: Int): ByteArray {
            val result = ByteArray(count)
            var at = 0
            while (at < count) {
                val first = pieces.removeFirst()
                val used = minOf(first.size, count - at)
                System.arraycopy(first, 0, result, at, used)
                if (used < first.size) pieces.addFirst(first.copyOfRange(used, first.size))
                at += used
            }
            held -= count
            return result
        }

        // Neither side may stall it forever.
        private fun progressed() {
            timeout?.cancel()
            timeout = loop.after(TIMEOUT_MILLIS) { stop("no progress for ${TIMEOUT_MILLIS / 1000} seconds") }
        }

        fun stop(why: String) {
            if (finished) return
            log.trace { "A paste into an X11 program stopped: $why" }
            if (!answered) answer(requestor, target, XConnection.NONE, time)
            finish()
        }

        private fun finish() {
            finished = true
            timeout?.cancel()
            reader.close()
            pieces.clear()
            sending -= this
        }
    }

    // ==== X11 → Wayland ======================================================================

    /** On the Wayland clipboard: what an X program copied (the data is asked for when pasted). */
    private inner class FromX(override val mimeTypes: List<String>, val atomOf: Map<String, Int>) : ClipboardData {
        val server: XClipboard get() = this@XClipboard

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
            if (isOurs(clipboard?.clipboard)) clipboard?.setClipboard(null)
            return
        }
        // An X program copied: what can it give?
        x.convertSelection(window, selection, targets, targetsProperty, xcb_xfixes_selection_notify_event_t.selection_timestamp(event))
        x.flush()
    }

    private val queue = ArrayDeque<Receiving>()
    private var receiving: Receiving? = null

    private fun receiveNext() {
        val next = queue.removeFirstOrNull() ?: return
        receiving = next
        next.ask()
    }

    /**
     * One Wayland program's paste of an X program's clipboard, served by the X owner; one at
     * a time (they share our property). Each piece goes on into the pipe as it comes; the
     * next is asked for (by deleting the property) once the pipe took most of it.
     */
    private inner class Receiving(val target: Int, fd: Fd) {
        var incremental = false
        private val writer = PipeWriter(fd)
        private var timeout: EventLoop.Timer? = null
        private var waitingToAsk = false

        fun ask() {
            x.convertSelection(window, selection, target, dataProperty)
            x.flush()
            progressed()
        }

        fun answered(property: Int) {
            if (property == XConnection.NONE) return done(ok = false)
            val value = x.property(window, dataProperty, maxBytes = MAX_READ, delete = true) ?: return done(ok = false)
            if (value.type == incr) {
                incremental = true // the pieces follow, each as a new value of the property
                progressed()
            } else {
                writer.write(value.bytes)
                done(ok = true)
            }
        }

        fun nextPiece() {
            val value = x.property(window, dataProperty, maxBytes = MAX_READ) ?: return
            progressed()
            if (value.bytes.isEmpty()) {
                x.deleteProperty(window, dataProperty)
                x.flush()
                return done(ok = true) // the empty piece: all came
            }
            writer.write(value.bytes)
            // The next piece only once the pasting program keeps up.
            if (writer.queued < MAX_HELD) askForMore() else {
                waitingToAsk = true
                writer.onDrained = { if (waitingToAsk) askForMore() }
            }
        }

        private fun askForMore() {
            waitingToAsk = false
            x.deleteProperty(window, dataProperty)
            x.flush()
        }

        private fun progressed() {
            timeout?.cancel()
            timeout = loop.after(TIMEOUT_MILLIS) {
                log.warn("The X11 program did not send its clipboard in time")
                done(ok = false)
            }
        }

        fun done(ok: Boolean) {
            if (receiving !== this) return
            timeout?.cancel()
            receiving = null
            if (ok) writer.finish() else writer.close()
            receiveNext()
        }

        fun abandon() {
            timeout?.cancel()
            writer.close()
        }
    }

    private fun answered(event: MemorySegment) {
        if (xcb_selection_notify_event_t.requestor(event) != window) return
        val property = xcb_selection_notify_event_t.property(event)
        when (val target = xcb_selection_notify_event_t.target(event)) {
            targets -> if (property != XConnection.NONE) offerFromX()
            // Only the answer for the paste waited for now (a late one for a paste given up
            // on must not end up in the next program's paste).
            else -> receiving?.takeIf { it.target == target }?.answered(property)
        }
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

    // ==== pipes, without ever blocking the compositor =========================================

    /** Reads a pipe as data comes; [pause] stops reading for a while (the other side is slower). */
    private inner class PipeReader(private val fd: Fd, private val data: (ByteArray) -> Unit, private val end: (ok: Boolean) -> Unit) {
        private var paused = false
        private var done = false

        init {
            fd.setNonBlocking()
            loop.add(fd) { readSome() }
        }

        private fun readSome() = Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(PIECE.toLong())
            while (!paused && !done) {
                val n = try {
                    fd.read(buffer)
                } catch (e: NativeException) {
                    if (e.errno == Errno.EINTR) continue
                    if (e.errno == Errno.EAGAIN) return@use
                    return@use finish(ok = false)
                }
                if (n == 0L) return@use finish(ok = true)
                data(buffer.asSlice(0, n).toArray(JAVA_BYTE))
            }
        }

        // Not watched at all while paused (a closed pipe would else wake the loop again and again).
        fun pause() {
            if (paused || done) return
            paused = true
            loop.remove(fd)
        }

        fun resume() {
            if (!paused || done) return
            paused = false
            loop.add(fd) { readSome() }
            readSome()
        }

        private fun finish(ok: Boolean) {
            if (done) return
            close()
            end(ok)
        }

        fun close() {
            if (done) return
            done = true
            if (!paused) loop.remove(fd)
            fd.close()
        }
    }

    /** Writes into a pipe as fast as the reader takes it; [finish] closes it once all is written. */
    private inner class PipeWriter(private val fd: Fd) {
        private val pending = ArrayDeque<ByteArray>()
        private var offset = 0 // into the first pending array
        private var watching = false
        private var finishing = false
        private var timeout: EventLoop.Timer? = null

        /** Bytes not written yet. */
        var queued = 0
            private set

        /** Called each time everything queued was written. */
        var onDrained: (() -> Unit)? = null

        init {
            runCatching { fd.setNonBlocking() }.onFailure { close() }
        }

        fun write(bytes: ByteArray) {
            if (!fd.isOpen || bytes.isEmpty()) return
            pending += bytes
            queued += bytes.size
            flush()
        }

        fun finish() {
            finishing = true
            flush()
        }

        private fun flush() {
            if (!fd.isOpen) return
            Arena.ofConfined().use { arena ->
                // (One piece at a time, as much as a pipe takes: not all that is left of a
                // 64 MB answer copied anew for each write.)
                val piece = arena.allocate(PIECE.toLong())
                while (pending.isNotEmpty()) {
                    val head = pending.first()
                    val size = minOf(head.size - offset, PIECE)
                    MemorySegment.copy(head, offset, piece, JAVA_BYTE, 0, size)
                    val n = try {
                        fd.write(piece.asSlice(0, size.toLong()))
                    } catch (e: NativeException) {
                        if (e.errno == Errno.EINTR) continue
                        if (e.errno == Errno.EAGAIN) return@use waitForRoom()
                        log.trace { "The pasting program stopped reading: ${e.message}" }
                        return@use close()
                    }
                    offset += n.toInt()
                    queued -= n.toInt()
                    if (offset == head.size) {
                        pending.removeFirst()
                        offset = 0
                    }
                }
                if (watching) {
                    loop.remove(fd)
                    watching = false
                }
                timeout?.cancel()
                onDrained?.invoke()
                if (finishing) close()
            }
        }

        // The pipe is full: go on when it has room (a program that never reads is given up on).
        private fun waitForRoom() {
            if (!watching) {
                watching = true
                loop.add(fd, read = false, write = true) { flush() }
            }
            timeout?.cancel()
            timeout = loop.after(WRITE_TIMEOUT_MILLIS) { close() }
        }

        fun close() {
            timeout?.cancel()
            if (!fd.isOpen) return
            if (watching) loop.remove(fd)
            watching = false
            fd.close()
            pending.clear()
            queued = 0
        }
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
        if (isOurs(clipboard?.clipboard)) clipboard?.setClipboard(null)
        for (transfer in queue) transfer.abandon()
        queue.clear()
        receiving?.abandon()
        receiving = null
        sending.toList().forEach { it.stop("the clipboard closed") }
        offered = null
    }

    // Last in the class: it uses the state declared above (initialized by now).
    init {
        x.changeAttributes(window, Xcb.XCB_CW_EVENT_MASK(), intArrayOf(Xcb.XCB_EVENT_MASK_PROPERTY_CHANGE()))
        if (clipboard != null) {
            x.watchSelection(window, selection)
            listening = clipboard.onClipboardChanged(::waylandCopied)
            clipboard.clipboard?.let(::waylandCopied)
        }
    }

    private companion object {
        // One property per request stays well below X's request size limit; more goes in pieces.
        const val MAX_PROPERTY = 256 * 1024 - 1024
        const val PIECE = 64 * 1024
        // At most this much is held for a slower side before the faster one is made to wait.
        const val MAX_HELD = 4 * PIECE
        // The largest single answer an X program may give (with BIG-REQUESTS).
        const val MAX_READ = 1 shl 26 // (64 MB in one piece; bigger comes in parts, INCR)
        const val TIMEOUT_MILLIS = 5000L
        const val WRITE_TIMEOUT_MILLIS = 60_000L
        val TEXT_TYPES = listOf("text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "STRING", "TEXT")
    }
}
