// Draws Caulis's own cursor themes ("Caulis", white, and "Caulis-Black") and writes them as
// Xcursor files into caulis-cursor's resources. Run after changing a cursor:
//
//     ./gradlew :tools:cursors:run
//
// Every cursor is a few shapes on a 24 x 24 grid (1 unit = 1 pixel at size 24), drawn at all
// sizes with a soft shadow. The pictures are made here, so they are Caulis's own (MPL-2.0).

package dev.caulis.tools.cursors

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Colours of a theme: [fill] and [line] for most cursors, swapped for the text-like ones. */
class Style(val name: String, val fill: Color, val line: Color, val comment: String)

val STYLES = listOf(
    Style("Caulis", Color.WHITE, Color.BLACK, "Sharp white cursors with a black outline"),
    Style("Caulis-Black", Color.BLACK, Color.WHITE, "Sharp black cursors with a white outline"),
)

val SIZES = listOf(16, 24, 32, 48, 64, 96)
val ACCENT = Color(0x2f, 0x80, 0xed) // the busy spinner
const val OUTLINE = 1.1 // outline width in units

/**
 * One shape of a cursor: filled, and outlined — half of the outline inside the shape, or all
 * of it outside ([outside], for thin shapes such as the text cursor, which would vanish).
 */
class Part(val shape: Shape, val fill: Color, val line: Color? = null, val lineWidth: Double = OUTLINE, val outside: Boolean = false)

/** A cursor: its parts (bottom first) and hotspot, in units; several frames if animated. */
class Design(val hotX: Double, val hotY: Double, val frames: List<List<Part>>, val delayMillis: Int = 0)

// --- shapes ---------------------------------------------------------------------------

fun polygon(vararg points: Double): Path2D.Double = Path2D.Double().apply {
    moveTo(points[0], points[1])
    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
    closePath()
}

fun Shape.moved(dx: Double, dy: Double): Shape = AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(this)
fun Shape.turned(degrees: Double, cx: Double, cy: Double): Shape =
    AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy).createTransformedShape(this)
fun Shape.scaled(factor: Double, cx: Double, cy: Double): Shape = AffineTransform().apply {
    translate(cx, cy); scale(factor, factor); translate(-cx, -cy)
}.createTransformedShape(this)

fun union(vararg shapes: Shape): Area = Area().apply { shapes.forEach { add(Area(it)) } }
fun round(x: Double, y: Double, w: Double, h: Double, r: Double) = RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2)
fun circle(cx: Double, cy: Double, r: Double) = Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2)
fun ring(cx: Double, cy: Double, outer: Double, inner: Double) = Area(circle(cx, cy, outer)).apply { subtract(Area(circle(cx, cy, inner))) }

/** A line drawn with a round pen, as a shape (so it can be filled and outlined). */
fun pen(width: Double, build: Path2D.Double.() -> Unit): Shape =
    BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(Path2D.Double().apply(build))

/** The classic sharp arrow, its tip at (x, y). */
fun arrow(x: Double = 2.0, y: Double = 1.5): Shape =
    polygon(0.0, 0.0, 0.0, 16.6, 3.9, 12.9, 6.7, 19.3, 9.3, 18.2, 6.5, 12.0, 11.8, 12.0).moved(x, y)

/** An arrow with a head at both ends, [length] long, across the centre at [degrees]. */
fun doubleArrow(degrees: Double, length: Double = 17.0, cx: Double = 12.0, cy: Double = 12.0): Shape {
    val h = length / 2
    val head = 4.6
    return polygon(
        -h, 0.0, -h + head, -3.9, -h + head, -1.15, h - head, -1.15, h - head, -3.9,
        h, 0.0, h - head, 3.9, h - head, 1.15, -h + head, 1.15, -h + head, 3.9,
    ).moved(cx, cy).turned(degrees, cx, cy)
}

/** The busy spinner: a track and a turning arc, [step] of 12. */
fun spinner(cx: Double, cy: Double, r: Double, step: Int, style: Style): List<Part> {
    val thick = r * 0.42
    val track = ring(cx, cy, r, r - thick)
    val arc = Area(java.awt.geom.Arc2D.Double(cx - r, cy - r, r * 2, r * 2, 90.0 - step * 30.0, -110.0, java.awt.geom.Arc2D.PIE))
    arc.intersect(Area(track))
    return listOf(Part(track, style.fill, style.line), Part(arc, ACCENT))
}

