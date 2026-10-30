package dev.caulis.core

import dev.caulis.geometry.Rect
import dev.caulis.render.BlurTone
import dev.caulis.render.Color
import dev.caulis.render.GpuTexture
import dev.caulis.render.Paint

/**
 * Something of the compositor's own above everything — a screenshot chooser, a launcher or a
 * menu drawn by the compositor itself — that has the keyboard and the pointer while it is
 * open ([Desktop.openOverlay]). Programs get no input meanwhile (keys let go still reach
 * them, so none stays stuck); quitting and the text consoles still work. Locking the screen
 * closes it.
 */
interface Overlay {

    /** What it shows on [monitor], bottom first, in desktop units (the monitor's [Monitor.area]). */
    fun draw(monitor: Monitor, canvas: OverlayCanvas)

    /** A key pressed or let go (its evdev code: 1 is Escape, 28 Enter, ...). */
    fun key(evdevCode: Int, pressed: Boolean) {}

    /** The pointer moved to [x], [y] (desktop units). */
    fun pointerMoved(x: Double, y: Double) {}

    /** A mouse button (272 left, 273 right, 274 middle) pressed or let go where the pointer is. */
    fun pointerButton(button: Int, pressed: Boolean) {}

    /** The wheel turned by [steps] (one notch is 1; negative: up or left). */
    fun scroll(vertical: Boolean, steps: Double) {}

    /** The cursor over it, by name ("default", "crosshair", "move", "pointer", ...). */
    val cursor: String get() = "default"

    /** It was closed ([Desktop.closeOverlay], or the screen was locked). */
    fun closed() {}
}

/**
 * Where an [Overlay], a [Drawing] or a [WindowDirector]'s decorations draw: pictures, colours,
 * borders, shadows and blur — in desktop units, in order (each one above the ones before).
 */
class OverlayCanvas internal constructor(
    /** The monitor drawn on now, or null (decorations of a window: wherever it is). */
    val monitor: Monitor?,
    private val key: Any,
    /** Seconds since the compositor started (for paints that move: gradients turning, shaders). */
    val time: Float = 0f,
    // What a wallpaper shader drawn here needs from the desktop (null: none can be drawn here).
    private val scene: WallpaperScene? = null,
) {

    internal val items = ArrayList<DrawItem>()

    // A paint that moves by itself was used: frames go on.
    internal var movingPaint = false

    private fun timeOf(paint: Paint): Float {
        if (!paint.moving) return 0f
        movingPaint = true
        return time
    }

    private fun next(kind: String) = listOf(key, kind, items.size)

    private fun <T : DrawItem> add(item: T): T = item.also { it.only = monitor; items += it }

    /** [texture] stretched over [rect], [alpha] as strong, its corners rounded by [radius]. */
    @JvmOverloads
    fun image(texture: GpuTexture, rect: Rect, alpha: Float = 1f, radius: Float = 0f) {
        add(DrawItem.Image(next("image"), texture, rect, clip = if (radius > 0f) rect else null, radius = radius, alpha = alpha))
    }

    /** [texture] stretched over [rect], [alpha] as strong, only what is inside [clip] shown. */
    @JvmOverloads
    fun imagePart(texture: GpuTexture, rect: Rect, clip: Rect, alpha: Float = 1f) {
        add(DrawItem.Image(next("image"), texture, rect, clip = clip, alpha = alpha))
    }

    /** [rect] filled with a colour (0..1 each), [alpha] as strong. */
    @JvmOverloads
    fun fill(rect: Rect, red: Float, green: Float, blue: Float, alpha: Float = 1f) {
        if (rect.width <= 0 || rect.height <= 0) return
        add(DrawItem.Color(next("fill"), rect, red, green, blue, alpha = alpha))
    }

    /** [rect] filled with a colour, its corners rounded by [radius]. */
    @JvmOverloads
    fun roundedFill(rect: Rect, radius: Float, color: Color, alpha: Float = 1f) {
        if (rect.width <= 0 || rect.height <= 0) return
        add(DrawItem.Tint(next("rounded"), rect, radius, color.red, color.green, color.blue, alpha * color.alpha))
    }

    /** A frame [width] thick just inside [rect]. */
    @JvmOverloads
    fun frame(rect: Rect, width: Int, red: Float, green: Float, blue: Float, alpha: Float = 1f) {
        fill(Rect(rect.x, rect.y, rect.width, width), red, green, blue, alpha)
        fill(Rect(rect.x, rect.bottom - width, rect.width, width), red, green, blue, alpha)
        fill(Rect(rect.x, rect.y + width, width, rect.height - 2 * width), red, green, blue, alpha)
        fill(Rect(rect.right - width, rect.y + width, width, rect.height - 2 * width), red, green, blue, alpha)
    }

    /**
     * A border [width] thick around the inside of [rect], round by [radius], in any [paint]
     * (a colour, a gradient, a shader — moving ones move with [time]).
     */
    @JvmOverloads
    fun border(rect: Rect, width: Float, paint: Paint, radius: Float = 0f, alpha: Float = 1f) {
        if (rect.width <= 0 || rect.height <= 0 || width <= 0f) return
        add(DrawItem.Border(next("border"), rect, radius, width, paint, alpha, timeOf(paint)))
    }

    /**
     * The same for a window in [state] (focused, urgent, in a group): a shader paint learns
     * it. [animate] false: a moving paint stands still (see [Border.animate]).
     */
    @JvmOverloads
    fun border(rect: Rect, width: Float, paint: Paint, radius: Float, alpha: Float, state: dev.caulis.render.PaintState, animate: Boolean = true) {
        if (rect.width <= 0 || rect.height <= 0 || width <= 0f) return
        add(DrawItem.Border(next("border"), rect, radius, width, paint, alpha, if (animate) timeOf(paint) else 0f, state))
    }

    /** A soft shadow around [box] (round by [radius]), [softness] wide, not drawn under [box] itself. */
    @JvmOverloads
    fun shadow(box: Rect, softness: Float, paint: Paint, radius: Float = 0f, alpha: Float = 1f) {
        if (box.width <= 0 || box.height <= 0) return
        add(DrawItem.Shadow(next("shadow"), box, radius, softness, paint, box, radius, alpha, timeOf(paint)))
    }

    /**
     * A wallpaper shader ([WallpaperShader]) over [rect], [alpha] as strong: its `at` is 0..1
     * over [rect], which may be bigger than the monitor (only the monitor's part is drawn) — a
     * wallpaper that moves and zooms with a view is a [rect] that moves and grows. [view] is the
     * part of the whole picture [rect] shows (its `view` uniform; null: all of it). It is drawn
     * anew as the shader asks ([WallpaperShader.framesPerSecond], the pointer), only while it
     * is seen; one that does not compile shows its fallback colour. On a canvas with no monitor
     * (a window's decorations) nothing is drawn.
     */
    @JvmOverloads
    fun wallpaper(shader: WallpaperShader, rect: Rect, alpha: Float = 1f, view: BackgroundView? = null) {
        val monitor = monitor ?: return
        val scene = scene ?: return
        if (rect.width <= 0 || rect.height <= 0 || alpha <= 0f) return
        add(scene.item(listOf(key, "wallpaper", items.size), monitor, rect, shader, alpha, view))
    }

    /**
     * What is below [rect] (drawn so far) blurred, round by [radius]: [size] how far each pass
     * reaches, [passes] how many (more: softer, slower); [tone] tints it (frosted glass).
     */
    @JvmOverloads
    fun blur(rect: Rect, radius: Float = 0f, size: Int = 4, passes: Int = 3, tone: BlurTone = BlurTone.NONE) {
        if (rect.width <= 0 || rect.height <= 0) return
        add(DrawItem.Blur(next("blur"), rect, radius, size.coerceAtLeast(1), passes.coerceIn(1, dev.caulis.render.MAX_BLUR_PASSES), tone))
    }
}

