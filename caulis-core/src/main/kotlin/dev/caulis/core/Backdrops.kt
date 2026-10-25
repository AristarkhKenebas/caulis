package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.render.Color
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The desktop's backgrounds ([Background]) on each monitor: their pictures on the GPU,
 * pictures read from files in the background, moving shaders drawn again as often as they
 * ask (only while they are seen), and a soft change from one background to the next.
 * Belongs to a [Desktop].
 */
internal class Backdrops(
    private val renderer: Renderer,
    private val loop: EventLoop,
    /** Something to draw changed. */
    private val changed: () -> Unit,
    /** A shader was drawn (problem null) or could not be (see Desktop.shaderWorks). */
    private val shaderWorks: (name: String, code: String, problem: String?) -> Unit = { _, _, _ -> },
) {
    private val log = Logger("render")
    private val start = System.nanoTime()

    var background: Background = Background.USUAL
        set(value) {
            field = value
            changed()
        }

    var backgroundFor: ((Monitor, Int) -> Background?)? = null
        set(value) {
            field = value
            changed()
        }

    /** How long one background takes to melt into the next. */
    var changeMillis = 400L

    private fun backgroundOf(monitor: Monitor, workspace: Int): Background = try {
        backgroundFor?.invoke(monitor, workspace) ?: background
    } catch (e: Exception) {
        log.error("Your backgroundFor failed (the usual background is shown): $e", e)
        background
    }

    // What each monitor showed last, and the one before while it melts away (since when).
    private val shown = HashMap<String, Background>()
    private val leaving = HashMap<String, Pair<Background, Long>>()

    /**
     * The items that draw [monitor]'s background now (below everything); [desktop] is all
     * monitors together (for backgrounds spanning them), [largestScale] the finest monitor's.
     */
    fun items(monitor: Monitor, workspace: Int, pointerX: Double, pointerY: Double, now: Long, desktop: Rect, largestScale: Double): List<DrawItem> {
        this.desktop = desktop
        this.largestScale = largestScale
        val wanted = backgroundOf(monitor, workspace)
        val before = shown.put(monitor.name, wanted)
        if (before != null && before != wanted && changeMillis > 0) leaving[monitor.name] = before to now
        val items = ArrayList<DrawItem>(2)
        items += item(monitor, workspace, wanted, 1f, pointerX, pointerY, now, "background ${monitor.name}")
        leaving[monitor.name]?.let { (old, since) ->
            val t = (now - since) / (changeMillis * 1_000_000.0)
            if (t >= 1.0) {
                leaving -= monitor.name
            } else {
                items += item(monitor, workspace, old, (1 - t * t * (3 - 2 * t)).toFloat(), pointerX, pointerY, now, "background before ${monitor.name}")
            }
        }
        return items
    }

    // All monitors together, and the finest scale (for pictures and shaders spanning them).
    private var desktop = Rect(0, 0, 1, 1)
    private var largestScale = 1.0

    /** True while a background melts into the next one: each frame is different. */
    fun changing(): Boolean = leaving.isNotEmpty()

    private fun item(monitor: Monitor, workspace: Int, background: Background, alpha: Float, pointerX: Double, pointerY: Double, now: Long, key: String): DrawItem {
        val area = monitor.area
        val pixels = monitor.pixelWidth to monitor.pixelHeight
        return when (background) {
            is Background.Solid -> DrawItem.Custom(key, area, listOf(background, alpha)) { r -> fill(r, background.color, alpha) }
            is Background.Gradient, is Background.Painted -> {
                val texture = painted(background, monitor, workspace)
                DrawItem.Custom(key, area, listOf(background, texture, alpha)) { r ->
                    if (texture != null) renderer.drawTexture(texture, r.x, r.y, r.width, r.height, opaque = false, alpha = alpha) else fill(r, USUAL_COLOR, alpha)
                }
            }
            is Background.Image -> {
                val picture = picture(background.path)
                val over = if (background.span) desktop else area
                val unitScale = if (background.span) largestScale else monitor.scale
                DrawItem.Custom(key, area, listOf(background, picture, alpha, pixels, over, unitScale)) { r -> drawImage(r, area, over, unitScale, background, picture, alpha) }
            }
            is Background.Shader -> {
                val shader = background.shader
                val seconds = (now - start) / 1e9
                // A moving shader is a new picture every frame of its own (fewer is less work).
                val frame = if (shader.moving) Math.floor(seconds * shader.framesPerSecond).toLong() else 0L
                val inside = pointerX >= area.x && pointerX < area.right && pointerY >= area.y && pointerY < area.bottom
                val pointer = if (inside) floatArrayOf(((pointerX - area.x) / area.width).toFloat(), ((pointerY - area.y) / area.height).toFloat()) else floatArrayOf(-1f, -1f)
                val onDesktop = floatArrayOf(((pointerX - desktop.x) / desktop.width).toFloat(), ((pointerY - desktop.y) / desktop.height).toFloat())
                val all = desktop
                val physical = monitor.output?.let { floatArrayOf(it.physicalWidthMm.toFloat(), it.physicalHeightMm.toFloat()) } ?: floatArrayOf(0f, 0f)
                val picture = shader.image?.let(::picture)
                if (shader.moving) movingOn += monitor.name to shader.framesPerSecond
                // One version of its code for drawing and for what is said about it (a file may change meanwhile).
                val code = shader.code
                val fileProblem = shader.fileProblem
                val source = shader.source(code)
                DrawItem.Custom(key, area, listOf(background, source, frame, alpha, pixels, picture, all, if (shader.moving) pointer.toList() + onDesktop.toList() else null)) { r ->
                    val drawn = renderer.drawShaded(
                        r.x, r.y, r.width, r.height, source,
                        floats = mapOf(
                            "time" to floatArrayOf(seconds.toFloat()), "size" to floatArrayOf(r.width.toFloat(), r.height.toFloat()),
                            "pointer" to pointer, "workspace" to floatArrayOf(workspace.toFloat()), "alpha" to floatArrayOf(alpha),
                            "monitor" to floatArrayOf(area.x.toFloat(), area.y.toFloat(), area.width.toFloat(), area.height.toFloat()),
                            "desktop" to floatArrayOf(all.x.toFloat(), all.y.toFloat(), all.width.toFloat(), all.height.toFloat()),
                            "scale" to floatArrayOf(monitor.scale.toFloat()), "physicalSize" to physical, "desktopPointer" to onDesktop,
                        ),
                        ints = mapOf("hasPicture" to if (picture != null) 1 else 0),
                        texture = picture?.texture,
                    )
                    shaderWorks(shader.name, code, fileProblem ?: if (drawn) null else renderer.effectProblem(source) ?: "it does not compile")
                    if (!drawn) fill(r, USUAL_COLOR, alpha)
                }
            }
        }
    }


    private fun fill(r: Rect, color: Color, alpha: Float) = renderer.drawColor(r.x, r.y, r.width, r.height, color.red, color.green, color.blue, color.alpha * alpha)

    // --- pictures from files: read in the background, then on the GPU --------------------------

    private class Picture(val texture: GpuTexture, val width: Int, val height: Int)

    // By file: its picture, or null while it is read (or when it could not be).
    private val pictures = HashMap<Path, Picture?>()

    // Picture files followed for changes (a new version is read again).
    private val followed = HashSet<Path>()

    private fun picture(path: Path): Picture? {
        if (path in pictures) return pictures[path]
        pictures[path] = null
        if (followed.add(path)) {
            FileWatch.watch(path) {
                loop.post {
                    pictures.remove(path)?.texture?.close()
                    changed()
                }
            }
        }
        Thread.ofVirtual().name("background-image").start {
            // (Also errors: Java's 2D graphics that cannot start says so with one; the background
            // then stays [Background.Image.behind], and the log says why.)
            val argb = try {
                val image = ImageIO.read(path.toFile()) ?: error("not a picture Java can read (PNG, JPEG, BMP, GIF)")
                BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB_PRE).also { argb ->
                    argb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
                }
            } catch (e: Throwable) {
                log.warn("The background picture $path could not be read: ${e.message ?: e}")
                return@start
            }
            loop.post {
                pictures[path] = Picture(upload(argb), argb.width, argb.height)
                log.info { "Background picture $path: ${argb.width}x${argb.height}" }
                changed()
            }
        }
        return null
    }

    // Draws [picture] placed over [over] (desktop units: the monitor's [area], or all monitors
    // together) as [Background.Image.fit] says: the part on this monitor, into its pixels [r].
    // [unitScale]: picture pixels per unit at its own size (CENTER, TILE).
    private fun drawImage(r: Rect, area: Rect, over: Rect, unitScale: Double, background: Background.Image, picture: Picture?, alpha: Float) {
        if (picture == null) return fill(r, background.behind, alpha)
        val iw = picture.width.toDouble()
        val ih = picture.height.toDouble()
        val ow = over.width.toDouble()
        val oh = over.height.toDouble()
        // Where the whole picture is, in desktop units (x, y, width, height).
        fun centered(w: Double, h: Double) = doubleArrayOf(over.x + (ow - w) / 2, over.y + (oh - h) / 2, w, h)
        val fit = background.fit
        if (fit != Background.Fit.FILL && fit != Background.Fit.STRETCH) fill(r, background.behind, alpha)
        when (fit) {
            Background.Fit.FILL -> max(ow / iw, oh / ih).let { s -> part(r, area, picture, centered(iw * s, ih * s), alpha) }
            Background.Fit.FIT -> min(ow / iw, oh / ih).let { s -> part(r, area, picture, centered(iw * s, ih * s), alpha) }
            Background.Fit.STRETCH -> part(r, area, picture, doubleArrayOf(over.x.toDouble(), over.y.toDouble(), ow, oh), alpha)
            Background.Fit.CENTER -> part(r, area, picture, centered(iw / unitScale, ih / unitScale), alpha)
            Background.Fit.TILE -> {
                val tw = iw / unitScale
                val th = ih / unitScale
                // Only the tiles touching this monitor.
                var ty = over.y + Math.floor((area.y - over.y) / th) * th
                while (ty < area.bottom) {
                    var tx = over.x + Math.floor((area.x - over.x) / tw) * tw
                    while (tx < area.right) {
                        part(r, area, picture, doubleArrayOf(tx, ty, tw, th), alpha)
                        tx += tw
                    }
                    ty += th
                }
            }
        }
    }

    // The part of [picture] placed at [place] (desktop units) that is on the monitor [area], into its pixels [r].
    private fun part(r: Rect, area: Rect, picture: Picture, place: DoubleArray, alpha: Float) {
        val (px, py, pw, ph) = place.toList()
        val left = max(px, area.x.toDouble())
        val top = max(py, area.y.toDouble())
        val right = min(px + pw, area.right.toDouble())
        val bottom = min(py + ph, area.bottom.toDouble())
        if (right <= left || bottom <= top) return
        val sx = r.width.toDouble() / area.width
        val sy = r.height.toDouble() / area.height
        val x0 = (r.x + (left - area.x) * sx).roundToInt()
        val y0 = (r.y + (top - area.y) * sy).roundToInt()
        val x1 = (r.x + (right - area.x) * sx).roundToInt()
        val y1 = (r.y + (bottom - area.y) * sy).roundToInt()
        if (x1 <= x0 || y1 <= y0) return
        val source = floatArrayOf(((left - px) / pw).toFloat(), ((top - py) / ph).toFloat(), ((right - left) / pw).toFloat(), ((bottom - top) / ph).toFloat())
        renderer.drawTexture(picture.texture, x0, y0, x1 - x0, y1 - y0, opaque = false, alpha = alpha, source = source)
    }

    // --- painted and gradients: once per size ----------------------------------------------------

    private val paintings = HashMap<String, Pair<Any, GpuTexture>>()

    private fun painted(background: Background, monitor: Monitor, workspace: Int): GpuTexture? {
        val width = monitor.pixelWidth.coerceAtLeast(1)
        val height = monitor.pixelHeight.coerceAtLeast(1)
        val shows = listOf(background, width, height, monitor.scale, if (background is Background.Painted) workspace else 0)
        val key = monitor.name + if (background is Background.Painted) " painted" else " gradient"
        paintings[key]?.let { (was, texture) -> if (was == shows) return texture else texture.close() }
        paintings.remove(key)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            when (background) {
                is Background.Gradient -> {
                    fun awt(c: Color) = java.awt.Color(c.red, c.green, c.blue, c.alpha)
                    val angle = background.degrees * PI / 180
                    val half = (max(width, height) / 2.0)
                    val cx = width / 2.0
                    val cy = height / 2.0
                    g.paint = GradientPaint(
                        (cx - cos(angle) * half).toFloat(), (cy - sin(angle) * half).toFloat(), awt(background.from),
                        (cx + cos(angle) * half).toFloat(), (cy + sin(angle) * half).toFloat(), awt(background.to),
                    )
                    g.fillRect(0, 0, width, height)
                }
                is Background.Painted -> background.painter.paint(g, BackgroundLook(width, height, monitor.scale, monitor.name, workspace))
                else -> {}
            }
        } catch (e: Exception) {
            log.error("Painting the background failed (your BackgroundPainter?): $e", e)
        } finally {
            g.dispose()
        }
        return upload(image).also { paintings[key] = shows to it }
    }

    private fun upload(image: BufferedImage): GpuTexture {
        val texture = renderer.createTexture(image.width, image.height)
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val data = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
            texture.upload(arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, *data), 0, image.height)
        }
        return texture
    }

    // --- moving shaders: drawn again as often as they ask, only while seen ------------------------

    // Monitors with a moving shader in this scene (and how often it wants to be drawn).
    private val movingOn = HashSet<Pair<String, Int>>()
    private var tick: EventLoop.Timer? = null

    /**
     * The scene was built: [seen] are the monitors whose background is not covered (by a
     * wallpaper program, or a fullscreen window). A moving shader there asks for its next frame.
     */
    fun sceneBuilt(seen: Set<String>) {
        val fps = movingOn.filter { it.first in seen }.maxOfOrNull { it.second }
        movingOn.clear()
        if (fps == null || tick != null) return
        tick = loop.after((1000L / fps).coerceAtLeast(1)) {
            tick = null
            changed()
        }
    }

    fun close() {
        tick?.cancel()
        pictures.values.forEach { it?.texture?.close() }
        paintings.values.forEach { it.second.close() }
    }

    private companion object {
        val USUAL_COLOR = (Background.USUAL as Background.Solid).color
    }
}
