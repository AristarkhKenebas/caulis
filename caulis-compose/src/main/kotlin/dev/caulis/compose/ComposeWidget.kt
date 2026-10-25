package dev.caulis.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import dev.caulis.core.Desktop
import dev.caulis.core.DrawingLevel
import dev.caulis.core.Monitor
import dev.caulis.core.OverlayCanvas
import dev.caulis.core.Reserve
import dev.caulis.core.Widget
import dev.caulis.core.WidgetKey
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.render.GpuTexture
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * A part of the compositor's own interface written in Compose — a bar, a dock, a launcher, a
 * volume pill: a [Widget] whose [content] is a Compose scene (one per monitor it is on),
 * drawn into a picture only when something in it changed, and getting the pointer, the wheel
 * and — with [takesKeyboard] — the keyboard (text fields work).
 *
 * ```
 * desktop.addWidget(ComposeWidget(desktop, post = { loop.post(it::run) },
 *     place = { m -> Rect(m.area.x, m.area.y, m.area.width, 32) }, reserve = { Reserve(Edge.TOP, 32) }) { monitor ->
 *     Row(Modifier.fillMaxSize().background(Color(0xCC1E1E2E))) { Text(monitor.name, color = Color.White) }
 * })
 * ```
 *
 * Close the widget (or the handle [Desktop.addWidget] gave: that closes it too) when it is
 * not needed any more. A new size in pixels (its area grown, another scale) makes its scene
 * anew: state kept in it with `remember` starts over then — keep it outside for a widget that
 * changes its size. In a Caulis configuration `compose(...)` does this for you. Everything Compose runs
 * (recomposition, animations, `LaunchedEffect`) runs on the compositor's thread through [post].
 */
