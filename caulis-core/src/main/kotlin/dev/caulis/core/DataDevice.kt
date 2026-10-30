package dev.caulis.core

import dev.caulis.core.input.Seat
import dev.caulis.ffi.Fd
import dev.caulis.log.Logger
import dev.caulis.protocol.Client
import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlDataDevice
import dev.caulis.protocol.wayland.WlDataDeviceManager
import dev.caulis.protocol.wayland.WlDataOffer
import dev.caulis.protocol.wayland.WlDataSource
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wayland.WlSurface

/**
 * wl_data_device_manager: the clipboard (copy and paste between programs).
 *
 * The program that copies offers a list of formats ("text/plain;charset=utf-8", ...).
 * The focused program is told what is on the clipboard and, when pasting, asks for one
 * format; the data then flows directly between the two programs through a pipe.
 * Drag and drop works between programs and within one (files from a file manager, tabs,
 * text): the program the button was pressed in starts it, the one the button is let go
 * over gets it, if it accepted one of the formats.
 *
 * The compositor can use the clipboard too ([clipboard], [setClipboard],
 * [onClipboardChanged]): to bridge it to X11 programs, keep a clipboard history, or put
 * something on it itself.
 */
class DataDeviceManager(private val display: Display, private val seat: Seat) {

    private val log = Logger("clipboard")
    private val devices = ArrayList<WlDataDevice>()

    /** What is on the clipboard: the source and its formats. */
    private var selection: ClipboardData? = null
    private val changeListeners = ArrayList<(ClipboardData?) -> Unit>()

    /** The primary selection: what is selected (pasted with the middle button), or null. */
    var primary: ClipboardData? = null
        private set
    private val primaryListeners = ArrayList<(ClipboardData?) -> Unit>()

    /** Puts [data] in the primary selection (null empties it). */
    fun setPrimary(data: ClipboardData?) {
        val old = primary
        if (old === data) return
        primary = data
        log.trace { "Primary selection: ${data?.let { "$it (${it.mimeTypes.joinToString()})" } ?: "empty"}" }
        old?.cancelled()
        for (listener in primaryListeners.toList()) {
            try {
                listener(data)
            } catch (e: Exception) {
                log.error("A primary selection listener failed", e)
            }
        }
    }

    /** Calls [listener] whenever the primary selection changes; close the result to stop. */
    fun onPrimaryChanged(listener: (ClipboardData?) -> Unit): AutoCloseable {
        primaryListeners += listener
        return AutoCloseable { primaryListeners -= listener }
    }

    // A program's wl_data_source.
    private class Source(val resource: WlDataSource) : ClipboardData {
        override val mimeTypes = ArrayList<String>()

        // Drag and drop: what may be done with it (copy 1, move 2, ask 4), as the program said.
        var actions = 0
        override val pid: Int get() = resource.client.pid

        override fun send(mimeType: String, fd: Fd) {
            fd.use { if (resource.isAlive && mimeType in mimeTypes) resource.sendSend(mimeType, it) }
        }

        override fun cancelled() {
            if (resource.isAlive) resource.sendCancelled()
        }

        override fun toString() = "the clipboard of ${resource.client.name}"
    }

    /** What is on the clipboard now (copied by a program or set with [setClipboard]), or null. */
    val clipboard: ClipboardData? get() = selection

    /** Puts [data] on the clipboard (null empties it); programs see it at once. */
    fun setClipboard(data: ClipboardData?) = setSelection(data)

    /** Calls [listener] whenever the clipboard changes (also by [setClipboard]); close the result to stop. */
    fun onClipboardChanged(listener: (ClipboardData?) -> Unit): AutoCloseable {
        changeListeners += listener
        return AutoCloseable { changeListeners -= listener }
    }

    init {
        display.addGlobal(WlDataDeviceManager, VERSION) { manager -> manager.requests = managerRequests }
        // The newly focused program learns what is on the clipboard.
        seat.onKeyboardFocus { surface -> surface?.let { offerSelectionTo(it.resource.client) } }
    }

