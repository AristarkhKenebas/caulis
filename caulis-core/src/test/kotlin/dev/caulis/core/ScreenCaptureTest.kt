package dev.caulis.core

import dev.caulis.ffi.SharedMemory
import dev.caulis.protocol.client.Connection
import dev.caulis.protocol.client.ProxyType
import dev.caulis.protocol.client.Proxy
import dev.caulis.protocol.client.extforeigntoplevellistv1.ExtForeignToplevelHandleV1
import dev.caulis.protocol.client.extforeigntoplevellistv1.ExtForeignToplevelListV1
import dev.caulis.protocol.client.extimagecapturesourcev1.ExtForeignToplevelImageCaptureSourceManagerV1
import dev.caulis.protocol.client.extimagecapturesourcev1.ExtOutputImageCaptureSourceManagerV1
import dev.caulis.protocol.client.wayland.WlOutput
import dev.caulis.protocol.client.xdgoutputunstablev1.ZxdgOutputManagerV1
import dev.caulis.protocol.client.xdgoutputunstablev1.ZxdgOutputV1
import dev.caulis.protocol.client.extimagecopycapturev1.ExtImageCopyCaptureFrameV1
import dev.caulis.protocol.client.extimagecopycapturev1.ExtImageCopyCaptureManagerV1
import dev.caulis.protocol.client.extimagecopycapturev1.ExtImageCopyCaptureSessionV1
import dev.caulis.protocol.client.extsessionlockv1.ExtSessionLockV1
import dev.caulis.protocol.client.wayland.WlRegistry
import dev.caulis.protocol.client.wlrscreencopyunstablev1.ZwlrScreencopyFrameV1
import dev.caulis.protocol.client.wlrscreencopyunstablev1.ZwlrScreencopyManagerV1
import dev.caulis.render.Dmabuf
import dev.caulis.render.gles.GlesRenderer
import dev.caulis.tiling.Floating
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Screenshots and recordings (wlr-screencopy, ext-image-copy-capture), as grim, wf-recorder and screen sharing use them. */
class ScreenCaptureTest {

    private val width = ScreenTestServer.WIDTH
    private val height = ScreenTestServer.HEIGHT

    private fun <T : Proxy> TestClient.global(wanted: String, type: ProxyType<T>, version: Int): T {
        var found = -1
        val registry = connection.display.getRegistry()
        registry.events = object : WlRegistry.Events {
            override fun global(proxy: WlRegistry, name: Int, `interface`: String, versionArg: Int) {
                if (`interface` == wanted) found = name
            }
        }
        connection.roundtrip()
        check(found >= 0) { "no $wanted" }
        return registry.bind(found, type, version)
    }

    // A screencopy frame as a screenshot program sees it.
    private class Shot(val frame: ZwlrScreencopyFrameV1) {
        @Volatile var size = 0 to 0
        @Volatile var stride = 0
        @Volatile var described = false
        @Volatile var ready = false
        @Volatile var failed = false
        @Volatile var damage: List<Int>? = null
    }

    private fun shot(frame: ZwlrScreencopyFrameV1): Shot {
        val shot = Shot(frame)
        frame.events = object : ZwlrScreencopyFrameV1.Events {
            override fun buffer(proxy: ZwlrScreencopyFrameV1, format: Int, width: Int, height: Int, stride: Int) {
                shot.size = width to height
                shot.stride = stride
            }
            override fun bufferDone(proxy: ZwlrScreencopyFrameV1) { shot.described = true }
            override fun damage(proxy: ZwlrScreencopyFrameV1, x: Int, y: Int, width: Int, height: Int) { shot.damage = listOf(x, y, width, height) }
            override fun ready(proxy: ZwlrScreencopyFrameV1, tvSecHi: Int, tvSecLo: Int, tvNsec: Int) { shot.ready = true }
            override fun failed(proxy: ZwlrScreencopyFrameV1) { shot.failed = true }
        }
        return shot
    }

