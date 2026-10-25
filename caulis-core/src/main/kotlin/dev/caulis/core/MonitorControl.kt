package dev.caulis.core

import dev.caulis.render.GpuTarget
import dev.caulis.render.Renderer

/** One way a monitor can run: its size in pixels and how often it refreshes. */
data class MonitorMode(val width: Int, val height: Int, val refreshMillihertz: Int, val preferred: Boolean = false) {
    override fun toString() = "${width}x$height@${"%.2f".format(refreshMillihertz / 1000.0)}"
}

/**
 * How a monitor is turned (wl_output's transform): its picture rotated clockwise by 90, 180
 * or 270 degrees, and mirrored left to right first for the FLIPPED ones.
 */
enum class Transform(val degrees: Int, val flipped: Boolean) {
    NORMAL(0, false), ROTATE_90(90, false), ROTATE_180(180, false), ROTATE_270(270, false),
    FLIPPED(0, true), FLIPPED_90(90, true), FLIPPED_180(180, true), FLIPPED_270(270, true);

    /** Width and height trade places. */
    val sideways: Boolean get() = degrees == 90 || degrees == 270

    companion object {
        /** By its wl_output number (0 to 7). */
        fun of(value: Int): Transform = entries.getOrElse(value) { NORMAL }
    }
}

/**
 * What the backend can do with a monitor besides drawing on it (the screen can do all of it;
 * headless monitors most; a window in another session little): another mode, power off and
 * on (DPMS), a gamma table (night light), off altogether. Each returns false if it cannot.
 */
interface MonitorControl {
    /** The modes it can run in, best first. */
    val modes: List<MonitorMode> get() = emptyList()

    /** The mode it runs in now. */
    val currentMode: MonitorMode? get() = null

    fun setMode(mode: MonitorMode): Boolean = false

    /** Turns the screen off (false) or on again, keeping everything as it is. */
    fun setPower(on: Boolean): Boolean = false

    /** How many entries a gamma table has (0: no gamma tables). */
    val gammaSize: Int get() = 0

    /**
     * Uses [red], [green] and [blue] (each [gammaSize] long, 0..65535) as the gamma table;
     * null: the monitor's own again.
     */
    fun setGamma(red: ShortArray?, green: ShortArray?, blue: ShortArray?): Boolean = false

    /** Turned around: the picture is drawn turned (see [Rotator]). */
    val canTransform: Boolean get() = false

    /** Any size, not only [modes] (monitors that are not real ones). */
    val customModes: Boolean get() = false
}

/**
 * Draws a monitor's picture turned ([Transform]): the desktop draws into a picture of the
 * monitor's own size (width and height traded when sideways), which is then copied turned
 * onto the screen's buffer.
 */
class Rotator(private val renderer: Renderer) : AutoCloseable {

    private var picture: GpuTarget? = null
    private var drawnBefore = false

    /**
     * [draw] as the backend gets it, for a screen buffer turned by [transform]: what to call
     * instead (the same, when not turned).
     */
    fun wrap(transform: Transform, draw: (GpuTarget, Int) -> Unit): (GpuTarget, Int) -> Unit {
        if (transform == Transform.NORMAL) {
            drawnBefore = false
            return draw
        }
        return { screen, _ ->
            val width = if (transform.sideways) screen.height else screen.width
            val height = if (transform.sideways) screen.width else screen.height
            val current = picture?.takeIf { it.width == width && it.height == height } ?: run {
                picture?.close()
                drawnBefore = false
                renderer.createTarget(width, height).also { picture = it }
            }
            // The picture stays ours: only what changed since the last frame is drawn into it.
            draw(current, if (drawnBefore) 1 else 0)
            drawnBefore = true
            val texture = current.texture
            renderer.begin(screen, 0f, 0f, 0f)
            if (texture != null) {
                renderer.drawShaded(0, 0, screen.width, screen.height, SHADER, emptyMap(), mapOf("turn" to transform.degrees / 90, "flip" to if (transform.flipped) 1 else 0), texture)
            }
            renderer.end()
        }
    }

    override fun close() {
        picture?.close()
        picture = null
    }

    private companion object {
        // For each pixel of the screen, the pixel of the picture that goes there.
        const val SHADER = """#version 300 es
            precision highp float;
            in vec2 uv;
            uniform sampler2D picture;
            uniform int turn;
            uniform int flip;
            out vec4 color;
            void main() {
                vec2 p = uv;
                // Turned clockwise: undo the turn to find where in the picture this is.
                if (turn == 1) p = vec2(p.y, 1.0 - p.x);
                else if (turn == 2) p = vec2(1.0 - p.x, 1.0 - p.y);
                else if (turn == 3) p = vec2(1.0 - p.y, p.x);
                if (flip == 1) p.x = 1.0 - p.x;
                color = vec4(texture(picture, p).rgb, 1.0);
            }"""
    }
}
