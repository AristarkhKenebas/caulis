package dev.caulis.core.cursor

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading Xcursor files and finding cursors in themes. */
class CursorThemeTest {

    // An image in a cursor file: nominal size, side, hotspot, frame delay, colour.
    private class Image(val nominal: Int, val side: Int, val hot: Int = 1, val delay: Int = 0, val color: Int = 0xFF000000.toInt())

    // Builds an Xcursor file the way themes store them.
    private fun xcursor(vararg images: Image): ByteArray {
        val header = 16 + images.size * 12
        val total = header + images.sumOf { 36 + it.side * it.side * 4 }
        val data = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0x72756358).putInt(16).putInt(0x10000).putInt(images.size)
        var position = header
        for (image in images) {
            data.putInt(0xfffd0002.toInt()).putInt(image.nominal).putInt(position)
            position += 36 + image.side * image.side * 4
        }
        for (image in images) {
            data.putInt(36).putInt(0xfffd0002.toInt()).putInt(image.nominal).putInt(1)
                .putInt(image.side).putInt(image.side).putInt(image.hot).putInt(image.hot).putInt(image.delay)
            repeat(image.side * image.side) { data.putInt(image.color) }
        }
        return data.array()
    }

    @Test
    fun `the size nearest to the wanted one, with all its frames`() {
        val file = xcursor(Image(24, 24, delay = 50), Image(24, 24, delay = 50), Image(48, 48, hot = 4))
        val small = XCursor.read(file, 30)!!
        assertEquals(2, small.size, "two frames at 24")
        assertEquals(24, small[0].width)
        val big = XCursor.read(file, 40)!!
        assertEquals(1, big.size)
        assertEquals(48, big[0].nominalSize)
        assertEquals(4, big[0].hotspotX)
        assertEquals(0xFF000000.toInt(), big[0].pixels[0])
    }

    @Test
    fun `broken files are refused, not crashed on`() {
        val good = xcursor(Image(24, 24))
        assertNull(XCursor.read(good.copyOf(good.size - 10), 24), "cut short")
        assertNull(XCursor.read(ByteArray(64), 24), "no Xcur header")
        assertNull(XCursor.read(ByteArray(0), 24), "empty")
        val huge = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16 + 12 + 16, 100_000) }
        assertNull(XCursor.read(huge, 24), "nonsense width")
    }

    @Test
    fun `animated cursors go round their frames`() {
        val frames = XCursor.read(xcursor(Image(24, 24, delay = 30, color = 1), Image(24, 24, delay = 70, color = 2)), 24)!!
        val cursor = Cursor("wait", frames)
        assertTrue(cursor.animated)
        assertEquals(1 to 30L, cursor.frameAt(0).let { it.first.pixels[0] to it.second })
        assertEquals(2 to 60L, cursor.frameAt(40).let { it.first.pixels[0] to it.second })
        assertEquals(1 to 25L, cursor.frameAt(105).let { it.first.pixels[0] to it.second }, "round again after 100 ms")
    }

    @Test
    fun `themes inherit, and old X11 names stand in for the CSS ones`() {
        val root = Files.createTempDirectory("cursor-themes")
        try {
            fun put(theme: String, name: String, bytes: ByteArray) {
                Files.createDirectories(root.resolve("$theme/cursors"))
                Files.write(root.resolve("$theme/cursors/$name"), bytes)
            }
            put("Mine", "xterm", xcursor(Image(24, 24, color = 7)))
            Files.writeString(root.resolve("Mine/index.theme"), "[Icon Theme]\nName=Mine\nInherits=Base\n")
            put("Base", "left_ptr", xcursor(Image(24, 24, color = 8)))

            val theme = CursorTheme.load("Mine", 24, listOf(root))
            assertTrue(theme.found)
            assertEquals(7, theme.cursor("text")!!.frames[0].pixels[0], "text = xterm, from the theme itself")
            assertEquals(8, theme.cursor("default")!!.frames[0].pixels[0], "default = left_ptr, from the inherited theme")
            assertNull(CursorTheme.load("Mine", 24, listOf(root)).cursor("zoom-in"), "nobody has it")
            assertTrue(!CursorTheme.load("Missing", 24, listOf(root)).found)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an installed theme has the usual cursors`() {
        val adwaita = CursorTheme.searchPath().firstOrNull { Files.isDirectory(it.resolve("Adwaita/cursors")) }
        assumeTrue(adwaita != null, "Adwaita is not installed")
        val theme = CursorTheme.load("Adwaita", 24)
        for (shape in listOf("default", "pointer", "text", "grabbing", "nwse-resize", "wait")) {
            assertNotNull(theme.cursor(shape), shape)
        }
        val missing = CursorTheme.SHAPES.filter { theme.cursor(it) == null }
        println("Adwaita lacks: $missing")
    }

}