/** Where a [Drawing] goes among everything else. */
enum class DrawingLevel {
    /**
     * A wallpaper of your own: above the desktop's background, below every panel — a wallpaper
     * program's (swaybg), bars on the bottom layer — and every window.
     */
    WALLPAPER,

    /** Above the wallpaper and the panels of the bottom layers, below every window. */
    BELOW_WINDOWS,

    /** Above the windows, below the panels of the top layer (bars). */
    ABOVE_WINDOWS,

    /** Above everything but the compositor's own notices, overlays and the cursor. */
    ON_TOP,
}

/**
 * Pictures of your own drawn with the desktop that take no input — a bar, rounded screen
 * corners, a border around the focused window, a glow ([Desktop.addDrawing]). [draw] is
 * asked again whenever the desktop draws anew (after [Desktop.requestRedraw] too) and every
 * frame while [animating].
 */
interface Drawing {
    /** What it shows on [monitor], bottom first (desktop units: [Monitor.area]). */
    fun draw(monitor: Monitor, canvas: OverlayCanvas)

    /** True while it changes by itself: frames keep coming. */
    fun animating(now: Long): Boolean = false
}

/** What a wallpaper shader on an [OverlayCanvas] needs from the desktop: its pointer, clock and frames. */
internal class WallpaperScene(
    private val shaders: ShaderWallpapers,
    private val renderer: dev.caulis.render.Renderer,
    private val pointerX: Double,
    private val pointerY: Double,
    private val now: Long,
    private val desktop: Rect,
    private val works: (name: String, code: String, problem: String?) -> Unit,
) {
    fun item(key: Any, monitor: Monitor, rect: Rect, shader: WallpaperShader, alpha: Float, view: BackgroundView?): DrawItem =
        shaders.item(key, renderer, monitor, rect, shader, alpha, pointerX, pointerY, now, desktop, monitor.workspace.number, view, null, look = listOf(shader), works = works)
}