    private fun SharedMemory.pixel(x: Int, y: Int, stride: Int) = read { it.get(JAVA_INT, y.toLong() * stride + x * 4L) }

    @Test
    fun `a screenshot of the screen and of a part of it`() = withServer { server, apps, grim ->
        server.onServer { it.layout = Floating }
        apps.window().draw(200, 100)
        apps.connection.roundtrip()
        grim.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val window = server.onServer { d -> d.screenRect(d.windows.first())!! }
        val manager = grim.global("zwlr_screencopy_manager_v1", ZwlrScreencopyManagerV1, 3)

        val whole = shot(manager.captureOutput(0, grim.output!!))
        grim.waitFor { whole.described }
        assertEquals(width to height, whole.size)
        val (buffer, memory) = grim.buffer(width, height)
        whole.frame.copy(buffer)
        grim.waitFor { whole.ready || whole.failed }
        assertTrue(whole.ready, "the picture came")
        assertEquals(BACKGROUND, memory.pixel(2, 2, whole.stride), "the desktop's background")
        assertEquals(BLACK, memory.pixel(window.x + 10, window.y + 10, whole.stride), "the window")

        val part = shot(manager.captureOutputRegion(0, grim.output, window.x - 5, window.y - 5, 20, 20))
        grim.waitFor { part.described }
        assertEquals(20 to 20, part.size)
        val (small, smallMemory) = grim.buffer(20, 20)
        part.frame.copy(small)
        grim.waitFor { part.ready || part.failed }
        assertEquals(BACKGROUND, smallMemory.pixel(1, 1, part.stride), "outside the window")
        assertEquals(BLACK, smallMemory.pixel(10, 10, part.stride), "inside the window")
    }

    @Test
    fun `a recording gets the next picture only when something changed, and what`() = withServer { server, apps, recorder ->
        server.onServer { it.layout = Floating }
        val window = apps.window()
        window.draw(200, 100)
        apps.connection.roundtrip()
        recorder.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val manager = recorder.global("zwlr_screencopy_manager_v1", ZwlrScreencopyManagerV1, 3)
        val (buffer, _) = recorder.buffer(width, height)

        val first = shot(manager.captureOutput(0, recorder.output!!))
        recorder.waitFor { first.described }
        first.frame.copyWithDamage(buffer)
        recorder.waitFor { first.ready }

        val second = shot(manager.captureOutput(0, recorder.output))
        recorder.waitFor { second.described }
        second.frame.copyWithDamage(buffer)
        repeat(20) { recorder.connection.roundtrip(); Thread.sleep(15) }
        assertFalse(second.ready, "nothing changed: no new picture")

        window.draw(200, 100) // the window draws again
        apps.connection.roundtrip()
        recorder.waitFor { second.ready }
        val rect = server.onServer { d -> d.screenRect(d.windows.first())!! }
        val (x, y, w, h) = second.damage!!
        assertTrue(x <= rect.x && y <= rect.y && x + w >= rect.right && y + h >= rect.bottom && w < width, "the damage is the window: ${second.damage}, window $rect")
    }

