package dev.caulis.screenshot

import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.wm.Caulis
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The screenshots' arithmetic and files, without a compositor: pixels, pictures put together, names, formats, keys. */
class PicturesTest {

    @Test
    fun `a part in desktop units becomes the right pixels on a monitor with a fractional scale, beside another`() {
        // The second of two monitors: 2560x1440 at 1.25 (2048x1152 units), right of a 1920 wide one.
        val area = Rect(1920, 0, 2048, 1152)
        fun pixels(r: Rect) = Frozen.toPixels(r, area, 1.25, 2560, 1440)
        assertEquals(Rect(125, 125, 250, 188), pixels(Rect(2020, 100, 200, 150)), "rounded at each edge (no gaps between neighbouring parts)")
        // Two parts side by side have no gap and no overlap between them.
        val left = pixels(Rect(2021, 101, 33, 20))
        val right = pixels(Rect(2054, 101, 33, 20))
        assertEquals(left.right, right.x)
        // Never outside the picture, at least one pixel.
        assertEquals(Rect(2550, 1430, 10, 10), pixels(Rect(3960, 1144, 100, 100)))
        assertEquals(Rect(0, 0, 1, 1), pixels(Rect(1000, -50, 10, 10)))
    }

    @Test
    fun `a picture put together from windows is as big as the most shrunk window needs, within its limits`() {
        val part = Rect(0, 0, 1000, 500)
        assertEquals(2.0, Screenshots.plan(part, 1.0, 2.0, 32_000_000), "the window's own size")
        assertEquals(1.5, Screenshots.plan(part, 1.5, 1.0, 32_000_000), "at least the monitor's scale")
        assertEquals(8.0, Screenshots.plan(Rect(0, 0, 100, 100), 1.0, 20.0, 32_000_000), "at most 8")
        assertEquals(16384.0 / 4000, Screenshots.plan(Rect(0, 0, 4000, 100), 1.0, 8.0, 32_000_000), "at most 16384 pixels across")
        val capped = Screenshots.plan(part, 1.0, 8.0, 2_000_000)
        assertTrue(part.width * capped * part.height * capped <= 2_000_001, "at most maxPixels: $capped")
    }

    @Test
    fun `windows are put in their places at their size, their corners cut, a hidden one black`() {
        val red = Pixels(40, 20, IntArray(40 * 20) { RED }, transparent = true)
        val green = Pixels(20, 20, IntArray(20 * 20) { GREEN }, transparent = true)
        val picture = Composite(
            100, 60,
            listOf(
                Composite.Piece(red, 10.0, 10.0, 40.0, 20.0, 0.0),
                // Drawn at twice its size, round corners.
                Composite.Piece(green, 50.0, 10.0, 40.0, 40.0, 8.0),
                // Hidden from screenshots.
                Composite.Piece(null, 10.0, 40.0, 30.0, 15.0, 0.0),
            ),
            background = null,
        ).draw()
        assertEquals(100, picture.width)
        assertEquals(60, picture.height)
        assertTrue(picture.transparent)
        fun at(x: Int, y: Int) = picture.argb[y * picture.width + x]
        assertEquals(RED, at(10, 10))
        assertEquals(RED, at(49, 29))
        assertEquals(0, at(9, 10) ushr 24, "see-through beside it")
        assertEquals(GREEN, at(70, 30), "the middle of the one made bigger")
        assertEquals(GREEN, at(89, 30))
        assertEquals(0, at(50, 10) ushr 24, "its corner cut away")
        assertEquals(0xFF000000.toInt(), at(20, 45), "black where the hidden one is")
        // With a background: nothing see-through.
        val behind = Composite(20, 20, listOf(Composite.Piece(green, 5.0, 5.0, 10.0, 10.0, 0.0)), dev.caulis.render.Color(0f, 0f, 1f)).draw()
        assertEquals(0xFF0000FF.toInt(), behind.argb[0])
        assertEquals(false, behind.transparent)
    }

