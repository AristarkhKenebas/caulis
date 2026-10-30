package dev.caulis.screenshot

import dev.caulis.core.Background
import dev.caulis.core.Effects
import dev.caulis.core.TestClient
import dev.caulis.core.TitleBars
import dev.caulis.geometry.Rect
import dev.caulis.ffi.SharedMemory
import dev.caulis.log.InternalCaulisApi
import dev.caulis.protocol.client.Connection
import dev.caulis.render.Color
import dev.caulis.render.assumeGpu
import dev.caulis.wm.Caulis
import java.awt.image.BufferedImage
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Screenshots in a running compositor (on monitors of its own, nowhere on screen): two monitors,
 * one at a fractional scale; windows of plain colours.
 */
class ScreenshotsTest {

    @Test
    fun `a part chosen on either of two monitors is saved with the pixels it covers`() = withShots { caulis, folder, _ ->
        // On the one at 1.25: 200x150 units are 250x188 pixels (each edge rounded).
        caulis.onLoop {
            Screenshots.of(caulis).choose(ShotKind.REGION)
            drag(caulis, 101.0, 101.0, 301.0, 251.0)
            key(caulis, KEY_ENTER)
            assertNull(desktop.overlay, "closed when taken")
        }
        val firstFile = waitForFiles(folder, 1).single()
        val first = image(firstFile)
        assertEquals(250, first.width)
        assertEquals(188, first.height)
        // On the one beside it at 1.0.
        caulis.onLoop {
            Screenshots.of(caulis).choose(ShotKind.REGION)
            drag(caulis, 2148.0, 100.0, 2348.0, 250.0)
            key(caulis, KEY_ENTER)
        }
        val second = image(waitForFiles(folder, 2).first { it != firstFile })
        assertEquals(200, second.width)
        assertEquals(150, second.height)
        assertEquals(BACKGROUND, second.getRGB(5, 5) and 0xFFFFFF, "the screen as it was")
    }

    @Test
    fun `the chooser starts choosing a part each time, unless it is told to remember the kind`() = withShots { caulis, folder, _ ->
        caulis.onLoop {
            val shots = Screenshots.of(caulis)
            assertEquals(ShotKind.REGION, shots.kind)
            shots.choose(ShotKind.SCREEN)
            key(caulis, KEY_ENTER)
            assertEquals(ShotKind.REGION, shots.kind, "a part again next time (rememberKind off)")
        }
        waitForFiles(folder, 1)
        caulis.onLoop {
            val shots = Screenshots.of(caulis)
            shots.chooser = shots.chooser.copy(rememberKind = true)
            shots.choose(ShotKind.SCREEN)
            key(caulis, KEY_ENTER)
            assertEquals(ShotKind.SCREEN, shots.kind, "the kind used last (rememberKind on)")
        }
        waitForFiles(folder, 2)
    }

    @Test
    fun `the part chosen last is there again and is taken without a drag`() = withShots { caulis, folder, _ ->
        caulis.onLoop {
            Screenshots.of(caulis).choose(ShotKind.REGION)
            drag(caulis, 101.0, 101.0, 301.0, 251.0)
            key(caulis, KEY_ENTER)
        }
        val first = waitForFiles(folder, 1).single()
        val (part, clear) = caulis.onLoop {
            Screenshots.of(caulis).choose()
            val overlay = desktop.overlay as ScreenshotOverlay
            val shown = overlay.part to overlay.placeOf(ChooserButton.CLEAR)
            key(caulis, KEY_ENTER)
            assertNull(desktop.overlay, "taken at once")
            shown
        }
        assertEquals(Rect(101, 101, 200, 150), part, "the same part again")
        assertNotNull(clear, "the clear button is there with it")
        val second = image(waitForFiles(folder, 2).first { it != first })
        assertEquals(250, second.width)
        assertEquals(188, second.height)
    }