    private val managerRequests = object : WlDataDeviceManager.Requests {
        override fun createDataSource(resource: WlDataDeviceManager, id: WlDataSource) {
            val source = Source(id)
            id.data = source
            id.requests = object : WlDataSource.Requests {
                override fun offer(resource: WlDataSource, mimeType: String) {
                    if (source.mimeTypes.size < MAX_MIME_TYPES && resource.client.server.fitsForwarded(mimeType)) source.mimeTypes += mimeType
                }

                override fun destroy(resource: WlDataSource) {}

                override fun setActions(resource: WlDataSource, dndActions: Int) {
                    source.actions = dndActions and ALL_ACTIONS
                }
            }
            id.onDestroy {
                if (selection === source) setSelection(null)
                // A drag of it goes too.
                if ((this@DataDeviceManager.seat.drag as? DndDrag)?.source === source) this@DataDeviceManager.seat.cancelDrag()
            }
        }

        override fun getDataDevice(resource: WlDataDeviceManager, id: WlDataDevice, seat: WlSeat) {
            devices += id
            id.onDestroy { devices -= id }
            id.requests = object : WlDataDevice.Requests {
                override fun startDrag(resource: WlDataDevice, source: WlDataSource?, origin: WlSurface, icon: WlSurface?, serial: Int) {
                    startDrag(resource, source?.dataAs<Source>(), origin, icon, serial)
                }

                override fun setSelection(resource: WlDataDevice, source: WlDataSource?, serial: Int) {
                    // Only the program you use may set the clipboard (one in the background
                    // could swap what you copied); clipboard managers use data-control.
                    if (!this@DataDeviceManager.seat.maySetSelection(resource.client, serial)) {
                        source?.sendCancelled()
                        return
                    }
                    this@DataDeviceManager.setSelection(source?.dataAs<Source>())
                }

                override fun release(resource: WlDataDevice) {}
            }
            // A device created by the focused program gets the current clipboard at once.
            if (this@DataDeviceManager.seat.keyboardFocus?.resource?.client === id.client) offerTo(id)
        }

        override fun release(resource: WlDataDeviceManager) {}
    }

    // --- drag and drop -------------------------------------------------------------------

    // Only the program a button is held in, with the serial of that press, may start a drag
    // (else any program could take the pointer); [icon] is dragged along under the cursor.
    private fun startDrag(device: WlDataDevice, source: Source?, originResource: WlSurface, iconResource: WlSurface?, serial: Int) {
        val client = device.client
        val origin = originResource.dataAs<Surface>() ?: return
        if (!seat.isInputSerial(client, serial) || !seat.buttonHeld || seat.pointerFocus?.resource?.client !== client || seat.drag != null) {
            log.info { "${client.name} tried to start a drag without a button held in it: refused" }
            source?.resource?.sendCancelled()
            return
        }
        val icon = iconResource?.dataAs<Surface>()
        if (icon != null && !icon.assignRole(Surface.Role.DND_ICON, device, WlDataDevice.Error.ROLE)) return
        log.info { "${client.name} drags ${source?.mimeTypes?.joinToString() ?: "within itself"}" }
        val drag = DndDrag(source, client, icon, origin)
        // (One listener per surface, for every drag it is in: a tab dragged many times over a
        // session does not pile them up.)
        if (icon != null && iconsHeard.put(icon, Unit) == null) icon.onCommit {
            val current = seat.drag as? DndDrag
            if (current != null && current.icon === icon) {
                current.iconX += icon.current.offsetX
                current.iconY += icon.current.offsetY
                seat.onCursorChange()
            }
        }
        if (originsHeard.put(origin, Unit) == null) origin.resource.onDestroy {
            // The window the drag started in went away (its program crashed): the drag ends.
            if ((seat.drag as? DndDrag)?.origin === origin) seat.cancelDrag()
        }
        seat.startDrag(drag)
    }

    // Surfaces already heard as drag icons and drag origins (see startDrag).
    private val iconsHeard = java.util.WeakHashMap<Surface, Unit>()
    private val originsHeard = java.util.WeakHashMap<Surface, Unit>()