    @Test
    fun `PNG keeps the picture as it is, JPEG puts see-through parts on white, and names are parsed`() {
        val half = 0x80000000.toInt() // (premultiplied: black at half)
        val pixels = Pixels(2, 1, intArrayOf(RED, half), transparent = true)
        val png = ImageIO.read(ByteArrayInputStream(pixels.encode(ShotFormat.PNG, 0.9f)))
        assertEquals(2, png.width)
        assertEquals(RED, png.getRGB(0, 0))
        assertEquals(0x80, png.getRGB(1, 0) ushr 24, "see-through kept")
        // (All of it the same: JPEG mixes the colours of pixels side by side.)
        val greyPixels = Pixels(16, 16, IntArray(256) { half }, transparent = true)
        val jpeg = ImageIO.read(ByteArrayInputStream(greyPixels.encode(ShotFormat.JPEG, 1f)))
        assertEquals(16, jpeg.width)
        val grey = jpeg.getRGB(8, 8) and 0xFF
        assertTrue(grey in 110..145, "black at half over white: grey ($grey)")
        // An opaque picture: what is in the alpha does not matter.
        val opaque = Pixels(1, 1, intArrayOf(0x0000FF00), transparent = false)
        assertEquals(0xFF00FF00.toInt(), ImageIO.read(ByteArrayInputStream(opaque.png())).getRGB(0, 0))

        assertEquals(ShotFormat.PNG, ShotFormat.parse(" PNG"))
        assertEquals(ShotFormat.JPEG, ShotFormat.parse("jpg "))
        assertEquals(ShotFormat.JPEG, ShotFormat.parse("jpeg"))
        assertNull(ShotFormat.parse("webp"))
    }

    @Test
    fun `files get the configured name and never replace one there`() {
        val folder = Files.createTempDirectory("screenshots")
        try {
            val shots = Screenshots(Caulis())
            shots.folder = folder
            shots.fileName = "{kind} {date} {time}/x"
            val at = LocalDateTime.of(2026, 10, 26, 14, 3, 22)
            assertEquals(folder.resolve("region 2026-10-26 14-03-22-x.png"), shots.fileFor(at, ShotKind.REGION))
            shots.format = ShotFormat.JPEG
            shots.fileName = " "
            assertEquals(folder.resolve("Screenshot from 2026-10-26 14-03-22.jpg"), shots.fileFor(at, ShotKind.WINDOWS))

            val file = folder.resolve("shot.png")
            val written = (1..3).map { Screenshots.writeNew(file, byteArrayOf(it.toByte())) }
            assertEquals(listOf("shot.png", "shot (2).png", "shot (3).png"), written.map { it.fileName.toString() })
            assertEquals(1, Files.readAllBytes(file)[0].toInt(), "the first one kept")
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the chooser's keys are found by what the layout has on them`() {
        val log = Logger("screenshot")
        val us = KeyNames.of("us", "", "", "pc105", log)
        assertEquals("a", us[30])
        assertEquals("Return", us[28])
        assertEquals("Escape", us[1])
        // AZERTY: A is where Q is on a US keyboard.
        assertEquals("a", KeyNames.of("fr", "", "", "pc105", log)[16])
        // Dvorak: S is where ; is.
        assertEquals("s", KeyNames.of("us", "dvorak", "", "pc105", log)[39])
        // A Russian layout first: the Latin letters of the next one.
        assertEquals("w", KeyNames.of("ru,us", "", "", "pc105", log)[17])

        val keys = ChooserKeys()
        assertTrue(keys.capture.has("Return"))
        assertTrue(keys.area.has("A"))
        assertTrue(!keys.close.has("escape"), "names of more than one letter as xkb writes them")
        assertTrue(!keys.capture.has(null))
        assertEquals("Enter", ScreenshotWords().keyName("Return"))
        assertEquals("W", ScreenshotWords().keyName("w"))
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
    }
}
