package dev.caulis.wm

import dev.caulis.protocol.client.extidlenotifyv1.ExtIdleNotificationV1
import dev.caulis.protocol.client.extidlenotifyv1.ExtIdleNotifierV1
import dev.caulis.protocol.client.idleinhibitunstablev1.ZwpIdleInhibitManagerV1
import dev.caulis.protocol.client.pointerconstraintsunstablev1.ZwpLockedPointerV1
import dev.caulis.protocol.client.pointerconstraintsunstablev1.ZwpPointerConstraintsV1
import dev.caulis.protocol.client.relativepointerunstablev1.ZwpRelativePointerManagerV1
import dev.caulis.protocol.client.relativepointerunstablev1.ZwpRelativePointerV1
import dev.caulis.protocol.client.wlroutputmanagementunstablev1.ZwlrOutputConfigurationV1
import dev.caulis.protocol.client.wlroutputmanagementunstablev1.ZwlrOutputHeadV1
import dev.caulis.protocol.client.wlroutputmanagementunstablev1.ZwlrOutputManagerV1
import dev.caulis.protocol.client.wlroutputmanagementunstablev1.ZwlrOutputModeV1
import dev.caulis.protocol.client.wlroutputpowermanagementunstablev1.ZwlrOutputPowerManagerV1
import dev.caulis.protocol.client.wlroutputpowermanagementunstablev1.ZwlrOutputPowerV1
import dev.caulis.protocol.client.xdgactivationv1.XdgActivationTokenV1
import dev.caulis.protocol.client.xdgactivationv1.XdgActivationV1
import dev.caulis.protocol.client.xdgdialogv1.XdgWmDialogV1
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The protocols of everyday use: clipboard managers, focus requests, idle, games, monitors. */
class DailyProtocolsTest {

    private val harness = CaulisTest()