// A small square badge at the arrow's lower right, with a sign inside.
fun badge(style: Style, sign: Shape): List<Part> {
    val box = round(13.5, 13.5, 9.0, 9.0, 1.2)
    return listOf(Part(box, style.fill, style.line), Part(sign, style.line))
}

fun plusSign(cx: Double, cy: Double, arm: Double, w: Double) = union(Rectangle2D.Double(cx - arm, cy - w / 2, arm * 2, w), Rectangle2D.Double(cx - w / 2, cy - arm, w, arm * 2))

fun question(cx: Double, cy: Double, size: Double): Shape {
    val s = size / 8
    val hook = pen(1.9 * s) {
        moveTo(cx - 2.2 * s, cy - 1.8 * s)
        curveTo(cx - 2.2 * s, cy - 4.6 * s, cx + 2.4 * s, cy - 4.6 * s, cx + 2.4 * s, cy - 1.9 * s)
        curveTo(cx + 2.4 * s, cy - 0.2 * s, cx, cy - 0.1 * s, cx, cy + 1.5 * s)
    }
    return union(hook, circle(cx, cy + 3.7 * s, 1.1 * s))
}

fun noEntry(cx: Double, cy: Double, r: Double): Area {
    val sign = ring(cx, cy, r, r * 0.68)
    sign.add(Area(Rectangle2D.Double(cx - r * 0.82, cy - r * 0.17, r * 1.64, r * 0.34).turned(45.0, cx, cy)))
    return sign
}

// On whole pixels (x 11..12 at size 24), so the thin line stays sharp.
fun iBeam(): Shape = union(
    Rectangle2D.Double(11.0, 4.5, 1.0, 15.0),
    pen(1.0) { moveTo(8.8, 3.5); quadTo(11.5, 3.5, 11.5, 5.2); quadTo(11.5, 3.5, 14.2, 3.5) },
    pen(1.0) { moveTo(8.8, 20.5); quadTo(11.5, 20.5, 11.5, 18.8); quadTo(11.5, 20.5, 14.2, 20.5) },
)

// The pointing hand: index finger up, the others folded.
fun pointingHand(style: Style): List<Part> {
    val hand = union(
        round(6.4, 1.0, 3.8, 13.5, 1.9),            // index finger
        round(10.0, 7.6, 3.6, 7.0, 1.8),            // middle
        round(13.4, 8.4, 3.4, 6.6, 1.7),            // ring
        round(16.6, 9.4, 3.2, 6.0, 1.6),            // little finger
        round(6.4, 10.5, 13.4, 10.8, 3.4),          // palm
        round(2.2, 9.6, 3.6, 7.8, 1.8).turned(-28.0, 5.0, 15.0), // thumb
    )
    val gaps = pen(0.8) {
        moveTo(10.2, 9.0); lineTo(10.2, 12.6)
        moveTo(13.5, 9.6); lineTo(13.5, 13.2)
        moveTo(16.7, 10.6); lineTo(16.7, 13.8)
    }
    return listOf(Part(hand, style.fill, style.line), Part(gaps, style.line))
}

// The open hand (something can be grabbed) and the closed one (it is held).
fun openHand(style: Style): List<Part> {
    val hand = union(
        round(6.2, 3.4, 3.0, 11.0, 1.5),
        round(9.4, 2.0, 3.0, 12.0, 1.5),
        round(12.6, 2.8, 3.0, 11.0, 1.5),
        round(15.8, 5.0, 2.8, 9.0, 1.4),
        round(6.2, 10.6, 12.4, 10.6, 3.4),
        round(2.4, 9.4, 3.2, 7.4, 1.6).turned(-35.0, 5.4, 15.4),
    )
    val gaps = pen(0.8) {
        moveTo(9.3, 5.0); lineTo(9.3, 11.6)
        moveTo(12.5, 4.0); lineTo(12.5, 11.6)
        moveTo(15.7, 6.4); lineTo(15.7, 11.6)
    }
    return listOf(Part(hand, style.fill, style.line), Part(gaps, style.line))
}

fun closedHand(style: Style): List<Part> {
    val hand = union(
        round(6.2, 6.6, 3.0, 6.0, 1.5),
        round(9.4, 6.0, 3.0, 6.4, 1.5),
        round(12.6, 6.2, 3.0, 6.2, 1.5),
        round(15.8, 7.0, 2.8, 5.6, 1.4),
        round(6.2, 9.4, 12.4, 10.6, 3.4),
        round(3.2, 10.0, 3.2, 6.0, 1.6).turned(-20.0, 5.0, 14.0),
    )
    val gaps = pen(0.8) {
        moveTo(9.3, 7.6); lineTo(9.3, 10.6)
        moveTo(12.5, 7.4); lineTo(12.5, 10.6)
        moveTo(15.7, 8.2); lineTo(15.7, 10.6)
    }
    return listOf(Part(hand, style.fill, style.line), Part(gaps, style.line))
}

