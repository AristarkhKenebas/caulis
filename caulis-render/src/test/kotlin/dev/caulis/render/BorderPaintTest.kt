package dev.caulis.render

import dev.caulis.geometry.Rect
import dev.caulis.render.gles.GlesRenderer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Border paints: Hyprland's words, the gradient's angle, and shaders with their uniforms (on the GPU, skipped without one). */
class BorderPaintTest {

    @Test
    fun `Hyprland's words`() {
        val paint = Paint.parse("rgba(33ccffee) rgba(00ff99ee) 45deg") as Paint.Gradient
        assertEquals(listOf(Color.hex("#33ccffee"), Color.hex("#00ff99ee")), paint.colors)
        assertEquals(45.0, paint.degrees)
        assertEquals(Paint.Solid(Color.hex("#595959aa")), Paint.parse("rgba(595959aa)"))
        assertEquals(Paint.Solid(Color.hex("#ff0000")), Paint.parse("rgb(ff0000)"))
        assertEquals(Paint.Solid(Color.hex("#11223344")), Paint.parse("0x44112233"), "alpha first")
        assertEquals(Paint.Solid(Color(1f, 0f, 0f, 0.5f)), Paint.parse("rgba(255, 0, 0, 0.5)"))
        assertEquals(90.0, (Paint.parse("#ff0000 #0000ff 90deg") as Paint.Gradient).degrees)
        assertFailsWith<IllegalArgumentException> { Paint.parse("rgba(nope)") }
        assertFailsWith<IllegalArgumentException> { Paint.parse("45deg") }
        // As Hyprland: no angle is 0deg; angles are numbers; a bad #colour says so plainly.
        assertEquals(0.0, (Paint.parse("#ff0000 #0000ff") as Paint.Gradient).degrees)
        assertFailsWith<IllegalArgumentException> { Paint.parse("#ff0000 #0000ff NaNdeg") }
        assertFailsWith<IllegalArgumentException> { Paint.parse("#ff0000 #0000ff Infinitydeg") }
        assertEquals("\"#ff00\" is not a colour", assertFailsWith<IllegalArgumentException> { Paint.parse("#ff00") }.message)
    }

    @Test
    fun `the gradient's angle`() {
        val red = Color(1f, 0f, 0f)
        val blue = Color(0f, 0f, 1f)
        // 0: left to right; 90: top to bottom; 45: top left to bottom right; 180: right to left.
        Paint.Gradient(listOf(red, blue), 0.0).let { assertColor(red, it.colorAt(0.0, 0.5)); assertColor(blue, it.colorAt(1.0, 0.5)) }
        Paint.Gradient(listOf(red, blue), 90.0).let { assertColor(red, it.colorAt(0.5, 0.0)); assertColor(blue, it.colorAt(0.5, 1.0)) }
        Paint.Gradient(listOf(red, blue), 45.0).let {
            assertColor(red, it.colorAt(0.0, 0.0))
            assertColor(blue, it.colorAt(1.0, 1.0))
            assertNear(0.5f, it.colorAt(1.0, 0.0).red, "the other corners are half way")
        }
        Paint.Gradient(listOf(red, blue), 180.0).let { assertColor(blue, it.colorAt(0.0, 0.5)); assertColor(red, it.colorAt(1.0, 0.5)) }
        // Turning a quarter a second: after a second, what 0 degrees was is at 90.
        val turning = Paint.Gradient(listOf(red, blue), 0.0, turnsPerSecond = 0.25)
        assertColor(red, turning.colorAt(0.5, 0.0, seconds = 1.0))
    }

    @Test
    fun `a gradient border on the GPU has its colours at the corners`() = draw({ renderer ->
        assertTrue(renderer.drawRoundedRect(Rect(0, 0, SIZE, SIZE), 0f, Paint.parse("rgba(ff0000ff) rgba(0000ffff) 45deg"), borderWidth = 4f))
    }) {
        val gradient = Paint.parse("rgba(ff0000ff) rgba(0000ffff) 45deg") as Paint.Gradient
        for ((x, y) in listOf(0 to 0, SIZE - 1 to SIZE - 1, SIZE - 1 to 0, 0 to SIZE - 1)) {
            val expected = gradient.colorAt((x + 0.5) / SIZE, (y + 0.5) / SIZE)
            val got = pixel(x, y)
            assertNear(expected.red, red(got) / 255f, "red at $x,$y (${hex(got)})")
            assertNear(expected.blue, blue(got) / 255f, "blue at $x,$y (${hex(got)})")
        }
        assertEquals(BLACK, pixel(SIZE / 2, SIZE / 2), "nothing inside the frame")
    }