class ComposeWidget @JvmOverloads constructor(
    private val desktop: Desktop,
    /** Runs a task on the compositor's thread soon (from any thread): `EventLoop.post`, `Caulis.post`. */
    private val post: (Runnable) -> Unit,
    /** Where it is on a monitor (desktop units), or null: not there. */
    private val place: (Monitor) -> Rect?,
    override val level: DrawingLevel = DrawingLevel.ON_TOP,
    /** Room it keeps at an edge of a monitor (a bar), or null. */
    private val reserve: (Monitor) -> Reserve? = { null },
    override val takesKeyboard: Boolean = false,
    private val content: @Composable (monitor: Monitor) -> Unit,
) : Widget, AutoCloseable {

    private val log = Logger("compose")

    // The compositor's thread for everything Compose dispatches.
    private val dispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = post(block)
    }

    // One scene per monitor it is on, the picture it last made and its size in pixels.
    private class Part(val scene: ImageComposeScene, val width: Int, val height: Int, val scale: Double) {
        var texture: GpuTexture? = null
        var drawnOnce = false
    }

    private val parts = HashMap<String, Part>()
    private var keyboardLost by mutableIntStateOf(0)
    private val heldKeys = LinkedHashMap<Int, WidgetKey>()
    private var hoverOn: String? = null
    private var keysOn: String? = null
    private var closed = false

    // A state changed anywhere (a clock, a volume): notifications sent, frames asked for.
    private val pendingApply = AtomicBoolean(false)
    private val writes = Snapshot.registerGlobalWriteObserver {
        if (pendingApply.compareAndSet(false, true)) post(Runnable {
            pendingApply.set(false)
            Snapshot.sendApplyNotifications()
            if (!closed) desktop.requestRedraw()
        })
    }

    override fun area(monitor: Monitor): Rect? = place(monitor)

    override fun reserve(monitor: Monitor): Reserve? = reserve.invoke(monitor)

    override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) {
        val part = partFor(monitor, area) ?: return
        if (!part.drawnOnce || part.scene.hasInvalidations()) render(part)
        part.texture?.let { canvas.image(it, area) }
    }

    // Only scenes still shown keep frames coming (not one of a monitor gone, or not there now).
    override fun animating(now: Long): Boolean {
        if (desktop.locked) return false
        val shown = desktop.monitors.associateBy { it.name }
        for (name in parts.keys.filter { it !in shown }) drop(name)
        return parts.any { (name, part) -> part.scene.hasInvalidations() && place(shown.getValue(name)) != null }
    }

    // The scene for [monitor] in [area]'s size (made anew when the size or scale changes).
    private fun partFor(monitor: Monitor, area: Rect): Part? {
        val scale = monitor.scale
        val width = Math.round(area.width * scale).toInt()
        val height = Math.round(area.height * scale).toInt()
        if (width <= 0 || height <= 0) return null
        parts[monitor.name]?.let { if (it.width == width && it.height == height && it.scale == scale) return it else drop(monitor.name) }
        val scene = ImageComposeScene(width, height, Density(scale.toFloat()), dispatcher) {
            // The keyboard gone elsewhere: no text field keeps it (or its caret blinking).
            val focus = LocalFocusManager.current
            val lost = keyboardLost
            LaunchedEffect(lost) { if (lost > 0) focus.clearFocus(force = true) }
            content(monitor)
        }
        log.info { "Compose scene on ${monitor.name}: ${width}x$height px at scale $scale" }
        windowFocused(scene, hasKeyboard)
        return Part(scene, width, height, scale).also { parts[monitor.name] = it }
    }

    // Compose draws the scene (Skia, on the processor); its pixels become our picture.
    private fun render(part: Part) {
        val image = part.scene.render(System.nanoTime())
        part.drawnOnce = true
        val bitmap = Bitmap()
        try {
            bitmap.allocPixels(ImageInfo.makeN32(part.width, part.height, ColorAlphaType.PREMUL))
            if (!image.readPixels(bitmap)) return
            val bytes = bitmap.readPixels() ?: return
            val texture = part.texture ?: desktop.renderer.createTexture(part.width, part.height).also { part.texture = it }
            Arena.ofConfined().use { arena ->
                val memory = arena.allocate(bytes.size.toLong())
                MemorySegment.copy(bytes, 0, memory, ValueLayout.JAVA_BYTE, 0, bytes.size)
                texture.upload(memory, 0, part.height)
            }
        } finally {
            bitmap.close()
            image.close()
        }
    }

    // --- input ------------------------------------------------------------------------------

    private fun sceneOf(name: String?): Part? = name?.let(parts::get)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun pointerMoved(monitor: Monitor, x: Double, y: Double) {
        val part = parts[monitor.name] ?: return
        if (hoverOn != monitor.name) {
            sceneOf(hoverOn)?.scene?.sendPointerEvent(PointerEventType.Exit, Offset.Zero)
            hoverOn = monitor.name
            part.scene.sendPointerEvent(PointerEventType.Enter, offset(part, x, y))
        }
        part.scene.sendPointerEvent(PointerEventType.Move, offset(part, x, y))
        last = x to y
    }

    private var last = 0.0 to 0.0

    private fun offset(part: Part, x: Double, y: Double) = Offset((x * part.scale).toFloat(), (y * part.scale).toFloat())

    @OptIn(ExperimentalComposeUiApi::class)
    override fun pointerLeft() {
        sceneOf(hoverOn)?.scene?.sendPointerEvent(PointerEventType.Exit, Offset.Zero)
        hoverOn = null
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun pointerButton(button: Int, pressed: Boolean) {
        val part = sceneOf(hoverOn) ?: return
        if (pressed) keysOn = hoverOn
        val which = when (button) {
            BTN_LEFT -> PointerButton.Primary
            BTN_RIGHT -> PointerButton.Secondary
            BTN_MIDDLE -> PointerButton.Tertiary
            BTN_SIDE -> PointerButton.Back
            BTN_EXTRA -> PointerButton.Forward
            else -> return
        }
        part.scene.sendPointerEvent(if (pressed) PointerEventType.Press else PointerEventType.Release, offset(part, last.first, last.second), button = which)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun scroll(dx: Double, dy: Double) {
        val part = sceneOf(hoverOn) ?: return
        // Compose counts in lines (one wheel notch is one).
        part.scene.sendPointerEvent(PointerEventType.Scroll, offset(part, last.first, last.second), scrollDelta = Offset((dx / 15).toFloat(), (dy / 15).toFloat()))
    }

    override fun key(key: WidgetKey) {
        val part = sceneOf(keysOn ?: hoverOn) ?: parts.values.firstOrNull() ?: return
        if (key.pressed) heldKeys[key.evdevCode] = key else heldKeys.remove(key.evdevCode)
        for (event in AwtKeys.events(key)) part.scene.sendKeyEvent(event)
    }

    private var hasKeyboard = false

    override fun keyboardFocus(has: Boolean) {
        hasKeyboard = has
        for (part in parts.values) windowFocused(part.scene, has)
        if (has) return
        // Keys held now are let go (else Compose thinks they still are).
        val part = sceneOf(keysOn ?: hoverOn) ?: parts.values.firstOrNull()
        for (held in heldKeys.values.toList()) {
            val up = WidgetKey(held.evdevCode, false, null, held.shift, held.ctrl, held.alt, held.logo, (System.nanoTime() / 1_000_000).toInt())
            part?.let { for (event in AwtKeys.events(up)) it.scene.sendKeyEvent(event) }
        }
        heldKeys.clear()
        keyboardLost++
    }

    // The scene's window counts as focused while the widget has the keyboard: Compose's text
    // fields act on keys (arrows, Home, Ctrl+A) only in a focused window. (Not public in
    // ImageComposeScene: reached by reflection, with the exact Compose version this module is built against.)
    private fun windowFocused(scene: ImageComposeScene, focused: Boolean) {
        try {
            val info = ImageComposeScene::class.java.getDeclaredField("_windowInfo").apply { isAccessible = true }.get(scene)
            info.javaClass.getMethod("setWindowFocused", Boolean::class.javaPrimitiveType).invoke(info, focused)
        } catch (e: ReflectiveOperationException) {
            log.warn("Compose's window focus cannot be set (keys like arrows may not work in text fields): $e")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        writes.dispose()
        for (name in parts.keys.toList()) drop(name)
    }

    private fun drop(name: String) {
        val part = parts.remove(name) ?: return
        part.scene.close()
        part.texture?.close()
    }

    private companion object {
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        const val BTN_MIDDLE = 274
        const val BTN_SIDE = 275
        const val BTN_EXTRA = 276
    }
}