    @Test
    fun `Escape clears the part shown, a second Escape closes, and it is not offered again`() = withShots { caulis, folder, _ ->
        caulis.onLoop {
            Screenshots.of(caulis).choose(ShotKind.REGION)
            drag(caulis, 101.0, 101.0, 301.0, 251.0)
            key(caulis, KEY_ENTER)
        }
        waitForFiles(folder, 1)
        caulis.onLoop {
            val shots = Screenshots.of(caulis)
            shots.choose()
            val overlay = desktop.overlay as ScreenshotOverlay
            assertNotNull(overlay.part)
            key(caulis, KEY_ESC)
            assertTrue(desktop.overlay === overlay, "still open")
            assertNull(overlay.part, "the part cleared")
            assertNull(overlay.placeOf(ChooserButton.CLEAR), "the clear button gone with it")
            key(caulis, KEY_ESC)
            assertNull(desktop.overlay, "the second Escape closes")
            shots.choose()
            assertNull((desktop.overlay as ScreenshotOverlay).part, "not offered again")
            key(caulis, KEY_ENTER)
            assertEquals(shots.style.words.dragFirst, desktop.onScreenDisplay, "nothing to take")
            key(caulis, KEY_ESC)
            assertNull(desktop.overlay)
            // Without escapeClearsSelection: Escape closes at once, and forgets the part.
            shots.chooser = shots.chooser.copy(escapeClearsSelection = false)
            shots.choose()
            drag(caulis, 101.0, 101.0, 301.0, 251.0)
            key(caulis, KEY_ESC)
            assertNull(desktop.overlay, "closed at once")
            assertNull(shots.remembered, "and forgotten")
        }
    }

    @Test
    fun `the clear button clears the part, and it is kept across restarts if told to`() = withShots { caulis, folder, _ ->
        val state = Files.createTempDirectory("screenshot-state")
        try {
            val file = state.resolve("selection")
            caulis.onLoop {
                val shots = Screenshots.of(caulis)
                shots.chooser = shots.chooser.copy(rememberAcrossRestarts = true)
                shots.selectionFile = file
                shots.choose(ShotKind.REGION)
                drag(caulis, 101.0, 101.0, 301.0, 251.0)
                key(caulis, KEY_ENTER)
            }
            waitForFiles(folder, 1)
            assertTrue(Files.isRegularFile(file), "kept in its file")
            caulis.onLoop {
                val shots = Screenshots.of(caulis)
                // As after a restart: only the file knows it.
                shots.asAfterRestart()
                shots.choose()
                val overlay = desktop.overlay as ScreenshotOverlay
                assertEquals(Rect(101, 101, 200, 150), overlay.part, "offered again from the file")
                val (bx, by) = assertNotNull(overlay.placeOf(ChooserButton.CLEAR))
                desktop.pointerMoved(0, bx, by)
                desktop.pointerButton(0, BTN_LEFT, true)
                desktop.pointerButton(0, BTN_LEFT, false)
                assertNull(overlay.part, "cleared by its button")
                assertNull(overlay.placeOf(ChooserButton.CLEAR), "the button gone with it")
                assertTrue(desktop.overlay === overlay, "still open")
                assertNull(shots.remembered)
                key(caulis, KEY_ESC)
                assertNull(desktop.overlay)
            }
            assertTrue(!Files.exists(file), "the file gone with it")
        } finally {
            state.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the part chosen last is dropped when the monitors change`() = withShots { caulis, folder, _ ->
        caulis.onLoop {
            Screenshots.of(caulis).choose(ShotKind.REGION)
            drag(caulis, 101.0, 101.0, 301.0, 251.0)
            key(caulis, KEY_ENTER)
        }
        waitForFiles(folder, 1)
        caulis.onLoop {
            val shots = Screenshots.of(caulis)
            assertNotNull(shots.remembered)
            desktop.setScale(desktop.monitors.first { it.name == "HEADLESS-1" }, 1.0)
            shots.choose()
            assertNull((desktop.overlay as ScreenshotOverlay).part, "not offered on other monitors")
            assertNull(shots.remembered, "dropped")
            key(caulis, KEY_ESC)
        }
    }

    @Test
    fun `a screenshot copied is pasted by the compositor's few clipboard writers, not a thread per paste`() = withShots { caulis, _, _ ->
        caulis.onLoop {
            val shots = Screenshots.of(caulis)
            shots.save = false
            shots.copy = true
            shots.takeMonitor()
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        var data: dev.caulis.core.ClipboardData? = null
        while (data == null && System.nanoTime() < deadline) {
            data = caulis.onLoop { clipboard.clipboard }
            Thread.sleep(20)
        }
        val copied = assertNotNull(data, "on the clipboard")
        assertEquals(listOf("image/png"), copied.mimeTypes)
        // Pasted many times at once: each gets the picture, and no thread of its own is made.
        val pastes = 20
        val all = (1..pastes).map { CompletableFuture<ByteArray?>() }
        caulis.onLoop { for (paste in all) dev.caulis.core.readClipboard(copied, "image/png") { paste.complete(it) } }
        for (paste in all) {
            val png = assertNotNull(paste.get(10, TimeUnit.SECONDS), "a paste read")
            assertNotNull(ImageIO.read(java.io.ByteArrayInputStream(png)), "a PNG")
        }
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "caulis-screenshot-paste" }, "no thread per paste")
    }

