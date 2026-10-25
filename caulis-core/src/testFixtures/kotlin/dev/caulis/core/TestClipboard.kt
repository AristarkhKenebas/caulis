package dev.caulis.core

import dev.caulis.ffi.Fd
import dev.caulis.protocol.client.wayland.WlDataDevice
import dev.caulis.protocol.client.wayland.WlDataDeviceManager
import dev.caulis.protocol.client.wayland.WlDataOffer
import dev.caulis.protocol.client.wayland.WlDataSource
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.util.concurrent.CompletableFuture

/** A test program's clipboard: copies, sees what is offered, pastes. Offers go to the focused program. */
class TestClipboard(private val client: TestClient) {

    private val manager = client.bind("wl_data_device_manager", WlDataDeviceManager, 3)
    private val device = manager.getDataDevice(client.seat)
    private var selection: WlDataOffer? = null
    private val mimeTypes = HashMap<WlDataOffer, MutableList<String>>()

    init {
        device.events = object : WlDataDevice.Events {
            override fun dataOffer(proxy: WlDataDevice, id: WlDataOffer) {
                val types = ArrayList<String>()
                mimeTypes[id] = types
                id.events = object : WlDataOffer.Events {
                    override fun offer(proxy: WlDataOffer, mimeType: String) { types += mimeType }
                }
            }

            override fun selection(proxy: WlDataDevice, id: WlDataOffer?) { selection = id }
        }
        client.connection.roundtrip()
    }

    /** Copies [data] as [mimeType]; it is sent whenever someone pastes. */
    fun copy(data: ByteArray, mimeType: String = TEXT) {
        val source = manager.createDataSource()
        source.offer(mimeType)
        source.events = object : WlDataSource.Events {
            // On its own thread, as programs do: the paste may be taken only as fast as it is read.
            override fun send(proxy: WlDataSource, mimeType: String, fd: Fd) {
                Thread { fd.use { runCatching { writeAll(it, data) } } }.start()
            }
        }
        device.setSelection(source, 0)
        client.connection.roundtrip()
    }

    /** The formats on the clipboard as this program was told (empty: nothing). */
    val offered: List<String> get() = selection?.let { mimeTypes[it] }.orEmpty()

    /** Pastes as [mimeType]; read on another thread while [meanwhile] keeps whoever sends it going. */
    fun paste(mimeType: String = TEXT, meanwhile: () -> Unit = {}): ByteArray {
        val (readEnd, writeEnd) = Fd.pipe()
        checkNotNull(selection) { "nothing on the clipboard" }.receive(mimeType, writeEnd)
        client.connection.roundtrip()
        writeEnd.close()
        val result = CompletableFuture<ByteArray>()
        Thread { result.complete(readEnd.use(::readAll)) }.start()
        client.waitFor { meanwhile(); result.isDone }
        return result.get()
    }

    companion object {
        const val TEXT = "text/plain;charset=utf-8"

        /** Writes all of [data] to [fd] (waiting while it is full). */
        fun writeAll(fd: Fd, data: ByteArray) = Arena.ofConfined().use { arena ->
            val memory = arena.allocate(data.size.toLong().coerceAtLeast(1))
            MemorySegment.copy(data, 0, memory, JAVA_BYTE, 0, data.size)
            var offset = 0L
            while (offset < data.size) offset += fd.write(memory.asSlice(offset, data.size - offset))
        }

        /** Reads [fd] to its end. */
        fun readAll(fd: Fd): ByteArray = Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(65536)
            val out = ByteArrayOutputStream()
            while (true) {
                val n = fd.read(buffer)
                if (n == 0L) break
                out.write(buffer.asSlice(0, n).toArray(JAVA_BYTE))
            }
            out.toByteArray()
        }
    }
}
