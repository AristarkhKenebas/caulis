package dev.caulis.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Color
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Surface
import java.lang.foreign.MemorySegment
import kotlin.coroutines.CoroutineContext

/**
 * One Compose scene drawn into a picture of [width] x [height] pixels — as Compose's own
 * ImageComposeScene, with two things more a compositor needs: its window counts as focused
 * only while the widget has the keyboard ([focused]), and the pointer's shape Compose asks for
 * is told ([onPointerIcon]).
 *
 * (Built from Compose's parts marked as for its modules only: the exact Compose version this
 * module is built against is required — see build.gradle.kts.)
 */
@OptIn(InternalComposeUiApi::class)
internal class Scene(
    val width: Int,
    val height: Int,
    density: Density,
    context: CoroutineContext,
    onPointerIcon: (PointerIcon) -> Unit,
    content: @Composable () -> Unit,
) : AutoCloseable {

    private val surface = Surface.makeRasterN32Premul(width, height)

    // The "window" it is in: as big as the picture; focused while the widget has the keyboard.
    private val window = object : WindowInfo {
        override var isWindowFocused by mutableStateOf(false)
        override val containerSize = IntSize(width, height)
        override val containerDpSize = DpSize((width / density.density).dp, (height / density.density).dp)
    }

    private val frames = FrameRecomposer(context)

    private val platform = object : PlatformContext.Empty() {
        override val windowInfo: WindowInfo get() = window
        override fun setPointerIcon(pointerIcon: PointerIcon) = onPointerIcon(pointerIcon)
    }

    private val scene = CanvasLayersComposeScene(frames, density, size = IntSize(width, height), platformContext = platform).also { it.setContent(content = content) }

    /** Text fields act on keys (arrows, Home, Ctrl+A) only in a focused window. */
    var focused: Boolean
        get() = window.isWindowFocused
        set(value) {
            window.isWindowFocused = value
        }

    fun hasInvalidations(): Boolean = frames.hasPendingWork() || scene.hasInvalidations()

    /** Composes and lays it out (animations at [nanoTime]), without drawing it. */
    fun layout(nanoTime: Long) {
        frames.performFrame(nanoTime)
        scene.measureAndLayout()
    }

    // The picture's own pixels, looked at where they are (not copied).
    private val pixmap = Pixmap()

    /**
     * Draws it (animations at [nanoTime]); its pixels — premultiplied, rows of [width] * 4
     * bytes — are the memory returned, which is the picture's own: good until it is drawn again
     * or closed. Null if they cannot be looked at so.
     */
    fun render(nanoTime: Long): MemorySegment? {
        surface.canvas.clear(Color.TRANSPARENT)
        frames.performFrame(nanoTime)
        scene.measureAndLayout()
        scene.draw(surface.canvas.asComposeCanvas())
        if (!surface.peekPixels(pixmap) || pixmap.rowBytes != width * 4) return null
        return MemorySegment.ofAddress(pixmap.addr).reinterpret(width.toLong() * height * 4)
    }

    /** Its pixels as last drawn (as [render] returns them), not drawn anew; null if they cannot be looked at so. */
    fun pixels(): MemorySegment? {
        if (!surface.peekPixels(pixmap) || pixmap.rowBytes != width * 4) return null
        return MemorySegment.ofAddress(pixmap.addr).reinterpret(width.toLong() * height * 4)
    }

    private val recorder = org.jetbrains.skia.PictureRecorder()

    /**
     * Composes, lays it out and records what it draws (animations at [nanoTime]) — on the
     * compositor's thread, as all of Compose — without drawing it into pixels: [play] does
     * that, on any thread.
     */
    fun record(nanoTime: Long): org.jetbrains.skia.Picture {
        frames.performFrame(nanoTime)
        scene.measureAndLayout()
        val canvas = recorder.beginRecording(org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()))
        scene.draw(canvas.asComposeCanvas())
        return recorder.finishRecordingAsPicture()
    }

    /**
     * Draws [picture] (from [record]) into the picture's pixels, as [render] returns them.
     * Any thread, one at a time, never while [render] runs. Closes [picture].
     */
    fun play(picture: org.jetbrains.skia.Picture): MemorySegment? {
        try {
            surface.canvas.clear(Color.TRANSPARENT)
            surface.canvas.drawPicture(picture)
        } finally {
            picture.close()
        }
        if (!surface.peekPixels(pixmap) || pixmap.rowBytes != width * 4) return null
        return MemorySegment.ofAddress(pixmap.addr).reinterpret(width.toLong() * height * 4)
    }

    fun sendPointerEvent(
        type: PointerEventType,
        position: Offset,
        scrollDelta: Offset = Offset.Zero,
        modifiers: PointerKeyboardModifiers? = null,
        button: PointerButton? = null,
    ) {
        scene.sendPointerEvent(type, position, scrollDelta, System.nanoTime() / 1_000_000, keyboardModifiers = modifiers, button = button)
    }

    fun sendKeyEvent(event: KeyEvent): Boolean = scene.sendKeyEvent(event)

    override fun close() {
        try {
            scene.close()
        } finally {
            frames.close()
            recorder.close()
            pixmap.close()
            surface.close()
        }
    }
}
