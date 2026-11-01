package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.render.Color
import java.awt.Graphics2D

/** How serious a [Notice] is. */
enum class NoticeLevel { INFO, WARNING, ERROR }

/**
 * A message of the compositor itself on screen: a mistake in the configuration, something
 * that does not work, or whatever your code tells the user with [Desktop.notify]. Clicking
 * it closes it.
 */
class Notice internal constructor(
    val level: NoticeLevel,
    /** A first line in bold, or null. */
    val title: String?,
    val text: String,
    /** The monitor it is shown on (null: every monitor, see [NoticeStyle.everyMonitor]). */
    val monitorName: String?,
) {
    /** How many times it came: the same message again is counted, not shown twice. */
    var count = 1
        internal set

    internal var shownSince = System.nanoTime()
    internal var leavingSince: Long? = null
    internal var timer: EventLoop.Timer? = null

    /** False once it was closed (clicked, timed out, or by [Desktop.dismiss]). */
    val open: Boolean get() = leavingSince == null

    /** Its text was copied (its copy button shows a check). */
    var copied = false
        internal set

    /** What its copy button copies: the title and the text. */
    val fullText: String get() = listOfNotNull(title, text).joinToString("\n")

    override fun toString() = "Notice($level, ${title?.let { "$it: " } ?: ""}$text${if (count > 1) " ×$count" else ""})"
}

/** The colours of one [NoticeLevel]. */
data class NoticeColors(val background: Color, val text: Color)

/** What a notice shows right now (for a [NoticePainter]). Sizes in pixels. */
class NoticeLook(
    val width: Int,
    /** 0 while [NoticePainter.height] asks how high it is. */
    val height: Int,
    /** Pixels per desktop unit (to scale fonts and lines). */
    val scale: Double,
    val notice: Notice,
    /** The pointer is on it (a click closes it; its copy button copies it). */
    val hovered: Boolean,
    val style: NoticeStyle,
) {
    /** True for a strip across the monitor, false for a card. */
    val banner: Boolean get() = style.placement is NoticePlacement.Banner
}

/** Draws a notice into a [NoticeLook.width] x [NoticeLook.height] picture (see-through where nothing is drawn). */
interface NoticePainter {
    /** How high (in pixels) [look] is at its width; [g] is there to measure text. */
    fun height(g: Graphics2D, look: NoticeLook): Int = DefaultNoticePainter.height(g, look)

    fun paint(g: Graphics2D, look: NoticeLook)

    /** The button of [look] at ([x], [y]) (pixels in it), if it draws one there; null: a click closes the notice. */
    fun actionAt(look: NoticeLook, x: Double, y: Double): NoticeAction? = null
}

/** What a notice's button does. */
enum class NoticeAction {
    /** Its text goes to the clipboard (to send it to someone). */
    COPY,

    /** It closes. */
    CLOSE,
}

// [curve] at [t]; a curve that fails (the user's own) is a straight line.
internal fun curveAt(curve: Curve, t: Double): Double = try {
    curve.at(t)
} catch (e: Exception) {
    dev.caulis.log.Logger("notices").warn("Your curve of the notices failed (a straight line instead): $e")
    t
}