    @Test
    fun `a recorder's GPU buffer gets the picture without a copy through memory`() = withServer { server, apps, recorder ->
        server.onServer { it.layout = Floating }
        apps.window().draw(200, 100)
        apps.connection.roundtrip()
        recorder.waitFor { server.onServer { d -> d.windows.firstOrNull()?.let(d::screenRect) != null } }
        val window = server.onServer { d -> d.screenRect(d.windows.first())!! }
        val manager = recorder.global("zwlr_screencopy_manager_v1", ZwlrScreencopyManagerV1, 3)
        var offered = false
        val frame = manager.captureOutput(0, recorder.output!!)
        var ready = false
        var failed = false
        frame.events = object : ZwlrScreencopyFrameV1.Events {
            override fun linuxDmabuf(proxy: ZwlrScreencopyFrameV1, format: Int, width: Int, height: Int) { offered = format == Dmabuf.XRGB8888 }
            override fun ready(proxy: ZwlrScreencopyFrameV1, tvSecHi: Int, tvSecLo: Int, tvNsec: Int) { ready = true }
            override fun failed(proxy: ZwlrScreencopyFrameV1) { failed = true }
        }
        recorder.connection.roundtrip()
        assertTrue(offered, "a GPU buffer is offered")
        // The recorder's own GPU buffer, as wf-recorder or OBS allocate one.
        GlesRenderer.create().use { own ->
            own.createBuffer(width, height, Dmabuf.XRGB8888, listOf(Dmabuf.MODIFIER_LINEAR), scanout = false).use { gpu ->
                val params = recorder.dmabuf!!.createParams()
                gpu.export().use { exported ->
                    exported.planes.forEachIndexed { index, plane ->
                        params.add(plane.fd, index, plane.offset, plane.stride, (exported.modifier ushr 32).toInt(), exported.modifier.toInt())
                    }
                }
                val buffer = params.createImmed(width, height, Dmabuf.XRGB8888, 0)
                params.destroy()
                frame.copy(buffer)
                recorder.waitFor { ready || failed }
                assertTrue(ready, "the picture came")
                java.lang.foreign.Arena.ofConfined().use { arena ->
                    val pixels = arena.allocate(width.toLong() * height * 4)
                    gpu.readPixels(pixels, width * 4)
                    fun at(x: Int, y: Int) = pixels.get(JAVA_INT, (y.toLong() * width + x) * 4)
                    assertEquals(BACKGROUND, at(2, 2), "the background, in the recorder's own buffer")
                    assertEquals(BLACK, at(window.x + 10, window.y + 10), "the window")
                }
            }
        }
    }

    @Test
    fun `a screenshot as grim 1_5 takes it, with the monitor's size from xdg-output`() = withServer { server, apps, grim ->
        apps.window().draw(200, 100)
        apps.connection.roundtrip()
        grim.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }
        // grim asks where the monitor is and how big (without this it guesses 0 x 0).
        var logical = 0 to 0
        var described = false
        grim.global("zxdg_output_manager_v1", ZxdgOutputManagerV1, 3).getXdgOutput(grim.output!!).events = object : ZxdgOutputV1.Events {
            override fun logicalSize(proxy: ZxdgOutputV1, width: Int, height: Int) { logical = width to height }
        }
        grim.output.events = object : WlOutput.Events {
            override fun done(proxy: WlOutput) { described = true }
        }
        grim.waitFor { described }
        assertEquals(width to height, logical)

