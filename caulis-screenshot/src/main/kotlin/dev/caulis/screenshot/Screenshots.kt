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
 * The chooser: drag to choose a part (then drag it, or its edges and corners, to change it;
 * the arrow keys move it, with Shift by ten, with Ctrl they resize it), Tab for the next
 * kind, Enter or a double click takes it, Ctrl+C only copies it, Escape cancels. A window
 * or a monitor is taken with a click.
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
     * A part with windows drawn smaller than they are (an overview, a zoomed-out view) is put
     * together from the windows themselves at their own size: sharp, as if each filled the
     * screen, in the places they have on screen. False: the screen as it looks.
     */
    var sharpWindows = true

    /** In a picture put together from windows: their corners' radius (desktop units) and what is behind them (null: see-through). */
    var sharpCornerRadius = 0f
    var sharpBackground: dev.caulis.render.Color? = null

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

    /** Opens the chooser, starting with [start]. */
    @JvmOverloads
    fun choose(start: ShotKind = kind) {
        val desktop = caulis.desktop
        if (desktop.locked || desktop.overlay is ScreenshotOverlay) return
        kind = start
        val frozen = desktop.monitors.associateWith { Frozen.capture(desktop, it) }
        desktop.openOverlay(ScreenshotOverlay(caulis, this, frozen))
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

    // Saves and copies [picture] on a thread of its own (PNG takes a moment), then says so.
    internal fun deliver(picture: Pixels, what: String, copyOnly: Boolean = false) {
        val now = LocalDateTime.now()
        val jpeg = format.lowercase().let { it == "jpg" || it == "jpeg" }
        val name = fileName.replace("{date}", now.format(DATE)).replace("{time}", now.format(TIME)).replace("{kind}", what.substringBefore(' '))
            .replace('/', '-').ifBlank { "Screenshot from ${now.format(FILE_TIME)}" }
        val file = if (save && !copyOnly) folder.resolve("$name.${if (jpeg) "jpg" else "png"}") else null
        val copying = copy || copyOnly
        Thread({
            try {
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

    /**
     * [region] of [monitor] put together from the windows in it at their own size (see
     * [sharpWindows]), or null when none is drawn smaller there (the screen is as sharp).
     */
    internal fun sharpRegion(monitor: Monitor, chosen: Rect): Pixels? {
        val desktop = caulis.desktop
        val shown = desktop.allWindows.filter(desktop::drawnNow).mapNotNull { w -> desktop.shownRect(w)?.takeIf { it.intersect(chosen) != null && desktop.monitorOf(w) === monitor }?.let { w to it } }
        if (shown.isEmpty()) return null
        // See-through behind them: only as big as the windows in the part (no empty margins).
        val region = if (sharpBackground != null) chosen else shown.mapNotNull { it.second.intersect(chosen) }.reduce(Rect::union)
        // How much bigger to draw it all: as much as the most shrunk window needs (at most 4x, 16384 pixels).
        val factor = shown.maxOf { (w, r) -> desktop.windowPixelSize(w).first.toDouble() / r.width.coerceAtLeast(1) }
            .coerceAtMost(4.0).coerceAtMost(16384.0 / maxOf(region.width, region.height))
        if (factor <= monitor.scale * 1.05) return null
        val width = Math.round(region.width * factor).toInt()
        val height = Math.round(region.height * factor).toInt()
        val canvas = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE)
        val g = canvas.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        sharpBackground?.let { c ->
            g.color = java.awt.Color(c.red, c.green, c.blue, c.alpha)
            g.fillRect(0, 0, width, height)
        }
        for ((window, rect) in shown) {
            val (pw, ph) = desktop.windowPixelSize(window)
            val target = desktop.renderer.createTarget(pw, ph)
            val pixels = try {
                desktop.captureWindow(window, target)
                Pixels.read(target, null, transparent = true)
            } finally {
                target.close()
            }
            val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE)
            image.raster.setDataElements(0, 0, pixels.width, pixels.height, pixels.argb)
            val x = (rect.x - region.x) * factor
            val y = (rect.y - region.y) * factor
            val w = rect.width * factor
            val h = rect.height * factor
            val old = g.clip
            if (sharpCornerRadius > 0f) g.clip(java.awt.geom.RoundRectangle2D.Double(x, y, w, h, 2.0 * sharpCornerRadius * factor, 2.0 * sharpCornerRadius * factor))
            g.drawImage(image, Math.round(x).toInt(), Math.round(y).toInt(), Math.round(w).toInt(), Math.round(h).toInt(), null)
            g.clip = old
        }
        g.dispose()
        val argb = IntArray(width * height)
        canvas.raster.getDataElements(0, 0, width, height, argb)
        log.info { "Put together ${shown.size} windows at ${String.format("%.2f", factor)}x: ${width}x$height" }
        return Pixels(width, height, argb, transparent = sharpBackground == null)
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