fun magnifier(style: Style, plus: Boolean): List<Part> {
    val lens = union(circle(10.0, 10.0, 6.6), round(14.0, 12.6, 8.4, 3.2, 1.6).turned(45.0, 14.0, 14.0))
    val sign = if (plus) plusSign(10.0, 10.0, 3.4, 1.4) else Rectangle2D.Double(6.6, 9.3, 6.8, 1.4)
    return listOf(Part(lens, style.fill, style.line), Part(sign, style.line))
}

// --- the cursors ----------------------------------------------------------------------

fun designs(style: Style): Map<String, Design> {
    val f = style.fill
    val l = style.line
    fun still(hotX: Double, hotY: Double, vararg parts: Part) = Design(hotX, hotY, listOf(parts.toList()))
    fun still(hotX: Double, hotY: Double, parts: List<Part>) = Design(hotX, hotY, listOf(parts))
    val pointer = Part(arrow(), f, l)
    // Text-like cursors are dark with a light outline (as on Windows), in either theme.
    val dark = if (style.fill == Color.WHITE) Color.BLACK else Color.WHITE
    val light = if (dark == Color.BLACK) Color.WHITE else Color.BLACK
    val resize = { degrees: Double -> still(12.0, 12.0, Part(doubleArrow(degrees), f, l)) }
    val split = { degrees: Double ->
        val bars = union(Rectangle2D.Double(10.0, 5.0, 1.4, 14.0), Rectangle2D.Double(12.6, 5.0, 1.4, 14.0)).turned(degrees, 12.0, 12.0)
        val arrows = Area(doubleArrow(degrees, 19.0)).apply { subtract(Area(Rectangle2D.Double(9.0, 0.0, 6.0, 24.0).turned(degrees, 12.0, 12.0))) }
        still(12.0, 12.0, Part(union(bars, arrows), f, l))
    }
    val fourWay = still(12.0, 12.0, Part(union(doubleArrow(0.0, 20.0), doubleArrow(90.0, 20.0)), f, l))
    val busy = (0 until 12).map { spinner(12.0, 12.0, 8.5, it, style) }
    val working = (0 until 12).map { listOf(pointer) + spinner(18.2, 17.6, 5.0, it, style) }
    return linkedMapOf(
        "default" to still(2.0, 1.5, pointer),
        "context-menu" to still(2.0, 1.5, listOf(pointer) + badge(style, union(
            Rectangle2D.Double(15.5, 15.6, 5.0, 1.1), Rectangle2D.Double(15.5, 17.6, 5.0, 1.1), Rectangle2D.Double(15.5, 19.6, 5.0, 1.1),
        ))),
        "help" to still(2.0, 1.5, pointer, Part(question(18.0, 16.0, 9.0), dark, light)),
        "pointer" to still(8.3, 1.2, pointingHand(style)),
        "progress" to Design(2.0, 1.5, working, delayMillis = 60),
        "wait" to Design(12.0, 12.0, busy, delayMillis = 60),
        "cell" to still(12.0, 12.0, Part(plusSign(12.0, 12.0, 8.0, 3.6), f, l)),
        "crosshair" to still(11.5, 11.5, Part(union(
            Rectangle2D.Double(11.0, 2.0, 1.0, 8.0), Rectangle2D.Double(11.0, 14.0, 1.0, 8.0),
            Rectangle2D.Double(2.0, 11.0, 8.0, 1.0), Rectangle2D.Double(14.0, 11.0, 8.0, 1.0),
        ), dark, light, 1.0, outside = true)),
        "text" to still(11.5, 12.0, Part(iBeam(), dark, light, 1.0, outside = true)),
        "vertical-text" to still(12.0, 12.0, Part(iBeam().turned(90.0, 12.0, 12.0), dark, light, 1.0, outside = true)),
        "alias" to still(2.0, 1.5, listOf(pointer) + badge(style, pen(1.3) {
            moveTo(15.8, 20.4); quadTo(15.8, 16.4, 19.6, 16.4)
        }.let { union(it, polygon(19.0, 14.6, 21.2, 16.4, 19.0, 18.2)) })),
        "copy" to still(2.0, 1.5, listOf(pointer) + badge(style, plusSign(18.0, 18.0, 2.9, 1.4))),
        "move" to fourWay,
        "no-drop" to still(2.0, 1.5, pointer, Part(noEntry(18.0, 17.5, 4.6), dark, light, 0.9)),
        "not-allowed" to still(12.0, 12.0, Part(noEntry(12.0, 12.0, 8.0), dark, light)),
        "grab" to still(12.0, 12.0, openHand(style)),
        "grabbing" to still(12.0, 12.0, closedHand(style)),
        "e-resize" to resize(0.0),
        "n-resize" to resize(90.0),
        "ne-resize" to resize(-45.0),
        "nw-resize" to resize(45.0),
        "s-resize" to resize(90.0),
        "se-resize" to resize(45.0),
        "sw-resize" to resize(-45.0),
        "w-resize" to resize(0.0),
        "ew-resize" to resize(0.0),
        "ns-resize" to resize(90.0),
        "nesw-resize" to resize(-45.0),
        "nwse-resize" to resize(45.0),
        "col-resize" to split(0.0),
        "row-resize" to split(90.0),
        "all-scroll" to fourWay,
        "zoom-in" to still(10.0, 10.0, magnifier(style, plus = true)),
        "zoom-out" to still(10.0, 10.0, magnifier(style, plus = false)),
        "dnd-ask" to still(2.0, 1.5, listOf(pointer) + badge(style, question(18.0, 18.0, 6.0))),
        "all-resize" to fourWay,
    )
}