        val sources = grim.global("ext_output_image_capture_source_manager_v1", ExtOutputImageCaptureSourceManagerV1, 1)
        val copier = grim.global("ext_image_copy_capture_manager_v1", ExtImageCopyCaptureManagerV1, 1)
        val sharing = Sharing(copier.createSession(sources.createSource(grim.output), 0))
        sharing.session.events = object : ExtImageCopyCaptureSessionV1.Events {
            override fun bufferSize(proxy: ExtImageCopyCaptureSessionV1, width: Int, height: Int) { sharing.size = width to height }
            override fun done(proxy: ExtImageCopyCaptureSessionV1) { sharing.constraintsDone = true }
        }
        grim.waitFor { sharing.constraintsDone }
        assertEquals(width to height, sharing.size)
        val (buffer, memory) = grim.buffer(width, height)
        val frame = sharing.session.createFrame()
        var ready = false
        frame.events = object : ExtImageCopyCaptureFrameV1.Events {
            override fun ready(proxy: ExtImageCopyCaptureFrameV1) { ready = true }
        }
        frame.attachBuffer(buffer)
        frame.damageBuffer(0, 0, Int.MAX_VALUE, Int.MAX_VALUE) // as grim does
        frame.capture()
        grim.waitFor { ready }
        assertEquals(BACKGROUND, memory.pixel(1, height - 2, width * 4), "the desktop")
    }

    // An ext-image-copy-capture session as screen sharing of one window uses it.
    private class Sharing(val session: ExtImageCopyCaptureSessionV1) {
        @Volatile var size = 0 to 0
        @Volatile var constraintsDone = false
        @Volatile var stopped = false
    }

    private class Picture(val frame: ExtImageCopyCaptureFrameV1) {
        @Volatile var ready = false
        @Volatile var failed = -1
    }

    @Test
    fun `one window is shared, also while it is on another workspace, but not while the screen is locked`() = withServer { server, apps, sharer ->
        apps.window { it.setTitle("shared") }.draw(160, 120)
        apps.connection.roundtrip()
        sharer.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }
        // The window goes to workspace 2: nobody sees it now.
        server.onServer { d -> d.moveToWorkspace(d.windows.first(), 2) }

        var handle: ExtForeignToplevelHandleV1? = null
        sharer.global("ext_foreign_toplevel_list_v1", ExtForeignToplevelListV1, 1).events = object : ExtForeignToplevelListV1.Events {
            override fun toplevel(proxy: ExtForeignToplevelListV1, toplevel: ExtForeignToplevelHandleV1) { handle = toplevel }
        }
        sharer.waitFor { handle != null }
        val sources = sharer.global("ext_foreign_toplevel_image_capture_source_manager_v1", ExtForeignToplevelImageCaptureSourceManagerV1, 1)
        val copier = sharer.global("ext_image_copy_capture_manager_v1", ExtImageCopyCaptureManagerV1, 1)
        val sharing = Sharing(copier.createSession(sources.createSource(handle!!), 0))
        sharing.session.events = object : ExtImageCopyCaptureSessionV1.Events {
            override fun bufferSize(proxy: ExtImageCopyCaptureSessionV1, width: Int, height: Int) { sharing.size = width to height }
            override fun done(proxy: ExtImageCopyCaptureSessionV1) { sharing.constraintsDone = true }
            override fun stopped(proxy: ExtImageCopyCaptureSessionV1) { sharing.stopped = true }
        }
        sharer.waitFor { sharing.constraintsDone }
        assertEquals(160 to 120, sharing.size, "as big as the window")

        fun picture(): Picture {
            val picture = Picture(sharing.session.createFrame())
            picture.frame.events = object : ExtImageCopyCaptureFrameV1.Events {
                override fun ready(proxy: ExtImageCopyCaptureFrameV1) { picture.ready = true }
                override fun failed(proxy: ExtImageCopyCaptureFrameV1, reason: Int) { picture.failed = reason }
            }
            return picture
        }
        val (buffer, memory) = sharer.buffer(160, 120, format = 0) // ARGB8888
        val first = picture()
        first.frame.attachBuffer(buffer)
        first.frame.capture()
        sharer.waitFor { first.ready || first.failed >= 0 }
        assertTrue(first.ready, "a picture of the hidden window (failed: ${first.failed})")
        assertEquals(BLACK, memory.pixel(80, 60, 160 * 4), "the window's picture")
        assertTrue(server.onServer { d -> d.allWindows.first().surface.let { s -> d.isKeptUpdating(s) } }, "it keeps running while shared")

        // Locked: no window is given out.
        val lock = sharer.sessionLock!!.lock()
        var locked = false
        lock.events = object : ExtSessionLockV1.Events {
            override fun locked(proxy: ExtSessionLockV1) { locked = true }
        }
        sharer.waitFor { locked }
        val second = picture()
        second.frame.attachBuffer(buffer)
        second.frame.capture()
        repeat(20) { sharer.connection.roundtrip(); Thread.sleep(15) }
        assertFalse(second.ready, "no window pictures while locked")
        lock.unlockAndDestroy()
        sharer.connection.roundtrip()
    }

    @Test
    fun `a window hidden from capture is a black box when shared alone`() = withServer { server, apps, sharer ->
        // A red window (its own picture: the usual test pictures are black).
        val window = apps.window { it.setTitle("secret") }
        val (red, redMemory) = apps.buffer(160, 120)
        redMemory.write { m -> for (i in 0 until 160 * 120) m.setAtIndex(JAVA_INT, i.toLong(), 0x00FF0000) }
        window.surface.attach(red, 0, 0)
        window.surface.damageBuffer(0, 0, 160, 120)
        window.surface.commit()
        apps.connection.roundtrip()
        sharer.waitFor { server.onServer { d -> d.windows.isNotEmpty() } }

        var handle: ExtForeignToplevelHandleV1? = null
        sharer.global("ext_foreign_toplevel_list_v1", ExtForeignToplevelListV1, 1).events = object : ExtForeignToplevelListV1.Events {
            override fun toplevel(proxy: ExtForeignToplevelListV1, toplevel: ExtForeignToplevelHandleV1) { handle = toplevel }
        }
        sharer.waitFor { handle != null }
        val sources = sharer.global("ext_foreign_toplevel_image_capture_source_manager_v1", ExtForeignToplevelImageCaptureSourceManagerV1, 1)
        val copier = sharer.global("ext_image_copy_capture_manager_v1", ExtImageCopyCaptureManagerV1, 1)
        val sharing = Sharing(copier.createSession(sources.createSource(handle!!), 0))
        sharing.session.events = object : ExtImageCopyCaptureSessionV1.Events {
            override fun bufferSize(proxy: ExtImageCopyCaptureSessionV1, width: Int, height: Int) { sharing.size = width to height }
            override fun done(proxy: ExtImageCopyCaptureSessionV1) { sharing.constraintsDone = true }
            override fun stopped(proxy: ExtImageCopyCaptureSessionV1) { sharing.stopped = true }
        }
        sharer.waitFor { sharing.constraintsDone }
        val (buffer, memory) = sharer.buffer(160, 120, format = 0) // ARGB8888
        fun take(): Int {
            val picture = Picture(sharing.session.createFrame())
            picture.frame.events = object : ExtImageCopyCaptureFrameV1.Events {
                override fun ready(proxy: ExtImageCopyCaptureFrameV1) { picture.ready = true }
                override fun failed(proxy: ExtImageCopyCaptureFrameV1, reason: Int) { picture.failed = reason }
            }
            picture.frame.attachBuffer(buffer)
            picture.frame.damageBuffer(0, 0, 160, 120)
            picture.frame.capture()
            sharer.waitFor { picture.ready || picture.failed >= 0 }
            assertTrue(picture.ready, "a picture (failed: ${picture.failed})")
            return memory.pixel(80, 60, 160 * 4)
        }
        assertEquals(0xFFFF0000.toInt(), take(), "the window's own picture")
        // A rule says it is hidden from capture (a password manager): black, as on a shared screen.
        server.onServer { d -> d.behaviorFor = { WindowBehavior(hiddenFromCapture = true) } }
        // (A new picture of it, for the capture to have something new.)
        window.surface.attach(red, 0, 0)
        window.surface.damageBuffer(0, 0, 160, 120)
        window.surface.commit()
        apps.connection.roundtrip()
        assertEquals(BLACK, take(), "not its contents")
        sharer.connection.roundtrip()
    }

    private fun withServer(test: (ScreenTestServer, TestClient, TestClient) -> Unit) {
        assumeTrue(Files.isDirectory(Path.of("/dev/dri")), "no GPU")
        ScreenTestServer().use { server ->
            TestClient(Connection.connect(server.socketPath)).use { apps ->
                TestClient(Connection.connect(server.socketPath)).use { capturer -> test(server, apps, capturer) }
            }
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        // Desktop's BACKGROUND (0.16, 0.18, 0.22) in 8 bits.
        const val BACKGROUND = 0xFF292E38.toInt()
    }
}
