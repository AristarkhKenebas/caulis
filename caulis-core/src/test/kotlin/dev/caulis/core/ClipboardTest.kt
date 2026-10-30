package dev.caulis.core

import dev.caulis.ffi.Fd
import dev.caulis.protocol.client.Connection
import dev.caulis.render.assumeGpu
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Copy and paste: between programs, and the compositor's own use of the clipboard. */
class ClipboardTest {

    @Test
    fun `what a program copies can be pasted, and the compositor hears of it`() = withServer { server, client ->
        val heard = CopyOnWriteArrayList<List<String>>()
        server.onServer { server.clipboard.onClipboardChanged { heard += it?.mimeTypes.orEmpty() } }
        val clipboard = TestClipboard(client)
        clipboard.copy("hello".toByteArray())
        client.waitFor { TestClipboard.TEXT in clipboard.offered }
        assertEquals("hello", clipboard.paste().decodeToString())
        assertEquals(listOf(listOf(TestClipboard.TEXT)), heard.toList())
    }

    @Test
    fun `formats too many or too long (an X11 program's) are not offered, and nobody is disconnected`() = withServer { server, client ->
        // (An X11 program's TARGETS: an atom name may be 64 KB, and there may be thousands.)
        val types = listOf(TestClipboard.TEXT, "a/" + "x".repeat(5000)) + List(200) { "x-test/type-$it" }
        server.onServer {
            server.clipboard.setClipboard(object : ClipboardData {
                override val mimeTypes = types
                override fun send(mimeType: String, fd: Fd) = fd.use { TestClipboard.writeAll(it, "still here".toByteArray()) }
            })
        }
        val clipboard = TestClipboard(client)
        client.waitFor { TestClipboard.TEXT in clipboard.offered }
        repeat(3) { client.connection.roundtrip() }
        assertEquals(64, clipboard.offered.size, "at most 64 formats")
        assertTrue(clipboard.offered.all { it.length < 4096 }, "none too long to send")
        assertEquals("still here", clipboard.paste().decodeToString(), "the program is still connected")
    }

    @Test
    fun `the compositor can put something on the clipboard`() = withServer { server, client ->
        var cancelled = false
        server.onServer {
            server.clipboard.setClipboard(object : ClipboardData {
                override val mimeTypes = listOf(TestClipboard.TEXT)
                override fun send(mimeType: String, fd: Fd) = fd.use { TestClipboard.writeAll(it, "from the compositor".toByteArray()) }
                override fun cancelled() { cancelled = true }
            })
        }
        val clipboard = TestClipboard(client)
        client.waitFor { TestClipboard.TEXT in clipboard.offered }
        assertEquals("from the compositor", clipboard.paste().decodeToString())
        // A program copying replaces it.
        clipboard.copy("mine".toByteArray())
        client.waitFor { server.onServer { cancelled } }
    }

    @Test
    fun `the compositor can read what a program copied`() = withServer { server, client ->
        val clipboard = TestClipboard(client)
        clipboard.copy("for the history".toByteArray())
        client.waitFor { server.onServer { server.clipboard.clipboard != null } }
        val (readEnd, writeEnd) = Fd.pipe()
        server.onServer { server.clipboard.clipboard!!.send(TestClipboard.TEXT, writeEnd) }
        val result = CompletableFuture<ByteArray>()
        Thread { result.complete(readEnd.use(TestClipboard::readAll)) }.start()
        client.waitFor { result.isDone } // the program sends it as it handles its messages
        assertEquals("for the history", result.get().decodeToString())
        assertTrue(server.onServer { server.clipboard.clipboard!!.mimeTypes } == listOf(TestClipboard.TEXT))
    }

    private fun withServer(test: (ScreenTestServer, TestClient) -> Unit) {
        assumeGpu()
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { client ->
                // The clipboard is offered to the focused program: give it a window.
                client.window().draw()
                client.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }
                test(server, client)
            }
        }
    }
}