// The X11 names older programs use, as other names for the cursors above.
val X11_NAMES = mapOf(
    "left_ptr" to "default", "arrow" to "default", "top_left_arrow" to "default",
    "question_arrow" to "help", "whats_this" to "help",
    "hand2" to "pointer", "hand1" to "pointer", "hand" to "pointer", "pointing_hand" to "pointer",
    "left_ptr_watch" to "progress", "half-busy" to "progress", "watch" to "wait",
    "plus" to "cell", "cross" to "crosshair", "tcross" to "crosshair", "xterm" to "text", "ibeam" to "text",
    "dnd-link" to "alias", "link" to "alias", "dnd-copy" to "copy", "fleur" to "move", "size_all" to "move",
    "dnd-move" to "move", "dnd-none" to "no-drop", "crossed_circle" to "not-allowed", "forbidden" to "not-allowed",
    "openhand" to "grab", "closedhand" to "grabbing",
    "right_side" to "e-resize", "left_side" to "w-resize", "top_side" to "n-resize", "bottom_side" to "s-resize",
    "top_right_corner" to "ne-resize", "bottom_left_corner" to "sw-resize",
    "top_left_corner" to "nw-resize", "bottom_right_corner" to "se-resize",
    "sb_h_double_arrow" to "ew-resize", "h_double_arrow" to "ew-resize", "size_hor" to "ew-resize",
    "sb_v_double_arrow" to "ns-resize", "v_double_arrow" to "ns-resize", "size_ver" to "ns-resize",
    "fd_double_arrow" to "nesw-resize", "size_bdiag" to "nesw-resize",
    "bd_double_arrow" to "nwse-resize", "size_fdiag" to "nwse-resize",
    "split_h" to "col-resize", "split_v" to "row-resize", "zoom_in" to "zoom-in", "zoom_out" to "zoom-out",
)

// --- drawing --------------------------------------------------------------------------