    // Runs wl-copy / wl-paste against the compositor (they use data-control, needing no window).
    private fun tool(socket: Path, vararg command: String, input: String? = null): String {
        val process = ProcessBuilder(*command).apply {
            environment()["WAYLAND_DISPLAY"] = socket.toString()
            environment()["XDG_RUNTIME_DIR"] = socket.parent.toString()
            redirectErrorStream(true)
        }.start()
        input?.let { process.outputStream.use { out -> out.write(it.toByteArray()) } } ?: process.outputStream.close()
        // wl-copy stays to serve what it copied: only paste is waited for — until what it copies
        // is there (a busy machine starts it slowly).
        if (command[0] == "wl-copy") {
            val primary = "--primary" in command
            val deadline = System.nanoTime() + 10_000_000_000
            while (System.nanoTime() < deadline) {
                val paste = ProcessBuilder(listOfNotNull("wl-paste", "--no-newline", "--primary".takeIf { primary })).apply {
                    environment()["WAYLAND_DISPLAY"] = socket.toString()
                    environment()["XDG_RUNTIME_DIR"] = socket.parent.toString()
                    redirectErrorStream(true)
                }.start()
                paste.outputStream.close()
                if (paste.waitFor(5, TimeUnit.SECONDS) && paste.inputStream.readAllBytes().decodeToString() == input) break
                Thread.sleep(100)
            }
            return ""
        }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "${command.joinToString(" ")} finished")
        return process.inputStream.readAllBytes().decodeToString()
    }

    @Test
    fun `clipboard managers copy and paste, the selection too`() = with(harness) {
        assumeTrue(listOf("wl-copy", "wl-paste").all { name -> System.getenv("PATH").orEmpty().split(':').any { Files.isExecutable(Path.of(it, name)) } }, "no wl-clipboard")
        withCaulis({}) { caulis, _ ->
            val socket = caulis.socketDirectory!!.resolve(caulis.onLoop { it.socket })
            tool(socket, "wl-copy", input = "from the clipboard")
            assertEquals("from the clipboard", tool(socket, "wl-paste", "--no-newline"))
            tool(socket, "wl-copy", "--primary", input = "selected")
            assertEquals("selected", tool(socket, "wl-paste", "--primary", "--no-newline"))
            assertEquals("from the clipboard", tool(socket, "wl-paste", "--no-newline"), "two separate things")
        }
    }

    @Test
    fun `a finger on a window is a touch for it, elsewhere the pointer`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val window = client.window { it.setAppId("touched") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "touched" }
            val events = java.util.Collections.synchronizedList(ArrayList<String>())
            client.seat.getTouch().events = object : dev.caulis.protocol.client.wayland.WlTouch.Events {
                override fun down(proxy: dev.caulis.protocol.client.wayland.WlTouch, serial: Int, time: Int, surface: dev.caulis.protocol.client.wayland.WlSurface, id: Int, x: Double, y: Double) {
                    events += "down $id ${x.toInt()} ${y.toInt()}"
                }
                override fun motion(proxy: dev.caulis.protocol.client.wayland.WlTouch, time: Int, id: Int, x: Double, y: Double) {
                    events += "motion $id ${x.toInt()} ${y.toInt()}"
                }
                override fun up(proxy: dev.caulis.protocol.client.wayland.WlTouch, serial: Int, time: Int, id: Int) {
                    events += "up $id"
                }
            }
            var looks = 0
            client.waitFor { ++looks > 3 } // (the touch object reaches the compositor)
            val rect = caulis.onLoop { r -> r.desktop.touchScreen = true; r.desktop.shownRect(r.desktop.allWindows.first { it.appId == "touched" })!! }
            caulis.onLoop { r ->
                r.desktop.touchDown(1, 0, rect.x + 10.0, rect.y + 20.0)
                r.desktop.touchFrame()
                r.desktop.touchMotion(2, 0, rect.x + 15.0, rect.y + 30.0)
                r.desktop.touchFrame()
                r.desktop.touchUp(3, 0)
                r.desktop.touchFrame()
            }
            client.waitFor { events.size >= 3 }
            assertEquals(listOf("down 0 10 20", "motion 0 15 30", "up 0"), events.toList())
            window.toString()
        }
    }

    @Test
    fun `focusing a window whose modal dialog is open focuses the dialog`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val main = client.window { it.setAppId("main") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "main" }
            val dialog = client.window { it.setAppId("dialog"); it.setParent(main.toplevel) }.also { it.draw(200, 100) }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "dialog" }
            // Not modal yet: the main window can have the focus.
            caulis.onLoop { r -> r.desktop.focus(r.desktop.allWindows.first { it.appId == "main" }) }
            assertEquals("main", caulis.onLoop { r -> r.desktop.focusedWindow?.appId })

            val dialogs = client.bind("xdg_wm_dialog_v1", XdgWmDialogV1, 1)
            dialogs.getXdgDialog(dialog.toplevel).setModal()
            client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.first { it.appId == "dialog" }.modal } }
            caulis.onLoop { r -> r.desktop.focus(r.desktop.allWindows.first { it.appId == "main" }) }
            assertEquals("dialog", caulis.onLoop { r -> r.desktop.focusedWindow?.appId }, "the dialog it waits for")
        }
    }

    @Test
    fun `a window asking for the focus gets it with a good token, else it is marked urgent`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val first = client.window { it.setAppId("first") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "first" }
            val second = client.window { it.setAppId("second") }.also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "second" }

            val activation = client.bind("xdg_activation_v1", XdgActivationV1, 1)
            fun token(): String {
                var received: String? = null
                val request = activation.getActivationToken()
                request.events = object : XdgActivationTokenV1.Events {
                    override fun done(proxy: XdgActivationTokenV1, token: String) {
                        received = token
                    }
                }
                request.commit()
                client.waitFor { received != null }
                return received!!
            }
            // The program has the keyboard: its token is good.
            activation.activate(token(), first.surface)
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "first" }

            // A made-up token: only "urgent".
            activation.activate("not a token", second.surface)
            client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.single { it.appId == "second" }.urgent } }
            assertEquals("first", caulis.onLoop { r -> r.desktop.focusedWindow?.appId })
            caulis.onLoop { r -> r.config.run("focus-urgent") }
            assertEquals("second", caulis.onLoop { r -> r.desktop.focusedWindow?.appId })
            assertFalse(caulis.onLoop { r -> r.desktop.focusedWindow!!.urgent }, "the focus clears it")
        }
    }

    @Test
    fun `a token a program asked for while it had the keyboard is no good once it lost it`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val socket = caulis.socketDirectory!!.resolve(caulis.onLoop { it.socket })
            dev.caulis.core.TestClient(dev.caulis.protocol.client.Connection.connect(socket)).use { thief ->
                val own = thief.window { it.setAppId("thief") }.also { it.draw() }
                thief.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "thief" }
                val activation = thief.bind("xdg_activation_v1", XdgActivationV1, 1)
                fun token(): String {
                    var received: String? = null
                    val request = activation.getActivationToken()
                    request.events = object : XdgActivationTokenV1.Events {
                        override fun done(proxy: XdgActivationTokenV1, token: String) {
                            received = token
                        }
                    }
                    request.commit()
                    thief.waitFor { received != null }
                    return received!!
                }
                // Asked for while it had the keyboard (no input needed), kept for later.
                val saved = token()
                // You move on to another program.
                client.window { it.setAppId("yours") }.also { it.draw() }
                client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow?.appId } == "yours" }
                activation.activate(saved, own.surface)
                thief.waitFor { caulis.onLoop { r -> r.desktop.allWindows.single { it.appId == "thief" }.urgent } }
                assertEquals("yours", caulis.onLoop { r -> r.desktop.focusedWindow?.appId }, "the focus stays where you are")
            }
        }
    }

    @Test
    fun `idle - told after a while without input, and when someone is back, unless a window keeps it awake`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val notifier = client.bind("ext_idle_notifier_v1", ExtIdleNotifierV1, 1)
            var idle = false
            var resumed = false
            notifier.getIdleNotification(300, client.seat).events = object : ExtIdleNotificationV1.Events {
                override fun idled(proxy: ExtIdleNotificationV1) { idle = true }
                override fun resumed(proxy: ExtIdleNotificationV1) { resumed = true }
            }
            client.waitFor { idle }
            caulis.onLoop { r -> r.key(0, 30, true); r.key(0, 30, false) }
            client.waitFor { resumed }

            // A video player keeps its window on screen: no idle.
            val video = client.window().also { it.draw() }
            client.waitFor { caulis.onLoop { r -> r.desktop.windows.isNotEmpty() } }
            client.bind("zwp_idle_inhibit_manager_v1", ZwpIdleInhibitManagerV1, 1).createInhibitor(video.surface)
            var idleAgain = false
            notifier.getIdleNotification(200, client.seat).events = object : ExtIdleNotificationV1.Events {
                override fun idled(proxy: ExtIdleNotificationV1) { idleAgain = true }
                override fun resumed(proxy: ExtIdleNotificationV1) {}
            }
            val until = System.nanoTime() + 700_000_000
            client.waitFor { System.nanoTime() > until }
            assertFalse(idleAgain, "kept awake")
        }
    }

    @Test
    fun `a game locks the pointer and gets the movement itself`() = with(harness) {
        withCaulis({}) { caulis, client ->
            val game = client.window().also { it.draw(400, 300) }
            client.waitFor { caulis.onLoop { r -> r.desktop.focusedWindow != null } }
            val pointer = client.seat.getPointer()
            var moved = 0.0
            client.bind("zwp_relative_pointer_manager_v1", ZwpRelativePointerManagerV1, 1).getRelativePointer(pointer).events =
                object : ZwpRelativePointerV1.Events {
                    override fun relativeMotion(proxy: ZwpRelativePointerV1, utimeHi: Int, utimeLo: Int, dx: Double, dy: Double, dxUnaccel: Double, dyUnaccel: Double) {
                        moved += dxUnaccel
                    }
                }
            // The pointer over the game first.
            val (x, y) = caulis.onLoop { r -> r.desktop.windows.single().let { it.x + 50.0 to it.y + 50.0 } }
            caulis.onLoop { r -> r.desktop.pointerMoved(0, x, y) }
            var locked = false
            client.bind("zwp_pointer_constraints_v1", ZwpPointerConstraintsV1, 1)
                .lockPointer(game.surface, pointer, null, ZwpPointerConstraintsV1.Lifetime.PERSISTENT).events = object : ZwpLockedPointerV1.Events {
                override fun locked(proxy: ZwpLockedPointerV1) { locked = true }
                override fun unlocked(proxy: ZwpLockedPointerV1) { locked = false }
            }
            client.waitFor { locked }
            caulis.onLoop { r -> r.desktop.pointerMovedBy(0, 30.0, 0.0, 20.0, 0.0) }
            client.waitFor { moved == 20.0 }
            assertEquals(x, caulis.onLoop { r -> r.desktop.pointerX }, "the pointer stays where it is")
        }
    }

    @Test
    fun `programs arrange headless monitors and switch screens off`() = with(harness) {
        withCaulis({}, headless = true) { caulis, client ->
            val manager = client.bind("zwlr_output_manager_v1", ZwlrOutputManagerV1, 4)
            val heads = LinkedHashMap<ZwlrOutputHeadV1, String>()
            val modes = ArrayList<Pair<ZwlrOutputModeV1, Pair<Int, Int>>>()
            var latest = 0
            manager.events = object : ZwlrOutputManagerV1.Events {
                override fun head(proxy: ZwlrOutputManagerV1, head: ZwlrOutputHeadV1) {
                    head.events = object : ZwlrOutputHeadV1.Events {
                        override fun name(proxy: ZwlrOutputHeadV1, name: String) { heads[proxy] = name }
                        override fun mode(proxy: ZwlrOutputHeadV1, mode: ZwlrOutputModeV1) {
                            mode.events = object : ZwlrOutputModeV1.Events {
                                override fun size(proxy: ZwlrOutputModeV1, width: Int, height: Int) { modes += proxy to (width to height) }
                            }
                        }
                    }
                }

                override fun done(proxy: ZwlrOutputManagerV1, serial: Int) {
                    latest = serial
                }
            }
            client.waitFor { latest != 0 && heads.isNotEmpty() }
            assertEquals(listOf("HEADLESS-1"), heads.values.toList())
            val (mode, _) = modes.first { it.second == (1280 to 720) }

            var result = ""
            val configuration = manager.createConfiguration(latest)
            configuration.events = object : ZwlrOutputConfigurationV1.Events {
                override fun succeeded(proxy: ZwlrOutputConfigurationV1) { result = "ok" }
                override fun failed(proxy: ZwlrOutputConfigurationV1) { result = "failed" }
                override fun cancelled(proxy: ZwlrOutputConfigurationV1) { result = "cancelled" }
            }
            val head = configuration.enableHead(heads.keys.single())
            head.setMode(mode)
            head.setTransform(1) // 90: standing on its side
            head.setScale(2.0)
            configuration.apply()
            client.waitFor { result != "" }
            assertEquals("ok", result)
            caulis.onLoop { r ->
                val monitor = r.desktop.monitors.single()
                assertEquals(720 to 1280, monitor.pixelWidth to monitor.pixelHeight, "turned: width and height traded")
                assertEquals(2.0, monitor.scale)
                assertEquals(dev.caulis.core.Transform.ROTATE_90, monitor.transform)
            }

            var power = -1
            client.bind("zwlr_output_power_manager_v1", ZwlrOutputPowerManagerV1, 1).getOutputPower(client.output!!).also { p ->
                p.events = object : ZwlrOutputPowerV1.Events {
                    override fun mode(proxy: ZwlrOutputPowerV1, mode: Int) { power = mode }
                }
                client.waitFor { power == 1 }
                p.setMode(0)
            }
            client.waitFor { power == 0 }
            assertFalse(caulis.onLoop { r -> r.desktop.monitors.single().powered })
            caulis.onLoop { r -> r.config.run("dpms on") }
            client.waitFor { power == 1 }
        }
    }
}