    // One drag: the surface it is over gets offers of what is dragged, and the drop.
    private inner class DndDrag(val source: Source?, private val originClient: Client, override val icon: Surface?, val origin: Surface) : Seat.Drag {
        override var iconX = 0
        override var iconY = 0
        private var target: Surface? = null
        private val offers = ArrayList<WlDataOffer>()
        private var accepted: String? = null
        private var acceptedOnce = false
        private var action = 0

        // The program under the pointer: offered the data (a drag without data: only its own program).
        override fun motion(timeMillis: Int, surface: Surface?, x: Double, y: Double) {
            val over = surface?.takeIf { source != null || it.resource.client === originClient }
            if (over !== target) {
                leave()
                if (over != null) enter(over, x, y)
                return
            }
            if (over == null) return
            devicesOf(over.resource.client).forEach { it.sendMotion(timeMillis, x, y) }
        }

        private fun enter(surface: Surface, x: Double, y: Double) {
            target = surface
            accepted = null
            acceptedOnce = false
            action = 0
            val serial = display.nextSerial()
            for (device in devicesOf(surface.resource.client)) {
                val offer = source?.let { offerFor(device, it) }
                device.sendEnter(serial, surface.resource, x, y, offer)
            }
        }

        private fun offerFor(device: WlDataDevice, source: Source): WlDataOffer {
            val offer = device.client.createServerResource(WlDataOffer, device.version)
            var offerActions = 0
            var preferred = 0
            offer.requests = object : WlDataOffer.Requests {
                override fun accept(resource: WlDataOffer, serial: Int, mimeType: String?) {
                    if (resource !in offers) return
                    accepted = mimeType?.takeIf { it in source.mimeTypes }
                    if (accepted != null) acceptedOnce = true
                    if (source.resource.isAlive) source.resource.sendTarget(accepted)
                }

                override fun receive(resource: WlDataOffer, mimeType: String, fd: Fd) {
                    if (source.resource.isAlive && mimeType in source.mimeTypes) source.send(mimeType, fd) else fd.close()
                }

                override fun finish(resource: WlDataOffer) {
                    // The target took the data: the drag is done.
                    if (source.resource.isAlive && source.resource.version >= 3) source.resource.sendDndFinished()
                }

                override fun setActions(resource: WlDataOffer, dndActions: Int, preferredAction: Int) {
                    if (dndActions and ALL_ACTIONS.inv() != 0) return resource.postError(WlDataOffer.Error.INVALID_ACTION_MASK, "unknown actions $dndActions")
                    // (The preferred action is one action, or none.)
                    if (preferredAction and ALL_ACTIONS.inv() != 0 || Integer.bitCount(preferredAction) > 1) {
                        return resource.postError(WlDataOffer.Error.INVALID_ACTION, "the preferred action is one action, not $preferredAction")
                    }
                    if (resource !in offers) return // an offer of an earlier drag, or one left
                    offerActions = dndActions and ALL_ACTIONS
                    preferred = preferredAction and ALL_ACTIONS
                    // What both sides allow: the target's choice if it can, else the first.
                    val both = source.actions and offerActions
                    action = when {
                        preferred and both != 0 -> preferred and both
                        both and 1 != 0 -> 1
                        both and 2 != 0 -> 2
                        both and 4 != 0 -> 4
                        else -> 0
                    }
                    if (resource.version >= 3) resource.sendAction(action)
                    if (source.resource.isAlive && source.resource.version >= 3) source.resource.sendAction(action)
                }

                override fun destroy(resource: WlDataOffer) {
                    offers -= resource
                }
            }
            offers += offer
            device.sendDataOffer(offer)
            offerable(source.mimeTypes, offer.client).forEach(offer::sendOffer)
            if (offer.version >= 3) offer.sendSourceActions(source.actions)
            return offer
        }

        private fun leave() {
            val old = target ?: return
            target = null
            offers.clear()
            if (old.resource.isAlive) devicesOf(old.resource.client).forEach { it.sendLeave() }
        }

        override fun drop() {
            val over = target
            // Dropped where a format was accepted (and, for programs that know actions, an action agreed): it is theirs.
            val newActions = source != null && source.resource.version >= 3 && offers.any { it.version >= 3 }
            val taken = over != null && over.resource.isAlive && (source == null || (accepted != null && (!newActions || action != 0)))
            if (!taken) {
                log.trace { "Drag ended: nothing took it" }
                cancel()
                return
            }
            log.info { "Dropped on ${over.resource.client.name}" }
            devicesOf(over.resource.client).forEach { it.sendDrop() }
            if (source != null && source.resource.isAlive && source.resource.version >= 3) source.resource.sendDndDropPerformed()
            target = null
        }

        override fun cancel() {
            leave()
            if (source != null && source.resource.isAlive) source.resource.sendCancelled()
        }
    }

