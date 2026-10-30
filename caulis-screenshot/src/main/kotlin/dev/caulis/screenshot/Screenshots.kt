package dev.caulis.screenshot

import dev.caulis.core.BytesOnClipboard
import dev.caulis.core.Monitor
import dev.caulis.core.Window
import dev.caulis.geometry.Rect
import dev.caulis.log.Logger
import dev.caulis.wm.BindOptions
import dev.caulis.wm.Caulis
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap

/** What a screenshot shows. */
enum class ShotKind {
    /** A part of a monitor, chosen with the mouse (or the keyboard). */
    REGION,

    /** One window, whole — even the parts other windows cover. */
    WINDOW,

    /** One monitor. */
    SCREEN,

    /**
     * Several windows, each whole (from edge to edge, never cut), put together at their own
     * size in the places they have on screen: chosen in an overview (windows drawn smaller),
     * by dragging a frame over them or clicking them.
     */
    WINDOWS,
}

/** The kind of file a screenshot is saved as. */
enum class ShotFormat(
    /** The file's ending. */
    val extension: String,
) {
    /** Exactly as on screen, see-through parts kept. */
    PNG("png"),

    /** Smaller, a little blurred ([Screenshots.jpegQuality]); see-through parts on white. */
    JPEG("jpg");

    companion object {
        /** The format named [name] ("png", "jpg", "jpeg"; any case), or null if there is none of that name. */
        @JvmStatic
        fun parse(name: String): ShotFormat? = when (name.trim().lowercase()) {
            "png" -> PNG
            "jpg", "jpeg" -> JPEG
            else -> null
        }
    }
}

/**
 * Screenshots, in the compositor itself: the screen stands still at once (nothing moves
 * on while you choose), a toolbar at the bottom chooses a part, a window or a monitor, the
 * pointer in it or not, and a timer. What is taken is saved ([folder], as [format]) and put
 * on the clipboard ([copy]), and a notice says so. Windows hidden from capture
 * (`hiddenFromCapture` in a rule) are black boxes in every picture, and are not taken alone.
 *
 * ```
 * screenshots()                                   // Print, Shift+Print, Alt+Print
 * screenshots { folder = Path.of("/tmp"); copy = false }
 * ```
 *
 * The chooser: drag to choose a part (then drag it, or its edges and corners, to change it —
 * or, with [selection] NEW, each drag chooses a new one; the arrow keys move it, with Shift
 * by ten, with Ctrl they resize it), Tab for the next kind, Enter or a double click takes it,
 * Ctrl+C only copies it, Escape cancels. A window or a monitor is taken with a click. Over
 * an overview (windows drawn smaller) there is one kind more, [ShotKind.WINDOWS]: drag a
 * frame over windows (Shift adds to them), click one to add or drop it, Ctrl+A takes all.
 * Its keys, buttons and timings are [chooser]'s, its look and words [style]'s.
 *
 * The same as actions for config.json, Lua and keys, when this module is in the compositor:
 * `screenshot` (the chooser), `screenshot region|window|screen|windows` (the chooser starting
 * with that kind), `screenshot focused` (the focused window at once), `screenshot monitor`
 * (the monitor with the pointer at once).
 *
 * Its settings and functions are for the compositor's thread.
 */
class Screenshots internal constructor(private val caulis: Caulis) {

    /** Where pictures are saved: the pictures folder's Screenshots (XDG_PICTURES_DIR). */
    var folder: Path = picturesFolder().resolve("Screenshots")

    /** Save each one as a file. */
    var save = true

    /** Put each one on the clipboard (as PNG). */
    var copy = true

    /** The pointer is in the picture (the chooser's toolbar changes it too). */
    var pointer = false

    /** Seconds to wait before taking it (0: at once; the chooser's toolbar changes it too). */
    var delaySeconds = 0

    /**
     * Shows the seconds left before it is taken: called with each (3, 2, 1), then with 0 just
     * before it is taken — take yours away then (it is not left out of the picture by itself).
     * Null: the screenshots' own pill, never in the picture.
     */
    var onCountdown: ((secondsLeft: Int) -> Unit)? = null

    /** Says that one was taken (a notice). */
    var notify = true

    /** Called with each one taken: where it was saved (null: not saved). */
    var onTaken: (Path?) -> Unit = {}

