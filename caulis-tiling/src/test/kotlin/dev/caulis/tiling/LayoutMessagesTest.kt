package dev.caulis.tiling

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Layout options and messages (Hyprland's layoutmsg): master, tree and columns. */
class LayoutMessagesTest {

    private class W(override val title: String) : LayoutWindow {
        override val appId = ""
        override val minWidth = 0
        override val minHeight = 0
        override val maxWidth = 0
        override val maxHeight = 0
        override fun toString() = title
    }

    private val screen = Rect(0, 0, 1000, 600)
    private val noGaps = Gaps(0, 0)
    private val a = W("a")
    private val b = W("b")
    private val c = W("c")
    private val all = listOf(a, b, c)

    private fun message(text: String) = TilingCommand.Message.of(text)

    @Test
    fun `master - ratio, side, more masters`() {
        val model = MasterLayout(noGaps, ratio = 0.5).createModel()
        var r = model.arrange(all, screen, a)
        assertEquals(Rect(0, 0, 500, 600), r[0], "a is the master, on the left")
        assertEquals(Rect(500, 0, 500, 300), r[1])

        model.command(message("ratio +0.1"), a)
        r = model.arrange(all, screen, a)
        assertEquals(600, r[0].width)

        model.command(message("side right"), a)
        r = model.arrange(all, screen, a)
        assertEquals(400, r[0].x, "on the right now")

        model.command(message("masters +1"), a)
        r = model.arrange(all, screen, a)
        assertEquals(Rect(400, 0, 600, 300), r[0], "two masters on top of each other")
        assertEquals(Rect(400, 300, 600, 300), r[1])
        assertEquals(Rect(0, 0, 400, 600), r[2], "the stack is c alone")

        assertFailsWith<IllegalArgumentException> { model.command(message("side up"), a) }
        assertFailsWith<IllegalArgumentException> { model.command(message("ratio lots"), a) }
    }

    @Test
    fun `master - swap with the master, focus it, cycle, and where new windows go`() {
        val model = MasterLayout(noGaps).createModel()
        model.arrange(all, screen, c)
        assertTrue(model.command(message("swap-with-master"), c))
        assertSame(c, model.takeFocus())
        var r = model.arrange(all, screen, c)
        assertEquals(0, r[2].x, "c is the master")

        model.command(message("focus-master"), b)
        assertSame(c, model.takeFocus())

        model.command(message("cycle next"), c)
        r = model.arrange(all, screen, c)
        assertEquals(0, r[0].x, "every window moved one place on (c, b, a → a, c, b): a is the master")

        val top = MasterLayout(noGaps, newWindow = NewWindow.MASTER).createModel()
        top.arrange(listOf(a), screen, a)
        r = top.arrange(listOf(a, b), screen, a)
        assertEquals(0, r[1].x, "the new window is the master")
    }

    @Test
    fun `tree - toggle and swap a split, its ratio, the new split's ratio`() {
        val model = BinaryTree(noGaps, splitRatio = 0.6).createModel()
        model.arrange(listOf(a), screen, a)
        var r = model.arrange(listOf(a, b), screen, a)
        assertEquals(600, r[0].width, "the old window keeps 0.6")

        model.command(message("toggle-split"), a)
        r = model.arrange(listOf(a, b), screen, a)
        assertEquals(Rect(0, 0, 1000, 360), r[0], "on top of each other now")

        model.command(message("swap-split"), a)
        r = model.arrange(listOf(a, b), screen, a)
        assertEquals(0, r[1].y, "b is on top")
        assertEquals(360, r[0].height, "a kept its size")

        model.command(message("ratio 0.5"), a)
        r = model.arrange(listOf(a, b), screen, a)
        assertEquals(300, r[0].height)
    }

    @Test
    fun `columns - width and centering`() {
        val model = ScrollingColumns(noGaps, newWidth = 0.4).createModel()
        model.arrange(all, screen, a)
        model.command(message("width 0.8"), c)
        var r = model.arrange(all, screen, c)
        assertEquals(800, r[2].width)
        model.command(message("width -0.3"), c)
        r = model.arrange(all, screen, c)
        assertEquals(500, r[2].width)

        model.command(message("center"), b)
        r = model.arrange(all, screen, b)
        assertEquals(500, r[1].x + r[1].width / 2, "b in the middle")

        val centered = ScrollingColumns(noGaps, newWidth = 0.4, centerFocused = true).createModel()
        r = centered.arrange(all, screen, b)
        assertEquals(500, r[1].x + r[1].width / 2, "always in the middle")
    }
}
