package dev.caulis.core.appearance

import dev.caulis.render.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Palettes: a wallpaper's colour found, roles that read well, codes that give it back. */
class PaletteTest {

    @Test
    fun `a blue wallpaper gives a blue seed`() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 64) for (x in 0 until 64) image.setRGB(x, y, if (x < 48) 0x2a5da8 else 0xeeeeee)
        val seed = Palette.seedOf(image)
        assertTrue(seed.blue > seed.red && seed.blue > seed.green, "$seed")
    }

    @Test
    fun `text reads on surfaces, in both looks`() {
        for (style in PaletteStyle.entries) for (dark in listOf(true, false)) {
            val s = Palette(Color.hex("#c0392b"), style).scheme(dark)
            val contrast = contrast(s.onSurface, s.surface)
            assertTrue(contrast > 7.0, "$style dark=$dark: ${"%.1f".format(contrast)}")
            assertTrue(contrast(s.onPrimary, s.primary) > 3.0, "$style dark=$dark primary")
        }
    }

    @Test
    fun `codes give the palette again`() {
        val p = Palette(Color.hex("#5b8cc8"), PaletteStyle.VIBRANT)
        assertEquals("caulis-palette:#5b8cc8:vibrant", p.code)
        assertEquals(p, Palette.parse(p.code))
        assertEquals(PaletteStyle.TONAL_SPOT, Palette.parse("#5b8cc8").style)
        assertTrue(Palette(Color.hex("#5b8cc8")).scheme(true).toCss().contains("@define-color primary #"))
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