    @Test
    fun `a shader gets its uniforms`() = draw({ renderer ->
        // Green where everything it was told is right, red where not.
        val code = """
            vec4 paint(vec2 at) {
                bool ok = focused == 1.0 && urgent == 1.0 && grouped == 0.0 && colorCount == 2
                    && colors[1] == vec4(0.0, 0.0, 1.0, 1.0) && abs(borderWidth - 6.0) < 0.01 && abs(cornerRadius - 0.0) < 0.01
                    && size == vec2(64.0, 64.0) && pixel.x >= 0.0 && pixel.x <= 64.0 && progress >= 0.0 && progress <= 1.0;
                return ok ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
            }
        """.trimIndent()
        val paint = Paint.Shader({ code }, "uniforms.glsl").withColors(Color(1f, 1f, 1f), Color(0f, 0f, 1f))
        assertTrue(renderer.drawRoundedRect(Rect(0, 0, SIZE, SIZE), 0f, paint, 6f, 1f, 0f, PaintState(focused = true, urgent = true)))
    }) {
        assertEquals(GREEN, pixel(SIZE / 2, 2), "the top edge")
        assertEquals(GREEN, pixel(2, SIZE / 2), "the left edge")
    }

    @Test
    fun `progress goes round the border clockwise from the top left`() = draw({ renderer ->
        val code = "vec4 paint(vec2 at) { return vec4(progress, 0.0, 0.0, 1.0); }"
        assertTrue(renderer.drawRoundedRect(Rect(0, 0, SIZE, SIZE), 0f, Paint.shader(code), borderWidth = 2f))
    }) {
        assertNear(0.125f, red(pixel(SIZE / 2, 0)) / 255f, "half way along the top: an eighth of the way")
        assertNear(0.375f, red(pixel(SIZE - 1, SIZE / 2)) / 255f, "half way down the right")
        assertNear(0.625f, red(pixel(SIZE / 2, SIZE - 1)) / 255f, "half way along the bottom")
        assertNear(0.875f, red(pixel(0, SIZE / 2)) / 255f, "half way up the left")
    }

    @Test
    fun `a shader that does not compile draws nothing and says why with its line`() = draw({ renderer ->
        val code = "vec4 paint(vec2 at) {\n  return nope;\n}"
        assertFalse(renderer.drawRoundedRect(Rect(0, 0, SIZE, SIZE), 0f, Paint.shader(code), 4f, 1f, 0f, PaintState.NONE))
        val problem = assertNotNull(renderer.effectProblem(code))
        // Not compiled again every frame: the failure is kept until the code changes.
        val compiled = (renderer as GlesRenderer).compilations
        assertFalse(renderer.drawRoundedRect(Rect(0, 0, SIZE, SIZE), 0f, Paint.shader(code), 4f, 1f, 0f, PaintState.NONE))
        assertEquals(compiled, renderer.compilations, "the broken shader was not compiled again")
        val changed = code.replace("nope", "nope2")
        assertFalse(renderer.drawRoundedRect(Rect(0, 0, SIZE, SIZE), 0f, Paint.shader(changed), 4f, 1f, 0f, PaintState.NONE))
        assertEquals(compiled + 1, renderer.compilations, "changed code is compiled")
        assertTrue(Regex("""\b0:2\(""").containsMatchIn(problem) || "2:" in problem, "the line is said: $problem")
        // The fallback the desktop draws instead: its first colour.
        assertEquals(Color(1f, 1f, 1f), Paint.Shader({ code }).firstColor())
        assertEquals(Color(0f, 1f, 0f), Paint.Shader({ code }).withColors(Color(0f, 1f, 0f)).firstColor())
    }) {
        assertEquals(BLACK, pixel(1, 1), "nothing drawn by it")
    }

    private fun assertColor(expected: Color, actual: Color) =
        assertTrue(abs(expected.red - actual.red) + abs(expected.green - actual.green) + abs(expected.blue - actual.blue) + abs(expected.alpha - actual.alpha) < 0.01f, "$expected expected, $actual")

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 0.06f, "$what: $expected expected, $actual")

    private lateinit var frame: MemorySegment

    private fun pixel(x: Int, y: Int) = frame.get(JAVA_INT, (y * SIZE.toLong() + x) * 4)
    private fun red(pixel: Int) = (pixel shr 16) and 0xff
    private fun blue(pixel: Int) = pixel and 0xff
    private fun hex(pixel: Int) = Integer.toHexString(pixel)

    // Draws [scene] on a black SIZE x SIZE target, reads it back, runs [checks].
    private fun draw(scene: (Renderer) -> Unit, checks: () -> Unit) {
        assumeGpu()
        GlesRenderer.create().use { renderer ->
            renderer.createTarget(SIZE, SIZE).use { target ->
                Arena.ofConfined().use { arena ->
                    renderer.begin(target, 0f, 0f, 0f)
                    frame = arena.allocate(SIZE.toLong() * SIZE * 4)
                    scene(renderer)
                    target.readPixels(frame, SIZE * 4)
                    checks()
                }
            }
        }
    }

    private companion object {
        const val SIZE = 64
        const val BLACK = 0xFF000000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
    }
}
