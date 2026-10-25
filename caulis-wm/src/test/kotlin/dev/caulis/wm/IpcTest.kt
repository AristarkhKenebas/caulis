package dev.caulis.wm

import dev.caulis.ipc.CaulisIpc
import dev.caulis.ipc.IpcException
import dev.caulis.ipc.v1.Event
import dev.caulis.protocol.client.extsessionlockv1.ExtSessionLockV1
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** The IPC socket, as a bar or a script uses it. */
class IpcTest {

    private val harness = CaulisTest()

    // Events read on a thread of their own, so a test waits for them with a time limit.
    private class Events(private val ipc: CaulisIpc) {
        private val queue = LinkedBlockingQueue<Event>()

        init {
            Thread.ofPlatform().daemon().start {
                while (true) queue.put(ipc.nextEvent() ?: break)
            }
        }

        // The next event that fits (others before it are skipped); the test program goes on
        // answering the compositor meanwhile.
        fun next(client: dev.caulis.core.TestClient, what: String, fits: (Event) -> Boolean): Event {
            var found: Event? = null
            client.waitFor {
                while (found == null) {
                    val event = queue.poll(20, TimeUnit.MILLISECONDS) ?: break
                    if (fits(event)) found = event
                }
                found != null
            }
            return found ?: fail("no $what event came")
        }
    }

    @Test
    fun `a program asks, acts on windows and hears what changes`() = with(harness) {
        withCaulis({ configName = "testwm" }) { caulis, client ->
            val socket = caulis.socketDirectory!!.resolve("testwm-${caulis.onLoop { it.socket }}.sock")
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(socket), "the user's alone")

            val first = client.window { it.setAppId("first") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "first" }

            CaulisIpc.connect(socket).use { ipc ->
                val hello = ipc.hello()
                assertEquals("testwm", hello.name)
                assertEquals(1, hello.protocol)
                assertEquals(caulis.onLoop { it.socket }, hello.waylandDisplay)

                val state = ipc.state()
                val window = state.windowsList.single { it.appId == "first" }
                assertTrue(window.focused)
                assertEquals(1, window.workspace)
                assertEquals(window.id, state.focusedWindow)
                assertTrue(window.rect.width > 0, "with its place")
                assertEquals(1, state.monitorsList.size)

                CaulisIpc.connect(socket).use { listener ->
                    listener.subscribe()
                    val events = Events(listener)

                    client.window { it.setAppId("second") }.draw()
                    val opened = events.next(client, "window-opened") { it.hasWindowOpened() }.windowOpened
                    assertEquals("second", opened.appId)
                    assertEquals(opened.id, events.next(client, "focus-changed") { it.hasFocusChanged() }.focusChanged)

                    // On a window that is not focused: the first one goes to workspace 2.
                    ipc.run("move-to-workspace 2", window.id)
                    val moved = events.next(client, "window-changed") { it.hasWindowChanged() && it.windowChanged.id == window.id }.windowChanged
                    assertEquals(2, moved.workspace)

                    ipc.run("workspace 2")
                    assertEquals(2, events.next(client, "workspace-shown") { it.hasWorkspaceShown() }.workspaceShown.number)

                    ipc.run("notify Hello from a script")
                    assertEquals("Hello from a script", events.next(client, "notice") { it.hasNotice() }.notice.text)
                }

                // Mistakes come back in words.
                val wrong = assertFailsWith<IpcException> { ipc.run("worksapce 3") }
                assertTrue("did you mean \"workspace\"" in wrong.message!!, wrong.message)
                assertTrue("no window" in assertFailsWith<IpcException> { ipc.run("close", 999_999) }.message!!)
                assertEquals(listOf("line 1: scale: 0.5 to 4, not 9"), ipc.checkConfig("{ \"scale\": 9 }"))
            }

            // Rubbish on the socket: that program is sent away, the compositor goes on.
            SocketChannel.open(StandardProtocolFamily.UNIX).use { bad ->
                bad.connect(UnixDomainSocketAddress.of(socket))
                bad.write(ByteBuffer.wrap(byteArrayOf(-1, -1, -1, -1, -1, -1, 1)))
                val gone = ByteBuffer.allocate(16)
                assertEquals(-1, bad.read(gone), "disconnected")
            }
            CaulisIpc.connect(socket).use { assertEquals("testwm", it.hello().name) }
            first.draw()
        }
    }

    @Test
    fun `while the screen is locked, programs cannot move things`() = with(harness) {
        withCaulis({ configName = "testwm" }) { caulis, client ->
            val socket = caulis.socketDirectory!!.resolve("testwm-${caulis.onLoop { it.socket }}.sock")
            CaulisIpc.connect(socket).use { ipc ->
                var locked = false
                client.sessionLock!!.lock().events = object : ExtSessionLockV1.Events {
                    override fun locked(proxy: ExtSessionLockV1) {
                        locked = true
                    }
                }
                client.waitFor { locked }
                assertTrue("locked" in assertFailsWith<IpcException> { ipc.run("workspace 2") }.message!!)
                ipc.run("notify still allowed")
                assertTrue(ipc.state().locked)
            }
        }
    }
}