    /** How the chooser looks (its toolbar's colours, corners, font, sizes) and its [ScreenshotStyle.words]. */
    var style = ScreenshotStyle()

    /** How the chooser behaves: its buttons, keys, timer steps and timings. */
    var chooser = ChooserBehaviour()

    /** The kind the chooser starts with ([ShotKind.REGION]; the one used last if [ChooserBehaviour.rememberKind]). */
    var kind = ShotKind.REGION

    /**
     * The kind it starts with over an overview (windows drawn smaller than they are):
     * [ShotKind.WINDOWS] by default; null: the same as anywhere ([kind]).
     */
    var overviewKind: ShotKind? = ShotKind.WINDOWS

    /** How a part is chosen. */
    enum class Selection {
        /** Drawn once, then dragged and resized by its edges and corners (a new one: drag outside it). */
        ADJUST,

        /** Each drag draws a new one. */
        NEW,
    }

    /** How a part is chosen (the chooser's toolbar changes it too). */
    var selection = Selection.ADJUST

    /**
     * A part with windows drawn smaller than they are (an overview, a zoomed-out view) is put
     * together from the windows themselves at their own size: sharp, as if each filled the
     * screen, in the places they have on screen. False: the screen as it looks. (No pointer
     * in such a picture.)
     */
    var sharpWindows = true

    /** In a picture put together from windows: their corners' radius (desktop units). */
    var sharpCornerRadius = 0f

    /**
     * In a picture put together from windows: the colour behind them, or null — the screen as
     * it was behind them with [sharpWallpaper], else see-through.
     */
    var sharpBackground: dev.caulis.render.Color? = null

    /**
     * Without a [sharpBackground]: the screen as it was behind the windows (the wallpaper,
     * made as big as the picture) instead of see-through gaps (which viewers show white).
     */
    var sharpWallpaper = true

    /**
     * At most this many pixels in a picture put together from windows (it is made as big as
     * the most shrunk window needs; each pixel takes 4 bytes, a few times over while it is made).
     */
    var maxPixels = 32_000_000

    /** The file's name: {date} (2026-10-26), {time} (14-03-22), {kind} (region, window, screen, windows). */
    var fileName = "Screenshot from {date} {time}"

    /** The kind of file saved (what is copied is always PNG). */
    var format = ShotFormat.PNG

    /** How good a JPEG is (0..1: smaller files to sharper pictures). */
    var jpegQuality = 0.92f

    /** A command run with each file saved ({file} is the file; else it is added at the end): an editor, an uploader. */
    var then: String? = null

    /**
     * Where the part chosen last is kept across restarts (with
     * [ChooserBehaviour.rememberAcrossRestarts]); null: `$XDG_STATE_HOME/<configName>/screenshot-selection`
     * (`~/.local/state/...`).
     */
    var selectionFile: Path? = null

    // The part chosen last: on which monitor, and the monitors then ([monitorsNow]): offered
    // again while they are the same.
    internal data class Remembered(val monitors: String, val monitor: String, val part: Rect)

    // It, and whether its file was read: kept with the desktop, so a reload of the
    // configuration (a new Caulis, new screenshots) keeps it too.
    internal class PartMemory {
        var remembered: Remembered? = null
        var loaded = false
    }

    private val memory: PartMemory get() = synchronized(memories) { memories.getOrPut(caulis.desktop) { PartMemory() } }

    internal var remembered: Remembered?
        get() = memory.remembered
        set(value) {
            memory.remembered = value
        }

    internal val log = Logger("screenshot")

    /**
     * Binds the keys: [choose] opens the chooser, [screen] takes the monitor with the pointer
     * at once, [window] the focused window at once, [selective] (a laptop's own screenshot
     * key) opens the chooser too. Null: not bound.
     */
    @JvmOverloads
    fun keys(choose: String? = "Print", screen: String? = "Shift+Print", window: String? = "Alt+Print", selective: String? = "XF86SelectiveScreenshot") {
        keysChosen = true
        choose?.let { caulis.bind(it, BindOptions(description = "screenshot")) { choose() } }
        screen?.let { caulis.bind(it, BindOptions(description = "screenshot of the screen")) { takeMonitor() } }
        window?.let { caulis.bind(it, BindOptions(description = "screenshot of the window")) { takeFocused() } }
        selective?.let { caulis.bind(it, BindOptions(description = "screenshot")) { choose() } }
    }

