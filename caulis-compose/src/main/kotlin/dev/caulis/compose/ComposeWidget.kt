package dev.caulis.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import dev.caulis.core.DataDeviceManager
import dev.caulis.core.Desktop
import dev.caulis.core.DrawingLevel
import dev.caulis.core.Edge
import dev.caulis.core.Monitor
import dev.caulis.core.OverlayCanvas
import dev.caulis.core.Reserve
import dev.caulis.core.Widget
import dev.caulis.core.WidgetKey
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.render.GpuTexture
import dev.caulis.core.NoticeLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * A part of the compositor's own interface written in Compose — a bar, a dock, a launcher, a
 * volume pill: a [Widget] whose [content] is a Compose scene (one per monitor it is on),
 * drawn into a picture only when something in it changed, and getting the pointer (with the
 * shape Compose asks for), the wheel and — with [takesKeyboard] — the keyboard (text fields
 * work, Ctrl+C and Ctrl+V with the compositor's [clipboard]).
 *
 * ```
 * desktop.addWidget(ComposeWidget(desktop, post = { loop.post(it::run) },
 *     place = { m -> Rect(m.area.x, m.area.y, m.area.width, 32) }, reserve = { Reserve(Edge.TOP, 32) }) { monitor ->
 *     Row(Modifier.fillMaxSize().background(Color(0xCC1E1E2E))) { Text(monitor.name, color = Color.White) }
 * })
 * ```
 *
 * Close the widget (or the handle [Desktop.addWidget] gave: that closes it too) when it is
 * not needed any more: it is then nowhere (no room kept, no clicks taken, nothing drawn). A
 * new size in pixels (its area grown, another scale) makes its scene anew: state kept in it
 * with `remember` starts over then — keep it outside for a widget that changes its size, or
 * give it a [sceneSize]. In a Caulis configuration `compose(...)` does this for you.
 *
 * Everything Compose runs (recomposition, animations, `LaunchedEffect`) runs on the
 * compositor's thread through [post]; call its functions on that thread too. Content that
 * throws is logged and started over ([recovery]); while its [appearance] is fully see-through
 * it is not drawn at all.
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
    /**
     * The scene's size on a monitor, or null: its area's. A scene bigger than its area shows
     * its top left part: the area may then grow and shrink (a menu with more rows) without the
     * scene being made anew.
     */
    private val sceneSize: (Monitor) -> SceneSize? = { null },
    /** How the picture is shown (see-through, bigger or smaller, moved): done by the compositor, nothing drawn anew. */
    private val appearance: (Monitor) -> WidgetLook = { WidgetLook.SHOWN },
    /** True while [appearance] changes by itself: frames keep coming. */
    private val moving: () -> Boolean = { false },
    /**
     * A press somewhere not on it (x, y in desktop units; onAnother: on another widget, e.g. a
     * bar): true if it was for this (a menu that closed), then nothing else gets it.
     */
    private val onPressOutside: (x: Double, y: Double, onAnother: Boolean) -> Boolean = { _, _, _ -> false },
    /** The compositor's clipboard for its text fields (`Caulis.clipboard`); null: copying and pasting do nothing. */
    private val clipboard: DataDeviceManager? = null,
    /** What it does when its content fails (an exception): logged, then started over — see [ComposeRecovery]. */
    private val recovery: ComposeRecovery = ComposeRecovery.DEFAULT,
    /**
     * Shown and used while the session is locked too (an on-screen keyboard, a "battery low"
     * pill: see [Widget.showsWhileLocked]). A lock screen of your own in Compose needs no
     * such mark: give the widget to [Desktop.lockInternally].
     */
    override val showsWhileLocked: Boolean = false,
    private val content: @Composable (monitor: Monitor) -> Unit,
) : Widget, AutoCloseable {

    // Caulis 0.1.1's constructor with its defaults left out (level, reserve, takesKeyboard):
    // what Kotlin code built against it calls — the defaults' mask and marker as Kotlin passes
    // them. (It cannot be a constructor with defaults: one with the same parameters is there.)
    @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
    constructor(
        desktop: Desktop,
        post: (Runnable) -> Unit,
        place: (Monitor) -> Rect?,
        level: DrawingLevel?,
        reserve: ((Monitor) -> Reserve?)?,
        takesKeyboard: Boolean,
        content: @Composable (monitor: Monitor) -> Unit,
        defaults: Int,
        @Suppress("UNUSED_PARAMETER") marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ) : this(
        desktop,
        post,
        place,
        level = if (defaults and (1 shl 3) != 0 || level == null) DrawingLevel.ON_TOP else level,
        reserve = if (defaults and (1 shl 4) != 0 || reserve == null) { _ -> null } else reserve,
        takesKeyboard = defaults and (1 shl 5) == 0 && takesKeyboard,
        content = content,
    )

    private val log = Logger("compose")

    // The compositor's thread for everything Compose dispatches.
    private val dispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = post(block)
    }

    // One scene per monitor it is on, the picture it last made and its size in pixels.
    private class Part(val scene: Scene, val width: Int, val height: Int, val scale: Double) {
        var texture: GpuTexture? = null
        // Its picture is up to date (false: never drawn, or moved on while not seen).
        var drawnOnce = false
        // Its content threw: the scene is dead (it starts over soon); its last picture stays.
        var failed = false
    }

    private val parts = HashMap<String, Part>()
    private var keyboardLost by mutableIntStateOf(0)
    private val heldKeys = LinkedHashMap<Int, WidgetKey>()
    private var hoverOn: String? = null
    private var keysOn: String? = null
    private var closed = false
    // (Compose of another version than this module's: said once, then nothing is shown.)
    private var broken = false
    private val compositorClipboard = clipboard?.let { CompositorClipboard(it, log) }

    // A state changed anywhere (a clock, a volume): notifications sent, frames asked for — once
    // for all the widgets (not one task per widget for every write).
    init {
        Writes.add(this)
    }

    override fun area(monitor: Monitor): Rect? = if (closed) null else place(monitor)

    override fun reserve(monitor: Monitor): Reserve? = if (closed) null else reserve.invoke(monitor)

    override fun draw(monitor: Monitor, canvas: OverlayCanvas, area: Rect) {
        if (closed) return
        val part = partFor(monitor, area) ?: return
        val look = appearance(monitor)
        if (look.alpha <= HIDDEN) {
            // Not seen at all (a menu or pill hidden by its look): composed and laid out (what
            // waits for its size gets it) but not drawn, and no frames kept coming for it —
            // its picture is made when it shows.
            if (!part.failed && part.scene.hasInvalidations()) {
                guarded(monitor.name, part) { part.scene.layout(System.nanoTime()) }
                part.drawnOnce = false
            }
            return
        }
        if (!part.failed && (!part.drawnOnce || part.scene.hasInvalidations())) render(monitor.name, part)
        val texture = part.texture ?: return
        val sceneRect = Rect(area.x, area.y, Math.round(part.width / part.scale).toInt(), Math.round(part.height / part.scale).toInt())
        if (look == WidgetLook.SHOWN && sceneRect == area) return canvas.image(texture, area)
        // Bigger or smaller about the area's middle, then moved.
        val cx = area.x + area.width / 2.0
        val cy = area.y + area.height / 2.0
        fun moved(r: Rect): Rect {
            val left = cx + (r.x - cx) * look.scale + look.dx
            val top = cy + (r.y - cy) * look.scale + look.dy
            return Rect(Math.round(left).toInt(), Math.round(top).toInt(), Math.round(r.width * look.scale).toInt(), Math.round(r.height * look.scale).toInt())
        }
        canvas.imagePart(texture, moved(sceneRect), moved(shownPart(area, look)), look.alpha)
    }

    // The part of [area] shown with [look]'s reveal (desktop units, before it is moved).
    private fun shownPart(area: Rect, look: WidgetLook): Rect {
        if (look.reveal >= 1f) return area
        val r = look.reveal.coerceIn(0f, 1f)
        val w = Math.round(area.width * r).toInt().coerceAtLeast(1)
        val h = Math.round(area.height * r).toInt().coerceAtLeast(1)
        return when (look.revealFrom) {
            Edge.TOP -> Rect(area.x, area.y, area.width, h)
            Edge.BOTTOM -> Rect(area.x, area.bottom - h, area.width, h)
            Edge.LEFT -> Rect(area.x, area.y, w, area.height)
            Edge.RIGHT -> Rect(area.right - w, area.y, w, area.height)
        }
    }

    // Only scenes still shown keep frames coming (not one of a monitor gone, or not there now).
    override fun animating(now: Long): Boolean {
        // (While locked only the lock screen and the widgets shown then move on.)
        if (closed || (desktop.locked && !showsWhileLocked && desktop.lockScreen !== this)) return false
        val shown = desktop.monitors.associateBy { it.name }
        for (name in parts.keys.filter { it !in shown }) drop(name)
        return parts.any { (name, part) ->
            val monitor = shown.getValue(name)
            !part.failed && part.scene.hasInvalidations() && place(monitor) != null && appearance(monitor).alpha > HIDDEN
        } || moving()
    }

    // The scene for [monitor] in [area]'s size (made anew when the size or scale changes).
    private fun partFor(monitor: Monitor, area: Rect): Part? {
        if (closed || broken) return null
        // Failed a moment ago: what it showed last, until it starts over.
        if (monitor.name in restartAt) return parts[monitor.name]
        val scale = monitor.scale
        val size = sceneSize(monitor) ?: SceneSize(area.width, area.height)
        val width = Math.round(size.width * scale).toInt()
        val height = Math.round(size.height * scale).toInt()
        if (width <= 0 || height <= 0) return null
        parts[monitor.name]?.let { if (it.width == width && it.height == height && it.scale == scale) return it else drop(monitor.name) }
        val name = monitor.name
        // (Its own coroutines fail into [failed], not into the thread's.)
        val context = dispatcher + CoroutineExceptionHandler { _, e -> failed(name, e) }
        val scene = try {
            Scene(width, height, Density(scale.toFloat()), context, ::pointerIcon) {
                // The keyboard gone elsewhere: no text field keeps it (or its caret blinking).
                val focus = LocalFocusManager.current
                val lost = keyboardLost
                LaunchedEffect(lost) { if (lost > 0) focus.clearFocus(force = true) }
                if (compositorClipboard != null) CompositionLocalProvider(LocalClipboard provides compositorClipboard) { content(monitor) } else content(monitor)
            }
        } catch (e: LinkageError) {
            broken = true
            log.error("Compose on the class path is not the version caulis-compose was built with (it needs exactly that one: see its dependencies) — Compose widgets are not shown", e)
            return null
        } catch (e: Throwable) {
            // (Its content threw the first time it was composed — also an Error: TODO().)
            e.rethrowIfFatalHere()
            failed(name, e)
            return null
        }
        log.info { "Compose scene on ${monitor.name}: ${width}x$height px at scale $scale" }
        scene.focused = hasKeyboard
        return Part(scene, width, height, scale).also { parts[monitor.name] = it }
    }

    // Compose draws the scene (Skia, on the processor); its pixels go straight from Skia's
    // picture to ours on the GPU (no copy in between, nothing new made each frame).
    private fun render(name: String, part: Part) {
        val started = System.nanoTime()
        part.drawnOnce = true
        val pixels = guarded(name, part) { part.scene.render(started) } ?: return
        val rendered = System.nanoTime()
        val texture = part.texture ?: desktop.renderer.createTexture(part.width, part.height).also { part.texture = it }
        texture.upload(pixels, 0, part.height)
        val ms = (System.nanoTime() - started) / 1_000_000.0
        if (ms > 8) log.trace { "Compose frame of ${part.width}x${part.height} px took %.1f ms (drawing %.1f)".format(ms, (rendered - started) / 1e6) }
    }

    // --- content that fails ---------------------------------------------------------------

    // Monitors whose scene failed: when it may start over (System.nanoTime()).
    private val restartAt = HashMap<String, Long>()
    private var failures = 0
    private var lastFailure = 0L
    private var noticeShown = false
    // Waits before scenes start over (cancelled when it closes).
    private val restarts = CoroutineScope(dispatcher + SupervisorJob())

    // Runs [action] on [part]'s scene (on [name]); if it throws, the scene failed: null.
    private inline fun <T> guarded(name: String, part: Part, action: () -> T): T? {
        if (part.failed) return null
        return try {
            action()
        } catch (e: Throwable) {
            // (An Error too — TODO() is one — else it would fail on every frame past us.)
            e.rethrowIfFatalHere()
            failed(name, e)
            null
        }
    }

    // What nothing here can recover from goes on (the JVM's own trouble, but for a stack
    // overflow: that is the content's own, and fails it as any mistake does).
    private fun Throwable.rethrowIfFatalHere() {
        if (this is VirtualMachineError && this !is StackOverflowError) throw this
    }

    // The content on [name] threw [error]: said, then its scene starts over after a while.
    private fun failed(name: String, error: Throwable) {
        if (closed) return
        val part = parts[name]
        if (part != null) {
            if (part.failed) return
            part.failed = true
        } else if (name in restartAt) {
            return
        }
        val now = System.nanoTime()
        if (failures > 0 && now - lastFailure > recovery.forgetAfterMillis * 1_000_000) failures = 0
        failures++
        lastFailure = now
        val wait = recovery.delayAfter(failures)
        restartAt[name] = now + wait * 1_000_000
        // (The whole story the first time; after that a line each — it may fail again and again.)
        if (failures == 1) {
            log.error("Your Compose content failed on $name: it starts over in $wait ms", error)
        } else {
            log.error("Your Compose content failed on $name again ($failures times in a row: $error): it starts over in $wait ms")
        }
        try {
            recovery.onError(error)
        } catch (e: Throwable) {
            e.rethrowIfFatalHere()
            log.error("Your onError of a Compose widget failed", e)
        }
        val notice = recovery.notice
        if (notice != null && !noticeShown) {
            noticeShown = true
            desktop.notify(notice.replace("{error}", error.message ?: error.javaClass.simpleName), NoticeLevel.ERROR)
        }
        // Not now: it may have failed in the middle of its own frame.
        restarts.launch {
            delay(wait)
            restartAt.remove(name)
            if (closed) return@launch
            log.info { "The Compose content on $name starts over" }
            if (parts[name]?.failed == true) drop(name)
            if (hoverOn == name) hoverOn = null
            desktop.requestRedraw()
        }
    }

    // --- input ------------------------------------------------------------------------------

    private fun sceneOf(name: String?): Part? = if (closed) null else name?.let(parts::get)?.takeIf { !it.failed }

    // Where the pointer is in the scene's pixels: [x], [y] (units from its area's top left)
    // taken back through how it is shown (bigger, moved); null where nothing of it is seen.
    private fun inScene(monitor: Monitor, part: Part, x: Double, y: Double): Offset? {
        val area = place(monitor) ?: return null
        val look = appearance(monitor)
        if (look.alpha <= HIDDEN || look.scale <= 0.001f) return null
        val sx = (x - area.width / 2.0 - look.dx) / look.scale + area.width / 2.0
        val sy = (y - area.height / 2.0 - look.dy) / look.scale + area.height / 2.0
        val shown = shownPart(area, look)
        if (sx < shown.x - area.x || sy < shown.y - area.y || sx >= shown.right - area.x || sy >= shown.bottom - area.y) return null
        return Offset((sx * part.scale).toFloat(), (sy * part.scale).toFloat())
    }

    private var last: Offset? = null

    override fun pointerMoved(monitor: Monitor, x: Double, y: Double) {
        val part = sceneOf(monitor.name) ?: return
        val at = inScene(monitor, part, x, y)
        if (at == null) return pointerLeft()
        if (hoverOn != monitor.name) {
            send(hoverOn, PointerEventType.Exit, Offset.Zero)
            hoverOn = monitor.name
            send(monitor.name, PointerEventType.Enter, at, modifiers = modifiers())
        }
        send(monitor.name, PointerEventType.Move, at, modifiers = modifiers())
        last = at
    }

    override fun pointerLeft() {
        send(hoverOn, PointerEventType.Exit, Offset.Zero)
        hoverOn = null
        last = null
        pointerIcon(PointerIcon.Default)
    }

    override fun pressedOutside(button: Int, x: Double, y: Double, onAnother: Boolean): Boolean =
        !closed && onPressOutside(x, y, onAnother)

    override fun pointerButton(button: Int, pressed: Boolean) {
        if (sceneOf(hoverOn) == null) return
        val at = last ?: return
        if (pressed) keysOn = hoverOn
        val which = when (button) {
            BTN_LEFT -> PointerButton.Primary
            BTN_RIGHT -> PointerButton.Secondary
            BTN_MIDDLE -> PointerButton.Tertiary
            BTN_SIDE -> PointerButton.Back
            BTN_EXTRA -> PointerButton.Forward
            else -> return
        }
        send(hoverOn, if (pressed) PointerEventType.Press else PointerEventType.Release, at, modifiers = modifiers(), button = which)
    }

    override fun scroll(dx: Double, dy: Double) {
        val at = last ?: return
        // Compose counts in lines (one wheel notch is one; the compositor gives units, a notch
        // being WHEEL_UNITS of them).
        send(hoverOn, PointerEventType.Scroll, at, scrollDelta = Offset((dx / WHEEL_UNITS).toFloat(), (dy / WHEEL_UNITS).toFloat()), modifiers = modifiers())
    }

    // The modifiers held, as the last key said (Shift+click, Ctrl+wheel). (Known while it has
    // the keyboard: else keys go to the programs.)
    private var lastKey: WidgetKey? = null

    private fun modifiers(): PointerKeyboardModifiers {
        val key = lastKey ?: return PointerKeyboardModifiers()
        return PointerKeyboardModifiers(isCtrlPressed = key.ctrl, isMetaPressed = key.logo, isAltPressed = key.alt, isShiftPressed = key.shift)
    }

    override fun key(key: WidgetKey) {
        val name = (keysOn ?: hoverOn)?.takeIf { sceneOf(it) != null } ?: parts.entries.firstOrNull { !it.value.failed }?.key?.takeIf { !closed } ?: return
        if (key.pressed) heldKeys[key.evdevCode] = key else heldKeys.remove(key.evdevCode)
        lastKey = key
        sendKeys(name, key)
    }

    // A pointer event to the scene on [name] (none: nothing; one that throws fails).
    private fun send(
        name: String?,
        type: PointerEventType,
        at: Offset,
        scrollDelta: Offset = Offset.Zero,
        modifiers: PointerKeyboardModifiers? = null,
        button: PointerButton? = null,
    ) {
        val part = sceneOf(name) ?: return
        guarded(name!!, part) { part.scene.sendPointerEvent(type, at, scrollDelta, modifiers, button) }
    }

    // [key] as Compose's key events to the scene on [name].
    private fun sendKeys(name: String, key: WidgetKey) {
        val part = sceneOf(name) ?: return
        for (event in AwtKeys.events(key, log)) guarded(name, part) { part.scene.sendKeyEvent(event) } ?: return
    }

    /** True while it has the keyboard ([Desktop.focusWidget]). */
    var hasKeyboard = false
        private set

    override fun keyboardFocus(has: Boolean) {
        hasKeyboard = has
        for (part in parts.values) part.scene.focused = has
        if (has) {
            // Given without a click (a key that opens a launcher): the keys go to the scene on
            // the monitor with the pointer, else on the focused one.
            keysOn = hoverOn ?: desktop.focusedMonitor.name.takeIf { it in parts } ?: keysOn
            return
        }
        // Keys held now are let go (else Compose thinks they still are).
        val name = (keysOn ?: hoverOn)?.takeIf { sceneOf(it) != null } ?: parts.entries.firstOrNull { !it.value.failed }?.key
        for (held in heldKeys.values.toList()) {
            val up = WidgetKey(held.evdevCode, false, null, held.shift, held.ctrl, held.alt, held.logo, (System.nanoTime() / 1_000_000).toInt())
            if (name != null) sendKeys(name, up)
        }
        heldKeys.clear()
        lastKey = null
        keyboardLost++
    }

    // --- the pointer's shape ----------------------------------------------------------------

    /** The cursor Compose asks for where the pointer is ("text" over a text field, "pointer" over a link, ...). */
    override var cursor: String = "default"
        private set

    private fun pointerIcon(icon: PointerIcon) {
        val name = CURSORS.firstOrNull { (awt, _) -> PointerIcon(java.awt.Cursor(awt)) == icon }?.second ?: "default"
        if (name == cursor) return
        cursor = name
        log.trace { "Compose's pointer: $name" }
        desktop.requestRedraw()
    }

    override fun close() {
        if (closed) return
        closed = true
        Writes.remove(this)
        restarts.cancel()
        restartAt.clear()
        for (name in parts.keys.toList()) drop(name)
        hoverOn = null
        keysOn = null
        log.info { "A Compose widget closed" }
        // Its room at an edge and its place for clicks go at once.
        desktop.updateWidgets()
    }

    /**
     * Draws its scene once now, off screen, [width] x [height] units on [monitor]. The first
     * time something is drawn is slow (classes loaded, fonts, pictures): done ahead, its first
     * showing is as quick as the next ones. Its own scene is kept when that is its size
     * (with a sceneSize, the scene is that size and [width], [height] do not matter). Nothing
     * once it is closed. Call it on the compositor's thread.
     */
    fun prewarm(monitor: Monitor, width: Int, height: Int) {
        if (closed) return
        val part = partFor(monitor, Rect(0, 0, width, height)) ?: return
        if (!part.failed) render(monitor.name, part)
    }

    /**
     * Starts its scenes over (made anew at the next frame): for content that is something else
     * now (another menu), state kept with `remember` starts over too. Done just after (so it
     * may be called from inside its content); call it on the compositor's thread.
     */
    fun restart() {
        post(Runnable {
            if (closed) return@Runnable
            log.trace { "A Compose widget starts over" }
            for (name in parts.keys.toList()) drop(name)
            // (Started over by hand: failures before do not count, none waits.)
            restartAt.clear()
            failures = 0
            // (The new scenes are entered anew by the next move; the keys go where the next click is.)
            hoverOn = null
            keysOn = null
            last = null
            desktop.requestRedraw()
        })
    }

    private fun drop(name: String) {
        val part = parts.remove(name) ?: return
        part.scene.close()
        part.texture?.close()
    }

    override fun toString(): String = "ComposeWidget@${Integer.toHexString(hashCode())}" +
        parts.entries.joinToString(prefix = " (", postfix = ")") { (name, part) -> "$name ${part.width}x${part.height}" }

    // One global write observer for every Compose widget: a write (from any thread) sends the
    // apply notifications once on the compositor's thread, and asks for a frame on each desktop.
    private object Writes {
        private val live = java.util.concurrent.CopyOnWriteArrayList<ComposeWidget>()
        private val pending = AtomicBoolean(false)
        private var observer: androidx.compose.runtime.snapshots.ObserverHandle? = null

        @Synchronized
        fun add(widget: ComposeWidget) {
            live += widget
            if (observer == null) observer = Snapshot.registerGlobalWriteObserver { written() }
        }

        @Synchronized
        fun remove(widget: ComposeWidget) {
            live -= widget
            if (live.isEmpty()) {
                observer?.dispose()
                observer = null
            }
        }

        private fun written() {
            if (!pending.compareAndSet(false, true)) return
            val first = live.firstOrNull() ?: return pending.set(false)
            first.post(Runnable {
                pending.set(false)
                Snapshot.sendApplyNotifications()
                for (desktop in live.filter { !it.closed }.map { it.desktop }.distinct()) desktop.requestRedraw()
            })
        }
    }

    private companion object {
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        const val BTN_MIDDLE = 274
        const val BTN_SIDE = 275
        const val BTN_EXTRA = 276

        // Units of the compositor's scrolling in one notch of a wheel (Wayland's 15).
        const val WHEEL_UNITS = 15.0

        // As see-through as this: not seen (not drawn, no frames for it, no clicks).
        const val HIDDEN = 0.002f

        // Compose's pointer shapes (Java's cursors on the desktop) and the compositor's names.
        val CURSORS = listOf(
            java.awt.Cursor.DEFAULT_CURSOR to "default", java.awt.Cursor.TEXT_CURSOR to "text", java.awt.Cursor.HAND_CURSOR to "pointer",
            java.awt.Cursor.CROSSHAIR_CURSOR to "crosshair", java.awt.Cursor.MOVE_CURSOR to "move", java.awt.Cursor.WAIT_CURSOR to "wait",
            java.awt.Cursor.N_RESIZE_CURSOR to "n-resize", java.awt.Cursor.S_RESIZE_CURSOR to "s-resize", java.awt.Cursor.E_RESIZE_CURSOR to "e-resize",
            java.awt.Cursor.W_RESIZE_CURSOR to "w-resize", java.awt.Cursor.NE_RESIZE_CURSOR to "ne-resize", java.awt.Cursor.NW_RESIZE_CURSOR to "nw-resize",
            java.awt.Cursor.SE_RESIZE_CURSOR to "se-resize", java.awt.Cursor.SW_RESIZE_CURSOR to "sw-resize",
        )
    }
}