    @Test
    fun `the countdown is shown, but is not in the picture`() = withShots { caulis, folder, _ ->
        val shots = caulis.onLoop { Screenshots.of(caulis) }
        var pill: dev.caulis.geometry.Rect? = null
        var origin = 0 to 0
        var scale = 1.0
        caulis.onLoop {
            shots.delaySeconds = 1
            // The monitor with the focus (where the pill is) taken.
            val monitor = desktop.focusedMonitor
            origin = monitor.area.x to monitor.area.y
            scale = monitor.scale
            desktop.pointerMoved(0, monitor.area.x + 10.0, monitor.area.y + 10.0)
            shots.takeMonitor()
            pill = Countdown(desktop, shots.style).also { it.left = 1 }.area(monitor)
        }
        val at = assertNotNull(pill)
        Thread.sleep(300)
        val during = caulis.onLoop {
            Frozen.capture(desktop, desktop.focusedMonitor, false).use { it.read(at) }
        }
        val middle = during.argb[(during.height / 2) * during.width + during.width / 2] and 0xFFFFFF
        assertTrue(middle != BACKGROUND, "the countdown is on screen while it counts")
        assertNull(caulis.onLoop { desktop.onScreenDisplay }, "(its own pill, not the on-screen display)")
        val picture = image(waitForFiles(folder, 1).single())
        val x = Math.round((at.x - origin.first + at.width / 2.0) * scale).toInt()
        val y = Math.round((at.y - origin.second + at.height / 2.0) * scale).toInt()
        assertEquals(BACKGROUND, picture.getRGB(x, y) and 0xFFFFFF, "no countdown in the picture")
    }

    @Test
    fun `windows put together whole, a window hidden from capture black and never taken alone`() = withShots { caulis, folder, client ->
        client.colored("open", 400, 300, GREEN)
        client.colored("secret", 300, 200, RED)
        client.waitFor { caulis.onLoop { desktop.allWindows.count { it.mapped && desktop.drawnNow(it) } } == 2 }
        Thread.sleep(200)
        val shots = caulis.onLoop { Screenshots.of(caulis) }
        val secret = caulis.onLoop { desktop.allWindows.first { it.appId == "secret" } }
        // Alone: not taken, said.
        caulis.onLoop { shots.takeWindow(secret) }
        assertEquals(shots.style.words.hiddenWindow, caulis.onLoop { desktop.onScreenDisplay })
        // Together: in their places at their own size (1.25 pixels a unit), the hidden one black, corners round.
        val (picture, shown) = caulis.onLoop {
            shots.sharpCornerRadius = 12f
            shots.sharpWallpaper = false
            val monitor = desktop.monitors.first { it.name == "HEADLESS-1" }
            val shown = shots.windowsShown(monitor)
            shots.windowsPicture(monitor, shown, null)!! to shown
        }
        val pixels = picture.draw()
        val union = shown.map { it.second }.reduce(dev.caulis.geometry.Rect::union)
        assertEquals(Math.round(union.width * 1.25).toInt(), pixels.width)
        assertEquals(Math.round(union.height * 1.25).toInt(), pixels.height)
        fun middleOf(name: String): Int {
            val r = shown.first { it.first.appId == name }.second
            val x = Math.round((r.x - union.x + r.width / 2.0) * 1.25).toInt()
            val y = Math.round((r.y - union.y + r.height / 2.0) * 1.25).toInt()
            return pixels.argb[y * pixels.width + x]
        }
        assertEquals(GREEN, middleOf("open") and 0xFFFFFF)
        assertEquals(0xFF000000.toInt(), middleOf("secret"), "black where the hidden one is")
        val open = shown.first { it.first.appId == "open" }.second
        val corner = pixels.argb[Math.round((open.y - union.y) * 1.25).toInt() * pixels.width + Math.round((open.x - union.x) * 1.25).toInt()]
        assertEquals(0, corner ushr 24, "its corner see-through")
        Thread.sleep(300)
        assertEquals(0L, Files.list(folder).use { it.count() }, "nothing saved of the hidden window")
    }