    internal var keysChosen = false

    /**
     * Opens the chooser, starting with [start] (null: the one used last, over an overview
     * [overviewKind]).
     */
    @JvmOverloads
    fun choose(start: ShotKind? = null) {
        val desktop = caulis.desktop
        if (desktop.locked || desktop.overlay is ScreenshotOverlay) return
        val began = System.nanoTime()
        val frozen = desktop.monitors.associateWith { Frozen.capture(desktop, it) }
        // The windows where they are now (the picture stands still, so do they).
        val windows = desktop.monitors.associateWith { windowsShown(it) }
        val overview = windows.any { (monitor, list) -> list.any { (w, r) -> shrunk(w, r, monitor) } }
        val first = start ?: (if (overview) overviewKind else null) ?: kind
        desktop.openOverlay(ScreenshotOverlay(caulis, this, frozen, windows, overview, if (first == ShotKind.WINDOWS && !overview) ShotKind.REGION else first))
        log.trace { "The chooser opened in ${(System.nanoTime() - began) / 1_000_000} ms${if (overview) " (over an overview)" else ""}" }
    }

    init {
        warmUpDrawing(style)
        // (Held by its Caulis while that lives — the map of them holds it weakly only, and its
        // settings would go with it.)
        caulis.onUnload { log.trace { "The screenshots' configuration is unloaded" } }
    }

    /** Takes the monitor with the pointer now (after [delaySeconds]). */
    fun takeMonitor() = later { caulis.desktop.let { d -> takeScreen(d.monitorAt(d.pointerX, d.pointerY)) } }

    /** Takes the focused window now (after [delaySeconds]); nothing if none has the focus. */
    fun takeFocused() = later {
        val window = caulis.focusedWindow
        if (window == null) caulis.desktop.showLevel(style.words.noFocusedWindow) else takeWindow(window)
    }

    // --- the part chosen last ----------------------------------------------------------------

    // The monitors as they are now, as one line: their names and places (desktop units).
    internal fun monitorsNow(): String = caulis.desktop.monitors.sortedBy { it.name }
        .joinToString("; ") { m -> m.area.let { "${m.name} ${it.x},${it.y} ${it.width}x${it.height}" } }

