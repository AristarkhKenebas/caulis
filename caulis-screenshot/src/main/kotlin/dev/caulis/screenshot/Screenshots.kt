package dev.caulis.screenshot

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

/**
 * Screenshots, in the compositor itself: the screen stands still at once (nothing moves
 * on while you choose), a toolbar at the bottom chooses a part, a window or a monitor, the
 * pointer in it or not, and a timer. What is taken is saved as PNG ([folder]) and put on the
 * clipboard ([copy]), and a notice says so.
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
 *
 * The same as actions for config.json, Lua and keys, when this module is in the compositor:
 * `screenshot` (the chooser), `screenshot region|window|screen`, `screenshot focused` (the
 * focused window at once), `screenshot monitor` (the monitor with the pointer at once).
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

    /** Says that one was taken (a notice). */
    var notify = true

    /** Called with each one taken: where it was saved (null: not saved). */
    var onTaken: (Path?) -> Unit = {}

    /** How the chooser looks (its toolbar's colours, corners, font, words). */
    var style = ScreenshotStyle()

    /** The kind the chooser starts with (the one used last). */
    var kind = ShotKind.REGION

    /**
     * The kind it starts with over an overview (windows drawn smaller than they are):
     * [ShotKind.WINDOWS] by default; null: the same as anywhere ([kind]).
     */
    var overviewKind: ShotKind? = ShotKind.WINDOWS

    /** How a part is chosen: [Selection.ADJUST] (dragged and resized after) or [Selection.NEW] (each drag a new one). */
    enum class Selection { ADJUST, NEW }

    /** How a part is chosen (the chooser's toolbar changes it too). */
    var selection = Selection.ADJUST

    /**
     * A part with windows drawn smaller than they are (an overview, a zoomed-out view) is put
     * together from the windows themselves at their own size: sharp, as if each filled the
     * screen, in the places they have on screen. False: the screen as it looks.
     */
    var sharpWindows = true

    /** In a picture put together from windows: their corners' radius (desktop units) and what is behind them (null: see-through). */
    var sharpCornerRadius = 0f
    var sharpBackground: dev.caulis.render.Color? = null

    /**
     * Without a [sharpBackground]: the screen as it was behind the windows (the wallpaper,
     * made as big as the picture) instead of see-through gaps (which viewers show white).
     */
    var sharpWallpaper = true

    /** The file's name: {date} (2026-10-26), {time} (14-03-22), {kind} (region, window, screen). */
    var fileName = "Screenshot from {date} {time}"

    /** "png" or "jpg" (smaller, no see-through; [jpegQuality] 0..1). */
    var format = "png"
    var jpegQuality = 0.92f

    /** A command run with each file saved ({file} is the file; else it is added at the end): an editor, an uploader. */
    var then: String? = null

    // The part chosen last (the chooser offers it again), per monitor.
    internal val lastRegion = HashMap<String, Rect>()

    internal val log = Logger("screenshot")

    /**
     * Binds the keys: [choose] opens the chooser, [screen] takes the monitor with the pointer
     * at once, [window] the focused window at once (null: not bound). A laptop's own
     * screenshot key (XF86SelectiveScreenshot) opens the chooser too.
     */
    fun keys(choose: String? = "Print", screen: String? = "Shift+Print", window: String? = "Alt+Print") {
        keysChosen = true
        choose?.let { caulis.bind(it, BindOptions(description = "screenshot")) { choose() } }
        screen?.let { caulis.bind(it, BindOptions(description = "screenshot of the screen")) { takeMonitor() } }
        window?.let { caulis.bind(it, BindOptions(description = "screenshot of the window")) { takeFocused() } }
        if (choose != null) caulis.bind("XF86SelectiveScreenshot", BindOptions(description = "screenshot")) { choose() }
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
    }

    /** Takes the monitor with the pointer now (after [delaySeconds]). */
    fun takeMonitor() = later { caulis.desktop.let { d -> takeScreen(d.monitorAt(d.pointerX, d.pointerY)) } }

    /** Takes the focused window now (after [delaySeconds]); nothing if none has the focus. */
    fun takeFocused() = later {
        val window = caulis.focusedWindow
        if (window == null) caulis.desktop.showLevel(style.word("No window has the focus")) else takeWindow(window)
    }

    // --- taking -----------------------------------------------------------------------------

    internal fun takeScreen(monitor: Monitor) {
        Frozen.capture(caulis.desktop, monitor, pointer).use { frozen -> deliver(frozen.read(null), "screen ${monitor.name}") }
    }

    internal fun takeWindow(window: Window) {
        val desktop = caulis.desktop
        val (width, height) = desktop.windowPixelSize(window)
        val target = desktop.renderer.createTarget(width, height)
        try {
            desktop.captureWindow(window, target)
            deliver(Pixels.read(target, null, transparent = true), "window ${window.appId}")
        } finally {
            target.close()
        }
    }

    // Waits [delaySeconds] (counting down on screen), then [take].
    internal fun later(take: () -> Unit) {
        val seconds = delaySeconds
        if (seconds <= 0) return take()
        for (left in seconds downTo 1) caulis.after((seconds - left) * 1000L) { desktop.showLevel(style.word("Screenshot in %s").replace("%s", left.toString())) }
        caulis.after(seconds * 1000L + 150) { take() } // (the countdown gone from the screen first)
    }

    internal fun deliver(picture: Pixels, what: String, copyOnly: Boolean = false) = deliver({ picture }, what, copyOnly)

    // Makes the picture ([make]: putting windows together), saves and copies it on a thread of
    // its own (PNG takes a moment), then says so.
    internal fun deliver(make: () -> Pixels, what: String, copyOnly: Boolean = false) {
        val now = LocalDateTime.now()
        val jpeg = format.lowercase().let { it == "jpg" || it == "jpeg" }
        val name = fileName.replace("{date}", now.format(DATE)).replace("{time}", now.format(TIME)).replace("{kind}", what.substringBefore(' '))
            .replace('/', '-').ifBlank { "Screenshot from ${now.format(FILE_TIME)}" }
        val file = if (save && !copyOnly) folder.resolve("$name.${if (jpeg) "jpg" else "png"}") else null
        val copying = copy || copyOnly
        Thread({
            try {
                val picture = make()
                val png = picture.png()
                val saved = file?.let {
                    Files.createDirectories(it.parent)
                    uniqueName(it).also { path -> Files.write(path, if (jpeg) picture.jpeg(jpegQuality) else png) }
                }
                caulis.post {
                    if (copying) clipboard.setClipboard(PngOnClipboard(png))
                    log.info { "Took $what (${picture.width}x${picture.height})${file?.let { " → $it" } ?: ""}${if (copying) ", copied" else ""}" }
                    if (notify) notify(listOfNotNull(file?.let { "Saved to ${shortPath(it)}" }, if (copying) "Copied" else null).joinToString(" · "), title = "Screenshot")
                    onTaken(saved)
                    // Then: the configured command with it (an editor).
                    val command = then?.takeIf { it.isNotBlank() }
                    if (command != null && saved != null) {
                        val quoted = "'" + saved.toString().replace("'", "'\\''") + "'"
                        caulis.launch(if ("{file}" in command) command.replace("{file}", quoted) else "$command $quoted")
                    }
                }
            } catch (e: Exception) {
                log.error("The screenshot could not be saved", e)
                caulis.post { notify("The screenshot could not be saved: ${e.message}", title = "Screenshot") }
            }
        }, "caulis-screenshot").apply { isDaemon = true }.start()
    }

    private fun uniqueName(file: Path): Path {
        if (!Files.exists(file)) return file
        val ext = file.fileName.toString().substringAfterLast('.', "png")
        val base = file.fileName.toString().removeSuffix(".$ext")
        return (2..999).asSequence().map { file.resolveSibling("$base ($it).$ext") }.firstOrNull { !Files.exists(it) } ?: file
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
     * shrunk window needs to be at its own size; at most 8, at most 16384 pixels across).
     */
    internal fun plan(monitor: Monitor, shown: List<Pair<Window, Rect>>, region: Rect?): Pair<Rect, Double>? {
        if (shown.isEmpty()) return null
        val part = region ?: shown.map { it.second }.reduce(Rect::union)
        val desktop = caulis.desktop
        val factor = shown.maxOf { (w, r) -> desktop.windowPixelSize(w).first.toDouble() / r.width.coerceAtLeast(1) }
            .coerceAtLeast(monitor.scale).coerceAtMost(8.0).coerceAtMost(16384.0 / maxOf(part.width, part.height))
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

    /**
     * [chosen] of [monitor] put together from the windows in it at their own size (see
     * [sharpWindows]), or null when none is drawn smaller there (the screen is as sharp).
     */
    internal fun sharpRegion(monitor: Monitor, chosen: Rect, windows: List<Pair<Window, Rect>>, screen: Frozen?): Composite? {
        val shown = windows.filter { it.second.intersect(chosen) != null }
        if (shown.none { (w, r) -> shrunk(w, r, monitor) }) return null
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
        val pieces = listOfNotNull(backdrop) + shown.map { (window, rect) ->
            val (pw, ph) = desktop.windowPixelSize(window)
            val target = desktop.renderer.createTarget(pw, ph)
            val pixels = try {
                desktop.captureWindow(window, target)
                Pixels.read(target, null, transparent = true)
            } finally {
                target.close()
            }
            val w = rect.width * factor
            // Its corners as round as on screen at its own size.
            val radius = sharpCornerRadius * monitor.scale * (w / pw)
            Composite.Piece(pixels, (rect.x - part.x) * factor, (rect.y - part.y) * factor, w, rect.height * factor, radius)
        }
        log.info { "Putting together ${shown.size} windows at ${String.format("%.2f", factor)}x: ${width}x$height" }
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
        private val installed = WeakHashMap<Caulis, Screenshots>()

        /** The screenshots of [caulis] (made the first time, without keys). */
        @JvmStatic
        fun of(caulis: Caulis): Screenshots = synchronized(installed) { installed.getOrPut(caulis) { Screenshots(caulis) } }

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
