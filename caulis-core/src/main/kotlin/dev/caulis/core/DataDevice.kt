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
 * Drag and drop is refused for now (stage 8).
 *
 * The compositor can use the clipboard too ([clipboard], [setClipboard],
 * [onClipboardChanged]): to bridge it to X11 programs, keep a clipboard history, or put
 * something on it itself.
 */
class DataDeviceManager(display: Display, private val seat: Seat) {

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
                    if (source.mimeTypes.size < MAX_MIME_TYPES) source.mimeTypes += mimeType
                }

                override fun destroy(resource: WlDataSource) {}

                override fun setActions(resource: WlDataSource, dndActions: Int) {}
            }
            id.onDestroy {
                if (selection === source) setSelection(null)
            }
        }

        override fun getDataDevice(resource: WlDataDeviceManager, id: WlDataDevice, seat: WlSeat) {
            devices += id
            id.onDestroy { devices -= id }
            id.requests = object : WlDataDevice.Requests {
                override fun startDrag(resource: WlDataDevice, source: WlDataSource?, origin: WlSurface, icon: WlSurface?, serial: Int) {
                    source?.sendCancelled() // drag and drop comes later
                }

                override fun setSelection(resource: WlDataDevice, source: WlDataSource?, serial: Int) {
                    // Only the program you use may set the clipboard (one in the background
                    // could swap what you copied); clipboard managers use data-control.
                    if (!this@DataDeviceManager.seat.inUse(resource.client)) {
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
        source.mimeTypes.forEach(offer::sendOffer)
        device.sendSelection(offer)
    }

    companion object {
        const val VERSION = 3
        private const val MAX_MIME_TYPES = 64
    }
}

/**
 * Something on the clipboard: the formats it can be had in (MIME types such as
 * "text/plain;charset=utf-8" or "image/png") and a way to get it in one of them.
 */
interface ClipboardData {
    val mimeTypes: List<String>

    /** Writes the data as [mimeType] to [fd], then closes it (it may finish later). */
    fun send(mimeType: String, fd: Fd)

    /** Something else was put on the clipboard. */
    fun cancelled() {}

    /** The process of the program that put it there (0: not known, or the compositor itself). */
    val pid: Int get() = 0
}

/** Text on the clipboard, as programs ask for it (UTF-8 text in its usual names). */
class TextOnClipboard(private val text: String) : ClipboardData {
    override val mimeTypes = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")

    override fun send(mimeType: String, fd: Fd) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        Thread({
            fd.use {
                java.lang.foreign.Arena.ofConfined().use { arena ->
                    val all = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, *bytes)
                    var done = 0L
                    while (done < bytes.size) {
                        val written = it.write(all.asSlice(done))
                        if (written <= 0) break
                        done += written
                    }
                }
            }
        }, "caulis-copy").apply { isDaemon = true }.start()
    }

    override fun toString() = "text (${text.length} characters)"
}

/**
 * Any data on the clipboard, in one or more formats ([formats]: MIME type → its bytes, the
 * first the main one; a picture's PNG: "image/png"; files: "text/uri-list" and its kin),
 * written on a thread of its own.
 */
class BytesOnClipboard(private val formats: Map<String, ByteArray>) : ClipboardData {
    /** One format alone. */
    constructor(mimeType: String, bytes: ByteArray) : this(mapOf(mimeType to bytes))

    override val mimeTypes = formats.keys.toList()

    override fun send(mimeType: String, fd: Fd) {
        val bytes = formats[mimeType] ?: return fd.close()
        Thread({
            fd.use {
                java.lang.foreign.Arena.ofConfined().use { arena ->
                    val all = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, *bytes)
                    var done = 0L
                    while (done < bytes.size) {
                        val written = it.write(all.asSlice(done))
                        if (written <= 0) break
                        done += written
                    }
                }
            }
        }, "caulis-copy").apply { isDaemon = true }.start()
    }

    override fun toString() = formats.entries.joinToString { (type, bytes) -> "$type (${bytes.size} bytes)" }
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
        data.send(mimeType, writeEnd)
    } catch (e: Exception) {
        readEnd.close()
        writeEnd.close()
        return done(null)
    }
    // (The program has its own copy of the end to write: ours closes, so the read ends with it.)
    writeEnd.close()
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