    private fun selectionPath(): Path = selectionFile ?: run {
        val home = System.getenv("HOME")?.takeIf { it.startsWith("/") } ?: System.getProperty("user.home")
        val state = System.getenv("XDG_STATE_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.local/state"
        Path.of(state, caulis.configName, "screenshot-selection")
    }

    /**
     * The part chosen last and its monitor, to be offered again (see
     * [ChooserBehaviour.rememberSelection]), clamped to the monitor; null when there is none or
     * the monitors changed since (then it is dropped).
     */
    internal fun rememberedPart(): Pair<Monitor, Rect>? {
        if (!chooser.rememberSelection) return null
        if (!memory.loaded && chooser.rememberAcrossRestarts) {
            memory.loaded = true
            if (remembered == null) remembered = loadPart()
        }
        val last = remembered ?: return null
        val monitor = caulis.desktop.monitors.firstOrNull { it.name == last.monitor }
        val part = if (last.monitors == monitorsNow()) monitor?.let { last.part.intersect(it.area) } else null
        if (monitor == null || part == null) {
            log.info { "The part chosen last is not offered again: the monitors changed" }
            forgetPart()
            return null
        }
        return monitor to part
    }

    // (For tests: the part remembered only in its file, as after a restart.)
    internal fun asAfterRestart() {
        memory.remembered = null
        memory.loaded = false
    }

    // Remembers [part] of [monitor] to offer it again (and keeps it across restarts, if told to).
    internal fun rememberPart(monitor: Monitor, part: Rect) {
        if (!chooser.rememberSelection) return
        val now = Remembered(monitorsNow(), monitor.name, part)
        if (now == remembered) return
        remembered = now
        log.trace { "The part chosen is remembered: ${part.width}x${part.height} on ${monitor.name}" }
        if (chooser.rememberAcrossRestarts) savePart(now)
    }

    // Forgets the part chosen last (none is offered next time).
    internal fun forgetPart() {
        if (remembered == null) return
        remembered = null
        log.trace { "The part chosen last is forgotten" }
        if (chooser.rememberAcrossRestarts) {
            try {
                Files.deleteIfExists(selectionPath())
            } catch (e: Exception) {
                log.warn("Could not delete ${selectionPath()}: ${e.message}")
            }
        }
    }

    // The file: the monitors, the monitor, "x y width height" — a line each.
    private fun savePart(part: Remembered) {
        val file = selectionPath()
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, "${part.monitors}\n${part.monitor}\n${part.part.x} ${part.part.y} ${part.part.width} ${part.part.height}\n")
        } catch (e: Exception) {
            log.warn("Could not keep the part chosen in $file: ${e.message}")
        }
    }

    private fun loadPart(): Remembered? {
        val file = selectionPath()
        if (!Files.isRegularFile(file)) return null
        return try {
            val lines = Files.readAllLines(file)
            val n = lines.getOrNull(2)?.trim()?.split(' ')?.map(String::toInt)
            if (lines.size < 3 || n == null || n.size != 4 || n[2] <= 0 || n[3] <= 0) null
            else Remembered(lines[0], lines[1], Rect(n[0], n[1], n[2], n[3]))
        } catch (e: Exception) {
            null
        }.also { log.trace { if (it != null) "The part chosen before the restart is offered again" else "$file is not a part chosen: ignored" } }
    }

    // --- taking -----------------------------------------------------------------------------

    internal fun takeScreen(monitor: Monitor, copyOnly: Boolean = false) {
        Frozen.capture(caulis.desktop, monitor, pointer).use { frozen -> deliver(frozen.read(null), "screen ${monitor.name}", ShotKind.SCREEN, copyOnly) }
    }

    // True if [window] is to be black in screenshots (a rule's hiddenFromCapture).
    internal fun hidden(window: Window): Boolean = caulis.desktop.behaviorOf(window).hiddenFromCapture

    internal fun takeWindow(window: Window, copyOnly: Boolean = false) {
        // (Gone meanwhile — closed during a countdown: nothing, not an empty picture.)
        if (window !in caulis.desktop.allWindows) return log.info { "The window to take is gone" }
        val desktop = caulis.desktop
        // (Hidden from screenshots: not taken, only said — a black picture would be no use.)
        if (hidden(window)) {
            log.info { "Not taken: ${window.appId} is hidden from screenshots" }
            return desktop.showLevel(style.words.hiddenWindow)
        }
        val (width, height) = desktop.windowPixelSize(window)
        val target = desktop.renderer.createTarget(width, height)
        try {
            desktop.captureWindow(window, target)
            deliver(Pixels.read(target, null, transparent = true), "window ${window.appId}", ShotKind.WINDOW, copyOnly)
        } finally {
            target.close()
        }
    }

    // Waits [delaySeconds] (counting down on screen), then [take].
    internal fun later(take: () -> Unit) {
        val seconds = delaySeconds
        if (seconds <= 0) return take()
        val desktop = caulis.desktop
        val own = onCountdown
        val pill = if (own == null) Countdown(desktop, style) else null
        val shown = pill?.let(desktop::addWidget)
        fun show(left: Int) {
            if (own != null) runCatching { own(left) }.onFailure { log.error("Your countdown (onCountdown) failed", it) } else pill?.left = left
        }
        log.info { "A screenshot in $seconds s" }
        val timers = ArrayList<AutoCloseable>()
        // A reload during the countdown cancels this configuration's timers: the countdown
        // goes with them (not left on screen for ever), and nothing is taken.
        // (Not taken off the list while it is being run: the configuration goes anyway.)
        val unloading = caulis.onUnload {
            timers.forEach(AutoCloseable::close)
            show(0)
            shown?.close()
            log.info { "The configuration was reloaded during the countdown: no screenshot" }
        }
        for (left in seconds downTo 1) timers += caulis.after((seconds - left) * 1000L) { show(left) }
        timers += caulis.after(seconds * 1000L) {
            unloading.close()
            // The countdown gone first: it is not in the picture.
            show(0)
            shown?.close()
            // (Locked meanwhile: nothing is taken — the windows are not to be seen now.)
            if (desktop.locked) return@after log.info { "Locked during the countdown: no screenshot" }
            take()
        }
    }

    internal fun deliver(picture: Pixels, what: String, kind: ShotKind, copyOnly: Boolean = false) = deliver({ picture }, what, kind, copyOnly)

    /** The file a picture of [kind] taken at [now] is saved as in [folder] (a free name is looked for when it is written). */
    internal fun fileFor(now: LocalDateTime, kind: ShotKind): Path {
        val name = fileName.replace("{date}", now.format(DATE)).replace("{time}", now.format(TIME)).replace("{kind}", kind.name.lowercase())
            .replace('/', '-').ifBlank { "Screenshot from ${now.format(FILE_TIME)}" }
        return folder.resolve("$name.${format.extension}")
    }

    // Makes the picture ([make]: putting windows together), saves and copies it on a thread of
    // its own (encoding takes a moment), then says so.
    internal fun deliver(make: () -> Pixels, what: String, kind: ShotKind, copyOnly: Boolean = false) {
        val file = if (save && !copyOnly) fileFor(LocalDateTime.now(), kind) else null
        val copying = copy || copyOnly
        val format = format
        val quality = jpegQuality
        val words = style.words
        Thread({
            try {
                val picture = make()
                // (A PNG only when one is wanted: copied, or saved as one.)
                val png = if (copying || (file != null && format == ShotFormat.PNG)) picture.png() else null
                val saved = file?.let {
                    Files.createDirectories(it.parent)
                    writeNew(it, if (format == ShotFormat.PNG && png != null) png else picture.encode(format, quality))
                }
                caulis.post {
                    // (Written to whoever pastes it by the compositor's few clipboard writers: a
                    // program asking again and again, or never reading, holds no thread of ours.)
                    if (png != null && copying) clipboard.setClipboard(BytesOnClipboard("image/png", png))
                    log.info { "Took $what (${picture.width}x${picture.height})${saved?.let { " → $it" } ?: ""}${if (copying) ", copied" else ""}" }
                    if (notify) notify(listOfNotNull(saved?.let { words.savedTo.with(shortPath(it)) }, if (copying) words.copied else null).joinToString(" · "), title = words.title)
                    onTaken(saved)
                    // Then: the configured command with it (an editor).
                    val command = then?.takeIf { it.isNotBlank() }
                    if (command != null && saved != null) {
                        val quoted = "'" + saved.toString().replace("'", "'\\''") + "'"
                        caulis.launch(if ("{file}" in command) command.replace("{file}", quoted) else "$command $quoted")
                    }
                }
            } catch (e: Throwable) {
                // (Out of memory too — a huge picture: said, not a thread gone without a word.)
                log.error("The screenshot could not be saved", e)
                caulis.post { notify(words.notSaved.with(e.message ?: e.javaClass.simpleName), title = words.title) }
                if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            }
        }, "caulis-screenshot").apply { isDaemon = true }.start()
    }

    // The windows on [monitor] as drawn now, bottom to top, and where.
    internal fun windowsShown(monitor: Monitor): List<Pair<Window, Rect>> {
        val desktop = caulis.desktop
        val order = desktop.stacking
        val drawn = desktop.allWindows.filter(desktop::drawnNow).sortedBy { w -> order.indexOf(w).let { if (it < 0) Int.MAX_VALUE else it } }
        return drawn.mapNotNull { w -> desktop.shownRect(w)?.takeIf { it.width > 0 && it.height > 0 && it.intersect(monitor.area) != null }?.let { w to it } }
    }

    // True if [window] is drawn at [rect] smaller than it is (an overview).
    internal fun shrunk(window: Window, rect: Rect, monitor: Monitor): Boolean =
        caulis.desktop.windowPixelSize(window).first.toDouble() / rect.width.coerceAtLeast(1) > monitor.scale * 1.05

    /**
     * How a picture of [shown] windows is put together: the part it shows (desktop units; all
     * of them whole when [region] is null) and how many pixels a unit is (as many as the most
     * shrunk window needs to be at its own size; at most 8, at most 16384 pixels across, at
     * most [maxPixels] in all).
     */
    internal fun plan(monitor: Monitor, shown: List<Pair<Window, Rect>>, region: Rect?): Pair<Rect, Double>? {
        if (shown.isEmpty()) return null
        val part = region ?: shown.map { it.second }.reduce(Rect::union)
        val desktop = caulis.desktop
        val factor = plan(part, monitor.scale, shown.maxOf { (w, r) -> desktop.windowPixelSize(w).first.toDouble() / r.width.coerceAtLeast(1) }, maxPixels)
        return part to factor
    }

    /** The picture's size in pixels for [shown] windows, whole. */
    internal fun windowsSize(monitor: Monitor, shown: List<Pair<Window, Rect>>): Pair<Int, Int>? =
        plan(monitor, shown, null)?.let { (part, factor) -> Math.round(part.width * factor).toInt() to Math.round(part.height * factor).toInt() }

    /**
     * [shown] windows, each whole, at their own size in the places they have (see
     * [ShotKind.WINDOWS]). The windows are drawn now; putting them together is left to [Composite.draw]
     * (on another thread).
     */
    internal fun windowsPicture(monitor: Monitor, shown: List<Pair<Window, Rect>>, screen: Frozen?): Composite? {
        val (part, factor) = plan(monitor, shown, null) ?: return null
        return composite(monitor, shown, part, factor, sharpBackground, screen.takeIf { sharpBackground == null && sharpWallpaper })
    }

    /** True if [chosen] of [monitor] is put together from windows (some drawn smaller there: see [sharpWindows]). */
    internal fun sharpThere(monitor: Monitor, chosen: Rect, windows: List<Pair<Window, Rect>>): Boolean =
        sharpWindows && windows.any { (w, r) -> r.intersect(chosen) != null && shrunk(w, r, monitor) }

    /**
     * [chosen] of [monitor] put together from the windows in it at their own size (see
     * [sharpWindows]), or null when none is drawn smaller there (the screen is as sharp).
     */
    internal fun sharpRegion(monitor: Monitor, chosen: Rect, windows: List<Pair<Window, Rect>>, screen: Frozen?): Composite? {
        if (!sharpThere(monitor, chosen, windows)) return null
        val shown = windows.filter { it.second.intersect(chosen) != null }
        val behind = screen.takeIf { sharpBackground == null && sharpWallpaper }
        // See-through behind them: only as big as the windows in the part (no empty margins).
        val region = if (sharpBackground != null || behind != null) chosen else shown.mapNotNull { it.second.intersect(chosen) }.reduce(Rect::union)
        val (part, factor) = plan(monitor, shown, region) ?: return null
        return composite(monitor, shown, part, factor, sharpBackground, behind)
    }

    private fun composite(monitor: Monitor, shown: List<Pair<Window, Rect>>, part: Rect, factor: Double, background: dev.caulis.render.Color?, behind: Frozen?): Composite {
        val desktop = caulis.desktop
        val width = Math.round(part.width * factor).toInt().coerceAtLeast(1)
        val height = Math.round(part.height * factor).toInt().coerceAtLeast(1)
        // The screen behind them first (the wallpaper in the gaps), made as big as the rest.
        val backdrop = behind?.let { frozen ->
            part.intersect(frozen.area)?.let { seen ->
                Composite.Piece(frozen.read(seen, pointer = false), (seen.x - part.x) * factor, (seen.y - part.y) * factor, seen.width * factor, seen.height * factor, 0.0)
            }
        }
        var black = 0
        val pieces = listOfNotNull(backdrop) + shown.map { (window, rect) ->
            val (pw, ph) = desktop.windowPixelSize(window)
            // (A window hidden from screenshots: a black box in its place, as on a shared screen.)
            val pixels = if (hidden(window)) null.also { black++ } else {
                val target = desktop.renderer.createTarget(pw, ph)
                try {
                    desktop.captureWindow(window, target)
                    Pixels.read(target, null, transparent = true)
                } finally {
                    target.close()
                }
            }
            val w = rect.width * factor
            // Its corners as round as on screen at its own size.
            val radius = sharpCornerRadius * monitor.scale * (w / pw)
            Composite.Piece(pixels, (rect.x - part.x) * factor, (rect.y - part.y) * factor, w, rect.height * factor, radius)
        }
        log.info { "Putting together ${shown.size} windows at ${String.format("%.2f", factor)}x: ${width}x$height${if (black > 0) " ($black hidden from screenshots: black)" else ""}" }
        return Composite(width, height, pieces, background)
    }

    private fun shortPath(file: Path): String {
        val home = Path.of(System.getenv("HOME")?.takeIf { it.startsWith("/") } ?: System.getProperty("user.home"))
        return if (file.startsWith(home)) "~/" + home.relativize(file) else file.toString()
    }

    companion object {
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME = DateTimeFormatter.ofPattern("HH-mm-ss")
        // (Held weakly both ways: the screenshots refer to their Caulis, which must still go away.)
        private val installed = WeakHashMap<Caulis, java.lang.ref.WeakReference<Screenshots>>()
        // The part chosen last, per desktop (it outlives reloads; nothing in it refers back).
        private val memories = WeakHashMap<dev.caulis.core.Desktop, PartMemory>()

        // Caulis 0.1.1's keys(...) with defaults left out (it had no selective key yet): what
        // Kotlin code built against it calls — the defaults' mask as Kotlin passes it. (It
        // cannot be a keys with defaults: keys(choose, screen, window) is there already.)
        @Deprecated("Kept for code built against Caulis 0.1.1", level = DeprecationLevel.HIDDEN)
        @JvmStatic
        @JvmName("keys\$default")
        fun keysWithDefaults011(shots: Screenshots, choose: String?, screen: String?, window: String?, defaults: Int, @Suppress("UNUSED_PARAMETER") marker: Any?) {
            shots.keys(
                if (defaults and 1 != 0) "Print" else choose,
                if (defaults and 2 != 0) "Shift+Print" else screen,
                if (defaults and 4 != 0) "Alt+Print" else window,
            )
        }

        /** The screenshots of [caulis] (made the first time, without keys). */
        @JvmStatic
        fun of(caulis: Caulis): Screenshots = synchronized(installed) {
            installed[caulis]?.get() ?: Screenshots(caulis).also { installed[caulis] = java.lang.ref.WeakReference(it) }
        }

        /**
         * How many pixels a unit is in a picture of [part] put together from windows: [wanted]
         * (what the most shrunk window needs), at least the monitor's [scale], at most 8, at most
         * 16384 pixels across and [maxPixels] in all.
         */
        internal fun plan(part: Rect, scale: Double, wanted: Double, maxPixels: Int): Double =
            wanted.coerceAtLeast(scale).coerceAtMost(8.0).coerceAtMost(16384.0 / maxOf(part.width, part.height))
                .coerceAtMost(Math.sqrt(maxPixels.coerceAtLeast(1).toDouble() / (part.width.toDouble() * part.height).coerceAtLeast(1.0)))

        // Writes [bytes] as [file], or "name (2).png", "name (3).png"... — never over a file there
        // (two screenshots in the same second keep both): the path written.
        internal fun writeNew(file: Path, bytes: ByteArray): Path {
            val ext = file.fileName.toString().substringAfterLast('.', "png")
            val base = file.fileName.toString().removeSuffix(".$ext")
            for (n in 1..9999) {
                val path = if (n == 1) file else file.resolveSibling("$base ($n).$ext")
                try {
                    Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
                    return path
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                }
            }
            throw java.io.IOException("no free name for $file")
        }

        // ~/Pictures, or what user-dirs.dirs says (XDG_PICTURES_DIR="$HOME/Bilder").
        internal fun picturesFolder(): Path {
            val home = System.getenv("HOME")?.takeIf { it.startsWith("/") } ?: System.getProperty("user.home")
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: "$home/.config"
            val named = try {
                Files.readAllLines(Path.of(config, "user-dirs.dirs")).firstNotNullOfOrNull { line ->
                    line.trim().takeIf { it.startsWith("XDG_PICTURES_DIR=") }?.substringAfter('=')?.trim('"')?.replace("\$HOME", home)
                }
            } catch (_: Exception) {
                null
            }
            return Path.of(named?.takeIf { it.startsWith("/") } ?: "$home/Pictures")
        }
    }

    // The names of the keys in the compositor's keyboard layout (the chooser's keys are looked up by them).
    internal fun keyNames(): Map<Int, String> =
        KeyNames.of(caulis.keyboardLayouts, caulis.keyboardVariants, caulis.keyboardOptions, caulis.keyboardModel, log)
}

/**
 * Screenshots in this compositor ([Screenshots]): Print opens the chooser, Shift+Print takes
 * the screen, Alt+Print the focused window. [configure] changes the settings (and
 * `keys(...)` the keys) — the keys are bound after it.
 */
fun Caulis.screenshots(configure: Screenshots.() -> Unit = {}): Screenshots {
    val shots = Screenshots.of(this)
    shots.configure()
    if (!shots.keysChosen) shots.keys()
    return shots
}
