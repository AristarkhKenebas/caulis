package dev.caulis.tiling

import dev.caulis.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RememberingLayoutsTest {

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

    // --- trees ---------------------------------------------------------------------------

    @Test
    fun `a tree splits the focused window along its longer side`() {
        val tree = BinaryTree(noGaps).createModel()
        tree.arrange(listOf(a), screen, a)
        val two = tree.arrange(listOf(a, b), screen, a)
        assertEquals(Rect(0, 0, 500, 600), two[0], "wide screen: side by side")
        val three = tree.arrange(listOf(a, b, c), screen, b)
        assertEquals(Rect(500, 0, 500, 300), three[1], "b was tall: split on top of each other")
        assertEquals(Rect(500, 300, 500, 300), three[2])
        assertEquals(Rect(0, 0, 500, 600), three[0], "a did not move")
    }

    @Test
    fun `preselect puts the next window on the chosen side`() {
        val tree = BinaryTree(noGaps).createModel()
        tree.arrange(listOf(a), screen, a)
        tree.command(TilingCommand.Preselect(Direction.LEFT), a)
        val r = tree.arrange(listOf(a, b), screen, a)
        assertEquals(0, r[1].x, "b opened left of a")
        assertEquals(500, r[0].x)
    }

    @Test
    fun `closing a window gives its space to its neighbour`() {
        val tree = BinaryTree(noGaps).createModel()
        tree.arrange(listOf(a), screen, a)
        tree.arrange(listOf(a, b), screen, a)
        tree.arrange(listOf(a, b, c), screen, b)
        val r = tree.arrange(listOf(a, b), screen, a)
        assertEquals(Rect(500, 0, 500, 600), r[1], "b has the whole right half again")
    }

    @Test
    fun `move swaps, resize moves the line, balance evens out, rotate turns`() {
        val tree = BinaryTree(noGaps).createModel()
        tree.arrange(listOf(a), screen, a)
        tree.arrange(listOf(a, b), screen, a)
        assertTrue(tree.command(TilingCommand.Move(Direction.RIGHT), a))
        assertEquals(500, tree.arrange(listOf(a, b), screen, a)[0].x, "a and b swapped")
        assertTrue(tree.command(TilingCommand.Resize(Direction.LEFT, 0.1), a))
        assertEquals(400, tree.arrange(listOf(a, b), screen, a)[0].x, "a grew to the left")
        tree.command(TilingCommand.Balance, a)
        assertEquals(500, tree.arrange(listOf(a, b), screen, a)[0].x)
        tree.command(TilingCommand.Rotate, a)
        assertEquals(300, tree.arrange(listOf(a, b), screen, a)[0].y, "now on top of each other")
    }

    @Test
    fun `manual tiling - you choose the split`() {
        val tree = ManualTiling(noGaps).createModel()
        tree.arrange(listOf(a), screen, a)
        tree.command(TilingCommand.SplitNext(sideBySide = false), a)
        val r = tree.arrange(listOf(a, b), screen, a)
        assertEquals(Rect(0, 300, 1000, 300), r[1], "b below a, though the screen is wide")
    }

    // --- columns -------------------------------------------------------------------------

    @Test
    fun `columns - new windows add columns, consume stacks them`() {
        val columns = ScrollingColumns(noGaps).createModel()
        columns.arrange(listOf(a), screen, a)
        columns.arrange(listOf(a, b), screen, a)
        val three = columns.arrange(listOf(a, b, c), screen, b)
        assertTrue(three[2].x > three[1].x, "c opened right of the focused b")
        assertTrue(columns.command(TilingCommand.Consume(Direction.LEFT), c))
        val stacked = columns.arrange(listOf(a, b, c), screen, c)
        assertEquals(stacked[1].x, stacked[2].x, "c is now in b's column")
        assertTrue(stacked[2].y > stacked[1].y)
        assertTrue(columns.command(TilingCommand.Consume(Direction.RIGHT), c))
        val out = columns.arrange(listOf(a, b, c), screen, c)
        assertTrue(out[2].x > out[1].x, "and out again into its own column")
    }

    @Test
    fun `columns - widths cycle`() {
        val columns = ScrollingColumns(noGaps).createModel()
        assertEquals(500, columns.arrange(listOf(a), screen, a)[0].width)
        columns.command(TilingCommand.CycleWidth, a)
        assertEquals(666, columns.arrange(listOf(a), screen, a)[0].width)
    }

    // --- canvas --------------------------------------------------------------------------

    @Test
    fun `canvas - windows take free cells and the view follows the focus`() {
        val canvas = Canvas(noGaps, columns = 2, rows = 2, windowColumns = 1, windowRows = 2).createModel()
        canvas.arrange(listOf(a), screen, a)
        val two = canvas.arrange(listOf(a, b), screen, a)
        assertEquals(Rect(0, 0, 500, 600), two[0])
        assertEquals(Rect(500, 0, 500, 600), two[1], "b in the free cell right of a")
        val three = canvas.arrange(listOf(a, b, c), screen, b)
        assertTrue(three[2].x >= 1000, "c right of b, off screen for now")
        val focusC = canvas.arrange(listOf(a, b, c), screen, c)
        assertEquals(Rect(500, 0, 500, 600), focusC[2], "focusing c brought it into view")
        assertTrue(focusC[0].right <= 0, "a scrolled out on the left")
    }

    @Test
    fun `canvas - panning stays where you put it`() {
        val canvas = Canvas(noGaps).createModel()
        canvas.arrange(listOf(a), screen, a)
        canvas.command(TilingCommand.Pan(Direction.RIGHT), a)
        canvas.command(TilingCommand.Pan(Direction.RIGHT), a)
        val r = canvas.arrange(listOf(a), screen, a)
        assertTrue(r[0].right <= 0, "the view moved away from a and stays there: ${r[0]}")
    }

    @Test
    fun `canvas - closed windows leave their cells, move and resize work in cells`() {
        val canvas = Canvas(noGaps).createModel()
        canvas.arrange(listOf(a), screen, a)
        canvas.arrange(listOf(a, b), screen, a)
        val r = canvas.arrange(listOf(b), screen, b)
        assertEquals(500, r[0].x, "b stays in its cell (a canvas does not squeeze)")
        canvas.command(TilingCommand.Resize(Direction.LEFT, 1.0), b)
        assertEquals(Rect(0, 0, 1000, 600), canvas.arrange(listOf(b), screen, b)[0], "one cell wider to the left")
    }

    @Test
    fun `canvas - moving a window by a cell, swapping with whoever is there`() {
        val canvas = Canvas(noGaps, columns = 2, rows = 2, windowColumns = 1, windowRows = 1).createModel()
        canvas.arrange(listOf(a), screen, a)
        canvas.arrange(listOf(a, b), screen, a)
        val before = canvas.arrange(listOf(a, b), screen, a)
        assertEquals(Rect(0, 0, 500, 300), before[0])
        assertEquals(Rect(500, 0, 500, 300), before[1])
        canvas.command(TilingCommand.Move(Direction.DOWN), a)
        assertEquals(Rect(0, 300, 500, 300), canvas.arrange(listOf(a, b), screen, a)[0], "a one cell down")
        canvas.command(TilingCommand.Move(Direction.UP), a)
        canvas.command(TilingCommand.Move(Direction.RIGHT), a)
        val swapped = canvas.arrange(listOf(a, b), screen, a)
        assertEquals(Rect(500, 0, 500, 300), swapped[0], "a moved right")
        assertEquals(Rect(0, 0, 500, 300), swapped[1], "b took a's old cell")
    }

    @Test
    fun `canvas - a minimized window keeps its cell and comes back to it`() {
        val canvas = Canvas(noGaps).createModel()
        canvas.arrange(listOf(a), screen, a)
        val before = canvas.arrange(listOf(a, b), screen, a)
        canvas.arrange(listOf(a), screen, a) // b minimized
        val withC = canvas.arrange(listOf(a, c), screen, a)
        assertTrue(withC[1] != before[1], "c does not take b's cell while b is away")
        val back = canvas.arrange(listOf(a, b, c), screen, a)
        assertEquals(before[1], back[1], "b is back in its own cell")
        // Closed for good: then its cell is free.
        canvas.forget(b)
        val d = W("d")
        val withD = canvas.arrange(listOf(a, c, d), screen, a)
        assertEquals(before[1], withD[2], "d gets the cell b left")
    }
}