    private fun devicesOf(client: Client): List<WlDataDevice> = devices.filter { it.client === client && it.isAlive }

    private fun setSelection(source: ClipboardData?) {
        val old = selection
        if (old === source) return
        selection = source
        log.info { "Clipboard: ${source?.let { "$it (${it.mimeTypes.joinToString()})" } ?: "empty"}" }
        old?.cancelled()
        seat.keyboardFocus?.let { offerSelectionTo(it.resource.client) }
        for (listener in changeListeners.toList()) {
            try {
                listener(source)
            } catch (e: Exception) {
                log.error("A clipboard listener failed", e)
            }
        }
    }

    private fun offerSelectionTo(client: Client) {
        devices.filter { it.client === client }.forEach(::offerTo)
    }

    // Describes the clipboard to one program with a fresh wl_data_offer.
    private fun offerTo(device: WlDataDevice) {
        val source = selection
        if (source == null) {
            device.sendSelection(null)
            return
        }
        val offer = device.client.createServerResource(WlDataOffer, device.version)
        offer.requests = object : WlDataOffer.Requests {
            override fun accept(resource: WlDataOffer, serial: Int, mimeType: String?) {}

            override fun receive(resource: WlDataOffer, mimeType: String, fd: Fd) {
                // The pasting program gets the data straight from the copying one.
                if (selection === source && mimeType in source.mimeTypes) {
                    log.trace { "${resource.client.name} pastes $mimeType" }
                    source.send(mimeType, fd)
                } else {
                    fd.close()
                }
            }

            override fun destroy(resource: WlDataOffer) {}

            override fun finish(resource: WlDataOffer) {}

            override fun setActions(resource: WlDataOffer, dndActions: Int, preferredAction: Int) {}
        }
        device.sendDataOffer(offer)
        offerable(source.mimeTypes, offer.client).forEach(offer::sendOffer)
        device.sendSelection(offer)
    }

    companion object {
        const val VERSION = 3
        private const val MAX_MIME_TYPES = 64

        // copy, move, ask (wl_data_device_manager.dnd_action)
        private const val ALL_ACTIONS = 7
    }
}

/**
 * The formats of something on the clipboard that may be offered to [client]: at most
 * [MAX_OFFERED_TYPES], each short enough to send (whoever put it there — an X11 program, your
 * code — a name past what libwayland takes would make every program it is offered to drop
 * its connection).
 */
internal fun offerable(mimeTypes: List<String>, client: dev.caulis.protocol.Client): List<String> {
    if (mimeTypes.size <= MAX_OFFERED_TYPES && mimeTypes.all { client.server.fitsForwarded(it) }) return mimeTypes
    return mimeTypes.asSequence().filter { client.server.fitsForwarded(it) }.take(MAX_OFFERED_TYPES).toList()
}

// Formats offered at most (programs' own sources keep as many).
internal const val MAX_OFFERED_TYPES = 64

/**
 * Something on the clipboard: the formats it can be had in (MIME types such as
 * "text/plain;charset=utf-8" or "image/png") and a way to get it in one of them.
 */
interface ClipboardData {
    val mimeTypes: List<String>

    /** Writes the data as [mimeType] to [fd], then closes it (it may finish later, on a thread): [fd] is its own from then on. */
    fun send(mimeType: String, fd: Fd)

    /** Something else was put on the clipboard. */
    fun cancelled() {}

    /** The process of the program that put it there (0: not known, or the compositor itself). */
    val pid: Int get() = 0
}

/** Text on the clipboard, as programs ask for it (UTF-8 text in its usual names). */
class TextOnClipboard(private val text: String) : ClipboardData {
    override val mimeTypes = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")

    override fun send(mimeType: String, fd: Fd) = writeClipboard(fd, text.toByteArray(Charsets.UTF_8))

    override fun toString() = "text (${text.length} characters)"
}

/**
 * Any data on the clipboard, in one or more formats ([formats]: MIME type → its bytes, the
 * first the main one; a picture's PNG: "image/png"; files: "text/uri-list" and its kin),
 * written on a thread of its own.
 */