/** One frame at [size] pixels: premultiplied ARGB, as Xcursor wants it. */
fun render(parts: List<Part>, size: Int): BufferedImage {
    val unit = size / 24.0
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.scale(unit, unit)
    // A soft shadow under everything, a little down and right.
    val silhouette = Area()
    for (part in parts) {
        silhouette.add(Area(part.shape))
        if (part.line != null) {
            val width = if (part.outside) part.lineWidth * 2 else part.lineWidth
            silhouette.add(Area(BasicStroke(width.toFloat()).createStrokedShape(part.shape)))
        }
    }
    val shadow = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE)
    shadow.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        scale(unit, unit)
        color = Color(0, 0, 0, 72)
        fill(silhouette.moved(0.9, 1.3))
        dispose()
    }
    blur(shadow, Math.max(1, Math.round(unit).toInt()))
    g.drawImage(shadow, AffineTransform.getScaleInstance(1 / unit, 1 / unit), null)
    for (part in parts) {
        val line = part.line
        if (line != null && part.outside) {
            // The outline all around the outside: drawn first, twice as wide, the fill on top.
            g.color = line
            g.stroke = BasicStroke((part.lineWidth * 2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(part.shape)
        }
        g.color = part.fill
        g.fill(part.shape)
        if (line != null && !part.outside) {
            g.color = line
            g.stroke = BasicStroke(part.lineWidth.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f)
            g.draw(part.shape)
        }
    }
    g.dispose()
    return image
}

// Two passes of a box blur over the alpha (good enough for a soft shadow).
fun blur(image: BufferedImage, radius: Int) {
    val w = image.width
    val h = image.height
    var pixels = image.getRGB(0, 0, w, h, null, 0, w).map { (it ushr 24) and 0xff }.toIntArray()
    repeat(2) {
        for (horizontal in listOf(true, false)) {
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (d in -radius..radius) {
                    val sx = if (horizontal) x + d else x
                    val sy = if (horizontal) y else y + d
                    if (sx in 0 until w && sy in 0 until h) sum += pixels[sy * w + sx]
                    count++
                }
                out[y * w + x] = sum / count
            }
            pixels = out
        }
    }
    image.setRGB(0, 0, w, h, pixels.map { it shl 24 }.toIntArray(), 0, w)
}

/** An Xcursor file: every size of every frame. */
fun xcursor(design: Design): ByteArray {
    val images = SIZES.flatMap { size -> design.frames.map { size to render(it, size) } }
    val header = 16 + images.size * 12
    val total = header + images.sumOf { (size, _) -> 36 + size * size * 4 }
    val data = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    data.putInt(0x72756358).putInt(16).putInt(0x10000).putInt(images.size)
    var position = header
    for ((size, _) in images) {
        data.putInt(0xfffd0002.toInt()).putInt(size).putInt(position)
        position += 36 + size * size * 4
    }
    for ((size, image) in images) {
        val unit = size / 24.0
        val hotX = Math.round(design.hotX * unit).toInt().coerceIn(0, size - 1)
        val hotY = Math.round(design.hotY * unit).toInt().coerceIn(0, size - 1)
        data.putInt(36).putInt(0xfffd0002.toInt()).putInt(size).putInt(1)
            .putInt(size).putInt(size).putInt(hotX).putInt(hotY).putInt(design.delayMillis)
        val pixels = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
        for (pixel in pixels) data.putInt(pixel)
    }
    return data.array()
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = Path.of(args.getOrElse(0) { "caulis-cursor/src/main/resources/dev/caulis/core/cursor" })
    Files.createDirectories(out)
    for (style in STYLES) {
        val all = designs(style)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.setLevel(9)
            fun put(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 }) // no dates: the same zip every time
                zip.write(content)
                zip.closeEntry()
            }
            put("index.theme", "[Icon Theme]\nName=${style.name}\nComment=${style.comment} (Caulis)\n".toByteArray())
            for ((name, design) in all) put("cursors/$name", xcursor(design))
            put("aliases", X11_NAMES.entries.joinToString("") { (alias, name) -> "$alias $name\n" }.toByteArray())
        }
        Files.write(out.resolve("${style.name}.zip"), bytes.toByteArray())
        println("${style.name}: ${all.size} cursors, ${bytes.size() / 1024} KB")
    }
    // A preview sheet of every cursor at 48 px, for looking at them (not part of the themes).
    args.getOrNull(1)?.let { preview ->
        val big = args.getOrNull(2)?.toIntOrNull() ?: 48
        val cell = big + 8
        val sheet = BufferedImage(10 * cell, 8 * cell, BufferedImage.TYPE_INT_ARGB)
        val g = sheet.createGraphics()
        g.color = Color(0x88, 0x99, 0xaa)
        g.fillRect(0, 0, sheet.width, sheet.height)
        var i = 0
        for (style in STYLES) for ((_, design) in designs(style)) {
            g.drawImage(render(design.frames[0], big), (i % 10) * cell + 4, (i / 10) * cell + 4, null)
            g.color = Color.RED
            val unit = big / 24.0
            g.fillRect((i % 10) * cell + 4 + Math.round(design.hotX * unit).toInt(), (i / 10) * cell + 4 + Math.round(design.hotY * unit).toInt(), 1, 1)
            i++
        }
        ImageIO.write(sheet, "png", Path.of(preview).toFile())
    }
}