    // --- the compositor ------------------------------------------------------------------------

    private fun Caulis.setUp() {
        headlessMonitors = 2
        monitor("HEADLESS-1") { mode(2560, 1440); scale = 1.25 }
        monitor("HEADLESS-2") { mode(1920, 1080); scale = 1.0 }
        background = Background.color(Color.hex("#203040"))
        effects = Effects.NONE
        titleBars = TitleBars.NONE
        rule { appId("open"); monitor = "HEADLESS-1"; floating = true; size(400, 300); position(100, 100) }
        rule { appId("secret"); monitor = "HEADLESS-1"; floating = true; size(300, 200); position(600, 200); hiddenFromCapture = true }
    }

    // A compositor with screenshots saved in a folder of the test's own, not copied, not said.
    private fun withShots(test: (Caulis, Path, TestClient) -> Unit) {
        assumeGpu()
        val sockets = Files.createTempDirectory("caulis-screenshot-test")
        val folder = Files.createTempDirectory("screenshots")
        val caulis = Caulis().apply {
            useConfigFile = false // never the config.json of whoever runs the tests
            setUp()
            backend = Caulis.Backend.HEADLESS
            socketDirectory = sockets
            exitAfterSeconds = 30 // never hang the test run
            screenshots {
                keys(null, null, null, null)
                this.folder = folder
                copy = false
                notify = false
            }
        }
        val started = CompletableFuture<Unit>()
        caulis.onStart { started.complete(Unit) }
        val thread = Thread.ofPlatform().start { caulis.run() }
        try {
            started.get(10, TimeUnit.SECONDS)
            val socket = Files.list(sockets).use { all -> all.filter { it.fileName.toString().startsWith("wayland-") && !it.toString().endsWith(".lock") }.findFirst().get() }
            TestClient(Connection.connect(socket)).use { client -> test(caulis, folder, client) }
        } finally {
            caulis.post { quit() }
            thread.join(5000)
            for (memory in memories) memory.close()
            memories.clear()
            sockets.toFile().deleteRecursively()
            folder.toFile().deleteRecursively()
        }
    }

    // Runs [action] on the compositor's thread and gives back its result.
    private fun <T> Caulis.onLoop(action: Caulis.() -> T): T {
        val result = CompletableFuture<T>()
        post { runCatching { action() }.fold(result::complete, result::completeExceptionally) }
        return try {
            result.get(5, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun drag(caulis: Caulis, x0: Double, y0: Double, x1: Double, y1: Double) {
        val d = caulis.desktop
        d.pointerMoved(0, x0, y0)
        d.pointerButton(0, BTN_LEFT, true)
        d.pointerMoved(0, x1, y1)
        d.pointerButton(0, BTN_LEFT, false)
    }

    private fun key(caulis: Caulis, code: Int) {
        caulis.desktop.key(0, code, true)
        caulis.desktop.key(0, code, false)
    }

    private val memories = ArrayList<SharedMemory>()

    // A window of [appId] all in [rgb], [width] x [height].
    @OptIn(InternalCaulisApi::class)
    private fun TestClient.colored(appId: String, width: Int, height: Int, rgb: Int) {
        val window = window { it.setAppId(appId) }
        val (buffer, memory) = buffer(width, height)
        memories += memory
        memory.write { segment -> for (i in 0L until width.toLong() * height) segment.setAtIndex(ValueLayout.JAVA_INT, i, rgb) }
        window.surface.attach(buffer, 0, 0)
        window.surface.damageBuffer(0, 0, width, height)
        window.surface.commit()
        connection.roundtrip()
    }

    // The files in [folder] once there are [count] (written whole).
    private fun waitForFiles(folder: Path, count: Int): List<Path> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val files = Files.list(folder).use { it.toList() }
            if (files.size >= count) {
                Thread.sleep(200)
                return files.sortedBy { Files.getLastModifiedTime(it) }
            }
            Thread.sleep(20)
        }
        error("not $count files in $folder")
    }

    private fun image(file: Path): BufferedImage = ImageIO.read(file.toFile())

    private companion object {
        const val BACKGROUND = 0x203040
        const val GREEN = 0x00FF00
        const val RED = 0xFF0000
        const val BTN_LEFT = 272
        const val KEY_ENTER = 28
        const val KEY_ESC = 1
    }
}
