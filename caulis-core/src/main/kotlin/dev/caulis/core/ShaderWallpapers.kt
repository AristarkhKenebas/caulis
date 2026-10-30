package dev.caulis.core

import dev.caulis.event.EventLoop
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.render.Color
import dev.caulis.render.GpuTexture
import dev.caulis.render.Renderer
import kotlin.math.exp
import kotlin.math.floor

/**
 * What wallpaper shaders ([WallpaperShader]) share, wherever they are drawn (the desktop's
 * background, or a [Drawing] with [OverlayCanvas.wallpaper]): their clock (which can stand
 * still: [paused]), the pointer's speed and the last clicks, and their frames — asked for only
 * as often as the shaders seen want them ([sceneBuilt]), never faster than [fpsLimit].
 * Belongs to a [Desktop]; everything on its loop's thread.
 */
internal class ShaderWallpapers(
    private val loop: EventLoop,
    /** A frame is wanted (something moved). */
    private val changed: () -> Unit,
) {
    private val log = Logger("render")
    private val start = System.nanoTime()

    // Time spent standing still so far (their clock goes on from where it stopped), and since when it stands.
    private var pausedNanos = 0L
    private var pausedSince = 0L

    /** All stand still: their time and the pointer as they were (nothing is drawn anew for them). */
    var paused = false
        set(value) {
            if (field == value) return
            field = value
            val now = System.nanoTime()
            if (value) pausedSince = now else pausedNanos += now - pausedSince
            log.info { if (value) "Wallpaper shaders stand still" else "Wallpaper shaders move again" }
            changed()
        }

    /** The most frames a second any of them is drawn at (0: as each asks). */
    var fpsLimit = 0
        set(value) {
            require(value >= 0) { "a frame limit is 0 (none) or more, not $value" }
            if (field == value) return
            field = value
            log.info { if (value == 0) "Wallpaper shaders: as many frames as each asks" else "Wallpaper shaders: at most $value frames a second" }
            changed()
        }

    /** Seconds on their clock at [now] (System.nanoTime). */
    fun seconds(now: Long): Double = ((if (paused) pausedSince else now) - start - pausedNanos) / 1e9

    // --- the pointer: where it is, how fast, the last presses -------------------------------------

    private var sampleX = Double.NaN
    private var sampleY = Double.NaN
    private var sampleAt = 0L
    private var velocityX = 0.0 // desktop units a second, smoothed
    private var velocityY = 0.0
    private var frozenX = Double.NaN
    private var frozenY = Double.NaN

    /** One press: where (desktop units), when (their clock's seconds), which button (1 left, 2 middle, 3 right). */
    private class Click(val x: Double, val y: Double, val seconds: Double, val button: Int)

    private val clicks = ArrayDeque<Click>(MAX_CLICKS)

    // When the pointer last moved or a button was pressed (System.nanoTime).
    private var lastInput = 0L

    /** A button was pressed at [x], [y] (desktop units): evdev's [button] (272 left, 273 right, 274 middle). */
    fun pressed(x: Double, y: Double, button: Int) {
        if (paused) return
        val number = when (button) {
            272 -> 1
            274 -> 2
            273 -> 3
            else -> 4
        }
        if (clicks.size == MAX_CLICKS) clicks.removeLast()
        val now = System.nanoTime()
        clicks.addFirst(Click(x, y, seconds(now), number))
        lastInput = now
        if (pointerWanted) wake()
    }

    /** The pointer moved: a shader following it is drawn anew (at its pace). */
    fun pointerMoved() {
        if (paused || !pointerWanted) return
        lastInput = System.nanoTime()
        wake()
    }

    /**
     * Where the pointer is now ([x], [y], desktop units): its speed from the last time, smoothed
     * (a quarter of a second or so), falling to nothing while it rests. Once per scene.
     */
    fun sample(x: Double, y: Double, now: Long) {
        if (paused) {
            if (frozenX.isNaN()) { frozenX = x; frozenY = y }
            return
        }
        frozenX = Double.NaN
        frozenY = Double.NaN
        if (!sampleX.isNaN() && now > sampleAt) {
            val dt = ((now - sampleAt) / 1e9).coerceAtMost(1.0)
            if (dt > 0.0005) {
                val k = 1 - exp(-dt / VELOCITY_SECONDS)
                velocityX += ((x - sampleX) / dt - velocityX) * k
                velocityY += ((y - sampleY) / dt - velocityY) * k
            }
        }
        if (Math.abs(velocityX) < 0.5) velocityX = 0.0
        if (Math.abs(velocityY) < 0.5) velocityY = 0.0
        sampleX = x
        sampleY = y
        sampleAt = now
    }

    // The pointer as shaders see it (where it stood when they stopped, while they stand still).
    private fun pointerX(x: Double) = if (paused && !frozenX.isNaN()) frozenX else x
    private fun pointerY(y: Double) = if (paused && !frozenY.isNaN()) frozenY else y

    // True while the pointer is still moving (or slowing down) or a click is fresh.
    private fun pointerBusy(now: Long): Boolean =
        velocityX != 0.0 || velocityY != 0.0 || now - lastInput < POINTER_REST_NANOS ||
            clicks.firstOrNull()?.let { seconds(now) - it.seconds < CLICK_SECONDS } == true

    // --- drawing ------------------------------------------------------------------------------------

    /**
     * The item drawing [shader] over [rect] (desktop units; it may stick out of [monitor], whose
     * part only is drawn): its `at` 0..1 over [rect]. [view] is the part of the whole picture
     * [rect] shows (null: all of it); [picture] what `image(uv)` reads.
     */
    fun item(
        key: Any, renderer: Renderer, monitor: Monitor, rect: Rect, shader: WallpaperShader, alpha: Float, pointerX: Double, pointerY: Double,
        now: Long, desktop: Rect, workspace: Int, view: BackgroundView?, picture: GpuTexture?, look: List<Any?>,
        works: (name: String, code: String, problem: String?) -> Unit,
    ): DrawItem {
        val seconds = seconds(now)
        val code = shader.code
        val usesTime = shader.usesTime
        val usesPointer = shader.usesPointer
        val fps = effectiveFps(shader)
        // A moving shader is a new picture every frame of its own (fewer is less work).
        val frame = if (usesTime && !paused) floor(seconds * fps).toLong() else 0L
        val px = pointerX(pointerX)
        val py = pointerY(pointerY)
        val area = monitor.area
        val inside = px >= area.x && px < area.right && py >= area.y && py < area.bottom
        val w = rect.width.coerceAtLeast(1).toDouble()
        val h = rect.height.coerceAtLeast(1).toDouble()
        val pointer = if (inside) floatArrayOf(((px - rect.x) / w).toFloat(), ((py - rect.y) / h).toFloat()) else floatArrayOf(-1f, -1f)
        val pointerPixels = if (inside) floatArrayOf(((px - rect.x) * monitor.scale).toFloat(), ((py - rect.y) * monitor.scale).toFloat()) else floatArrayOf(-1f, -1f)
        val velocity = if (paused) floatArrayOf(0f, 0f) else floatArrayOf((velocityX / w).toFloat(), (velocityY / h).toFloat())
        val onDesktop = floatArrayOf(((px - desktop.x) / desktop.width).toFloat(), ((py - desktop.y) / desktop.height).toFloat())
        val clickList = clicks.map { c -> floatArrayOf(((c.x - rect.x) / w).toFloat(), ((c.y - rect.y) / h).toFloat(), c.seconds.toFloat(), c.button.toFloat()) }
        val physical = monitor.output?.let { floatArrayOf(it.physicalWidthMm.toFloat(), it.physicalHeightMm.toFloat()) } ?: floatArrayOf(0f, 0f)
        val viewPart = view?.let { floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.width.toFloat(), it.height.toFloat()) } ?: floatArrayOf(0f, 0f, 1f, 1f)
        val fileProblem = shader.fileProblem
        val source = shader.source(code)
        val pointerLook: Any? = if (usesPointer) listOf(pointer.toList(), velocity.toList(), onDesktop.toList(), clickList.map { it.toList() }) else null
        // Asks for frames: one using time at its pace; one following the pointer while it moves.
        val wants = if (paused) 0 else if (usesTime || (usesPointer && pointerBusy(now))) fps else 0
        val fallback = shader.fallback ?: USUAL_COLOR
        // The part of [rect] on the monitor (0..1 of it): only that is drawn when drawn smaller.
        val shown = rect.intersect(monitor.area)?.let { s ->
            floatArrayOf(((s.x - rect.x) / w).toFloat(), ((s.y - rect.y) / h).toFloat(), (s.width / w).toFloat(), (s.height / h).toFloat())
        }
        val frameLook = look + listOf(source, frame, rect, picture, viewPart.toList(), pointerLook, workspace, shown?.toList(), shader.resolution)
        return DrawItem.Custom(key, rect, look + listOf(source, frame, alpha, rect, picture, viewPart.toList(), pointerLook, workspace), framesPerSecond = wants, wallpaper = shader) { r ->
            val floats = HashMap<String, FloatArray>(32)
            floats["time"] = floatArrayOf(seconds.toFloat())
            floats["size"] = floatArrayOf(r.width.toFloat(), r.height.toFloat())
            floats["pointer"] = pointer
            floats["pointerPixels"] = pointerPixels
            floats["pointerVelocity"] = velocity
            floats["workspace"] = floatArrayOf(workspace.toFloat())
            floats["alpha"] = floatArrayOf(alpha)
            floats["monitor"] = floatArrayOf(rect.x.toFloat(), rect.y.toFloat(), rect.width.toFloat(), rect.height.toFloat())
            floats["desktop"] = floatArrayOf(desktop.x.toFloat(), desktop.y.toFloat(), desktop.width.toFloat(), desktop.height.toFloat())
            floats["scale"] = floatArrayOf(monitor.scale.toFloat())
            floats["physicalSize"] = physical
            floats["desktopPointer"] = onDesktop
            floats["view"] = viewPart
            for (i in 0 until MAX_CLICKS) floats["clicks[$i]"] = clickList.getOrNull(i) ?: NO_CLICK
            for ((i, c) in shader.colors.withIndex()) floats["colors[$i]"] = floatArrayOf(c.red, c.green, c.blue, c.alpha)
            for ((name, value) in shader.values) floats[name] = floatArrayOf(value)
            val ints = mapOf("hasPicture" to if (picture != null) 1 else 0, "clickCount" to clickList.size, "colorCount" to shader.colors.size)
            val drawn = if (shader.resolution < 1f && shown != null) {
                drawSmaller(key, r, shown, frameLook, shader.resolution, source, floats, ints, picture, alpha, renderer)
            } else {
                renderer.drawShaded(r.x, r.y, r.width, r.height, source, floats, ints, picture)
            }
            works(shader.name, code, fileProblem ?: if (drawn) null else renderer.effectProblem(source) ?: "it does not compile")
            if (!drawn) renderer.drawColor(r.x, r.y, r.width, r.height, fallback.red, fallback.green, fallback.blue, fallback.alpha * alpha)
        }
    }

    // --- drawn smaller, kept: by item key, its picture and what it shows -------------------------

    private class Kept(val target: dev.caulis.render.GpuTarget, var look: Any?, var used: Long)

    private val kept = HashMap<Any, Kept>()

    // Draws the shader into a picture [resolution] as sharp as the part [shown] of [r] (pixels of
    // the frame), once per look of its own, and stretches that over its place. False: it does not compile.
    private fun drawSmaller(
        key: Any, r: Rect, shown: FloatArray, look: Any?, resolution: Float, source: String, floats: Map<String, FloatArray>, ints: Map<String, Int>,
        picture: GpuTexture?, alpha: Float, renderer: Renderer,
    ): Boolean {
        val px = Rect(
            r.x + Math.round(shown[0] * r.width), r.y + Math.round(shown[1] * r.height),
            Math.round(shown[2] * r.width).coerceAtLeast(1), Math.round(shown[3] * r.height).coerceAtLeast(1),
        )
        val tw = Math.round(px.width * resolution).coerceAtLeast(1)
        val th = Math.round(px.height * resolution).coerceAtLeast(1)
        var entry = kept[key]
        if (entry != null && (entry.target.width != tw || entry.target.height != th)) {
            entry.target.close()
            kept -= key
            entry = null
        }
        if (entry == null) entry = Kept(renderer.createTarget(tw, th), null, 0L).also { kept[key] = it }
        entry.used = System.nanoTime()
        if (entry.look != look) {
            // (Its own size: the shader's size uniform says the whole place, in the frame's pixels.)
            // (Drawn whole; its strength is the stretching's.)
            if (!renderer.drawShadedInto(entry.target, source, floats + ("alpha" to floatArrayOf(1f)), ints, picture, shown)) {
                entry.look = null
                return false
            }
            entry.look = look
        }
        val texture = entry.target.texture ?: return renderer.drawShaded(r.x, r.y, r.width, r.height, source, floats, ints, picture)
        renderer.drawTexture(texture, px.x, px.y, px.width, px.height, opaque = false, alpha = alpha)
        return true
    }

    private fun effectiveFps(shader: WallpaperShader): Int = if (fpsLimit > 0) minOf(fpsLimit, shader.framesPerSecond) else shader.framesPerSecond

    // --- frames: as often as the shaders seen want ----------------------------------------------------

    // A shader following the pointer is seen: its moves ask for frames.
    private var pointerWanted = false
    private var pointerFps = 0
    private var tick: EventLoop.Timer? = null

    /** The scene was built: [kept] is what is seen of it. The fastest shader seen asks for its next frame. */
    fun sceneBuilt(kept: List<DrawItem>) {
        // Pictures drawn smaller that nothing drew for a while: let go.
        if (this.kept.isNotEmpty()) {
            val now = System.nanoTime()
            this.kept.entries.removeIf { (_, entry) -> (now - entry.used > KEPT_NANOS).also { if (it) entry.target.close() } }
        }
        var fps = 0
        var pointer = 0
        for (item in kept) {
            if (item !is DrawItem.Custom) continue
            val shader = item.wallpaper ?: continue
            fps = maxOf(fps, item.framesPerSecond)
            if (shader.usesPointer) pointer = maxOf(pointer, effectiveFps(shader))
        }
        pointerWanted = pointer > 0 && !paused
        pointerFps = pointer
        if (fps > 0) schedule(fps)
    }

    // When the last frame was asked for (System.nanoTime).
    private var askedAt = 0L

    // The pointer moved or was pressed: a frame for a shader following it at its pace (none waiting already).
    private fun wake() {
        if (tick != null) return
        val fps = pointerFps.coerceAtLeast(1)
        val waited = (System.nanoTime() - askedAt) / 1_000_000
        ask(((1000L / fps) - waited).coerceAtLeast(1))
    }

    private fun schedule(fps: Int) {
        if (tick != null) return
        ask((1000L / fps).coerceAtLeast(1))
    }

    private fun ask(millis: Long) {
        tick = loop.after(millis) {
            tick = null
            askedAt = System.nanoTime()
            changed()
        }
    }

    fun close() {
        tick?.cancel()
        tick = null
        kept.values.forEach { it.target.close() }
        kept.clear()
    }

    internal companion object {
        /** How many presses shaders see. */
        const val MAX_CLICKS = 4

        // How long the pointer's speed is smoothed over, in seconds.
        const val VELOCITY_SECONDS = 0.12

        // A shader following the pointer is drawn this long after the last move (its speed falls to nothing meanwhile).
        const val POINTER_REST_NANOS = 400_000_000L

        // How long a click is fresh (a ripple from it plays meanwhile).
        const val CLICK_SECONDS = 4.0

        // How long a picture drawn smaller is kept while nothing draws it.
        const val KEPT_NANOS = 5_000_000_000L

        private val NO_CLICK = floatArrayOf(-1f, -1f, -1000f, 0f)

        val USUAL_COLOR = (Background.USUAL as Background.Solid).color
    }
}