class BytesOnClipboard(formats: Map<String, ByteArray>) : ClipboardData {
    // (Its own copy of the map; the bytes themselves are not copied: do not change them after.)
    private val formats: Map<String, ByteArray> = LinkedHashMap(formats)

    /** One format alone. */
    constructor(mimeType: String, bytes: ByteArray) : this(mapOf(mimeType to bytes))

    override val mimeTypes = formats.keys.toList()

    override fun send(mimeType: String, fd: Fd) {
        val bytes = formats[mimeType] ?: return fd.close()
        writeClipboard(fd, bytes)
    }

    override fun toString() = formats.entries.joinToString { (type, bytes) -> "$type (${bytes.size} bytes)" }
}

/**
 * Writes [bytes] to [fd] (a program reading a paste) and closes it — on one of a few threads of
 * the compositor's, not the caller's. A program that stops reading has 10 seconds, then the
 * rest is dropped: none can keep threads or descriptors waiting for ever, nor make a thread for
 * each paste it asks for. For [ClipboardData.send] of your own.
 */
fun writeClipboard(fd: Fd, bytes: ByteArray) {
    try {
        ClipboardWriters.pool.execute { ClipboardWriters.write(fd, bytes) }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        // (Too many pastes waiting at once: this one gets nothing.)
        clipboardLog.warn("Too many pastes at once: one is dropped")
        fd.close()
    }
}

private val clipboardLog = dev.caulis.log.Logger("clipboard")

private object ClipboardWriters {
    // A few threads, a short queue of pastes waiting.
    val pool = java.util.concurrent.ThreadPoolExecutor(
        1, 4, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(32),
        { r -> Thread(r, "caulis-copy").apply { isDaemon = true } },
    )

    fun write(fd: Fd, bytes: ByteArray) {
        fd.use {
            try {
                it.setNonBlocking()
                java.lang.foreign.Arena.ofConfined().use { arena ->
                    val all = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, *bytes)
                    val deadline = System.nanoTime() + 10_000_000_000L
                    var done = 0L
                    while (done < bytes.size) {
                        val written = try {
                            it.write(all.asSlice(done))
                        } catch (e: dev.caulis.ffi.NativeException) {
                            if (e.errno != dev.caulis.ffi.Errno.EAGAIN) return // (the program went away)
                            0L
                        }
                        if (written > 0) {
                            done += written
                        } else {
                            // Full for now: a moment, then again — until the program has waited too long.
                            if (System.nanoTime() > deadline) return clipboardLog.trace { "A paste was not read in 10 s: the rest dropped" }
                            Thread.sleep(5)
                        }
                    }
                }
            } catch (e: Exception) {
                clipboardLog.trace { "A paste could not be written: $e" }
            }
        }
    }
}

/**
 * Reads [data] as [mimeType] (the program that put it there writes it), on a thread of its
 * own (a program that never finishes keeps only that thread waiting); [done] gets the bytes,
 * on that thread, or null when there were more than [maxBytes]. Call it on the compositor's
 * thread (the request goes to the program from there).
 */
fun readClipboard(data: ClipboardData, mimeType: String, maxBytes: Int = 32 shl 20, done: (ByteArray?) -> Unit) {
    val (readEnd, writeEnd) = Fd.pipe()
    try {
        // (send takes the end to write: it closes it when written — a program's copy went to the
        // program, ours the compositor's own data writes on a thread of its own.)
        data.send(mimeType, writeEnd)
    } catch (e: Exception) {
        readEnd.close()
        runCatching { writeEnd.close() }
        // (On a thread too, as when it is read: [done] is never run on the caller's thread.)
        Thread({ done(null) }, "caulis-paste").apply { isDaemon = true }.start()
        return
    }
    val reader = Thread({
        val out = java.io.ByteArrayOutputStream()
        var tooBig = false
        try {
            java.lang.foreign.Arena.ofConfined().use { arena ->
                val buffer = arena.allocate(64 * 1024)
                while (true) {
                    val n = try { readEnd.read(buffer) } catch (_: Exception) { -1L }
                    if (n <= 0) break
                    out.write(buffer.asSlice(0, n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
                    if (out.size() > maxBytes) {
                        tooBig = true
                        break
                    }
                }
            }
        } finally {
            readEnd.close()
        }
        done(if (tooBig) null else out.toByteArray())
    }, "caulis-paste").apply { isDaemon = true }
    reader.start()
}
