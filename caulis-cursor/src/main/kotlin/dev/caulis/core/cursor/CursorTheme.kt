package dev.caulis.core.cursor

import dev.caulis.log.InternalCaulisApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

/**
 * Cursor themes that come with a library instead of being installed. A library offers its themes by listing its provider in
 * `META-INF/services/dev.caulis.core.cursor.CursorThemeProvider`; adding the library to a
 * project is then enough.
 */
interface CursorThemeProvider {
    /** The themes it has, the preferred one first. */
    val themes: List<String>

    /** A file of a theme, e.g. ("Caulis", "cursors/default"); null if it has none. */
    fun file(theme: String, path: String): ByteArray?

    /** The real files of a theme ("index.theme", "cursors/left_ptr", ...), to put them on disk for other programs. */
    fun files(theme: String): List<String>

    /** Cursor names that are another cursor of the theme ("default" to "left_ptr"); [file] resolves them itself. */
    fun aliases(theme: String): Map<String, String> = emptyMap()
}

/** A cursor at one size: one picture, or the frames of an animation (e.g. the busy cursor). */
class Cursor(val shape: String, val frames: List<CursorImage>) {
    val animated: Boolean get() = frames.size > 1 && frames.all { it.delayMillis > 0 }

    private val cycle = frames.sumOf { it.delayMillis.toLong() }

    /** The frame to show [millis] after the animation started, and how long until the next one. */
    fun frameAt(millis: Long): Pair<CursorImage, Long> {
        if (!animated) return frames[0] to Long.MAX_VALUE
        var t = Math.floorMod(millis, cycle)
        for (frame in frames) {
            if (t < frame.delayMillis) return frame to (frame.delayMillis - t)
            t -= frame.delayMillis
        }
        return frames.last() to 1L
    }
}

/**
 * A cursor theme (Adwaita, Bibata, Breeze, ...) at a [size] in pixels. Cursors are asked for
 * by the names programs use (`default`, `pointer`, `text`, `nwse-resize`, ... the CSS names);
 * older themes that only know the X11 names (`left_ptr`, `hand2`, `xterm`, ...) work too.
 * A cursor the theme does not have comes from the themes it inherits, then the system's.
 */
class CursorTheme private constructor(
    /** The theme's name, e.g. "Adwaita". */
    val name: String,
    /** The size wanted, in pixels (24 is usual; 32, 48 for big screens or eyes). */
    val size: Int,
    private val chain: List<String>,
    private val sources: List<Source>,
) {
    private val cache = HashMap<Pair<String, Int>, Cursor?>()

    /** True if the theme was found (otherwise only the fallback themes are used). */
    val found: Boolean get() = sources.any { it.has(name) }

    /** The cursor called [shape], or null if no theme has anything like it. */
    fun cursor(shape: String): Cursor? = cursor(shape, size)

    /**
     * The cursor called [shape] in the theme's size nearest to [pixels] (for a scaled screen:
     * the size times the scale, so it is sharp), or null if no theme has anything like it.
     */
    fun cursor(shape: String, pixels: Int): Cursor? = cache.getOrPut(shape to pixels) {
        // The chosen theme under any of the cursor's names first; only then the next theme.
        val names = listOf(shape) + (ALIASES[shape] ?: emptyList())
        for (theme in chain) {
            for (candidate in names) {
                val bytes = sources.firstNotNullOfOrNull { it.file(theme, "cursors/$candidate") } ?: continue
                val frames = XCursor.read(bytes, pixels) ?: continue
                return@getOrPut Cursor(shape, frames)
            }
        }
        null
    }

    /**
     * Writes the theme into [iconsFolder] (as `<name>/cursors/...`) if it came with a library,
     * so programs that draw their own cursor find it too (point `XCURSOR_PATH` there).
     * Written once; returns false if the theme is installed anyway (nothing to write).
     */
    fun exportTo(iconsFolder: Path): Boolean {
        val provider = (providers() + BUILT_IN).firstOrNull { name in it.themes } ?: return false
        val folder = iconsFolder.resolve(name)
        val done = folder.resolve(".complete")
        if (Files.exists(done)) return true
        for (path in provider.files(name)) {
            if (path.contains("..") || path.startsWith("/")) continue
            val bytes = provider.file(name, path) ?: continue
            val target = folder.resolve(path)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
        // Other names for the same cursor: links, not copies.
        for ((alias, original) in provider.aliases(name)) {
            if (alias.contains('/') || original.contains('/')) continue
            val link = folder.resolve("cursors").resolve(alias)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, Path.of(original))
        }
        Files.createFile(done)
        return true
    }

    override fun toString() = "CursorTheme($name, $size px)"

    // Where theme files come from: a library, or folders on disk.
    internal interface Source {
        fun file(theme: String, path: String): ByteArray?
        fun has(theme: String): Boolean = file(theme, "index.theme") != null || file(theme, "cursors/default") != null || file(theme, "cursors/left_ptr") != null
    }

    private class Provided(val provider: CursorThemeProvider) : Source {
        override fun file(theme: String, path: String) = if (theme in provider.themes) provider.file(theme, path) else null
    }

    private class Folders(val roots: List<Path>) : Source {
        override fun file(theme: String, path: String): ByteArray? {
            if (theme.contains('/') || theme.startsWith(".")) return null
            for (root in roots) {
                val file = root.resolve(theme).resolve(path)
                try {
                    if (Files.isRegularFile(file) && Files.size(file) <= MAX_FILE) return Files.readAllBytes(file)
                } catch (_: Exception) {
                    // unreadable: try the next folder
                }
            }
            return null
        }
    }

    companion object {
        /**
         * Loads the theme [name] at [size] pixels. Without a name: a theme that came with a
         * library, else the one in `XCURSOR_THEME`,
         * else Caulis's own ([DEFAULT], sharp white arrows). Without a size: `XCURSOR_SIZE`,
         * else 24. Cursors a theme lacks come from the system's theme, then from Caulis's own,
         * so there always is one.
         */
        @JvmStatic
        @JvmOverloads
        fun load(name: String? = null, size: Int? = null): CursorTheme = load(name, size, searchPath())

        // The same with the folders given (for tests, also caulis-core's).
        @InternalCaulisApi
        fun load(name: String?, size: Int?, folders: List<Path>): CursorTheme {
            val providers = providers()
            val sources = (providers + BUILT_IN).map(::Provided) + Folders(folders)
            val chosen = name ?: providers.firstNotNullOfOrNull { it.themes.firstOrNull() }
                ?: System.getenv("XCURSOR_THEME")?.takeIf { it.isNotBlank() } ?: DEFAULT
            val pixels = size ?: System.getenv("XCURSOR_SIZE")?.toIntOrNull()?.takeIf { it in 8..512 } ?: 24
            require(pixels in 8..512) { "a cursor size must be between 8 and 512 pixels, not $pixels" }
            return CursorTheme(chosen, pixels, inheritanceChain(chosen, sources), sources)
        }

        /** The names of all themes that can be loaded (installed or from libraries). */
        @JvmStatic
        fun available(): List<String> {
            val provided = (providers() + BUILT_IN).flatMap { it.themes }
            val installed = searchPath().flatMap { root ->
                try {
                    Files.list(root).use { list -> list.filter { Files.isDirectory(it.resolve("cursors")) }.map { it.fileName.toString() }.toList() }
                } catch (_: Exception) {
                    emptyList() // the folder does not exist
                }
            }
            return (provided + installed).distinct().sorted()
        }

        private fun providers(): List<CursorThemeProvider> =
            ServiceLoader.load(CursorThemeProvider::class.java).toList()

        /** Caulis's own theme: sharp white arrows with a black outline. */
        const val DEFAULT = "Caulis"

        /** Caulis's own theme in black, with a white outline. */
        const val BLACK = "Caulis-Black"

        // Caulis's own themes, always there (drawn by tools/cursors; MPL-2.0 as all of Caulis).
        private val BUILT_IN: CursorThemeProvider = ZipCursorThemes(CursorTheme::class.java, listOf(DEFAULT, BLACK))

        /** The folders cursor themes are looked for in (as libXcursor does). */
        @JvmStatic
        fun searchPath(): List<Path> {
            val home = System.getProperty("user.home")
            System.getenv("XCURSOR_PATH")?.takeIf { it.isNotBlank() }?.let { path ->
                return path.split(':').filter { it.isNotBlank() }.map { Path.of(it.replaceFirst(Regex("^~"), home)) }
            }
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
            val dataDirs = (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share").split(':')
            return (listOf("$dataHome/icons", "$home/.icons") + dataDirs.map { "$it/icons" } + "/usr/share/pixmaps")
                .distinct().map { Path.of(it) }
        }

        // The theme, the themes it inherits from (index.theme: Inherits=), then the fallbacks.
        private fun inheritanceChain(name: String, sources: List<Source>): List<String> {
            val chain = ArrayList<String>()
            val queue = ArrayDeque(listOf(name))
            while (queue.isNotEmpty() && chain.size < 16) {
                val theme = queue.removeFirst()
                if (theme in chain) continue
                chain += theme
                val index = sources.firstNotNullOfOrNull { it.file(theme, "index.theme") }?.decodeToString() ?: continue
                Regex("""(?m)^\s*Inherits\s*=\s*(.*)$""").find(index)?.groupValues?.get(1)
                    ?.split(',', ';')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }?.forEach(queue::addLast)
            }
            for (fallback in listOf("default", "Adwaita", DEFAULT)) if (fallback !in chain) chain += fallback
            return chain
        }

        private const val MAX_FILE = 16L * 1024 * 1024

        // The CSS names programs ask for, and what older themes call them.
        private val ALIASES: Map<String, List<String>> = mapOf(
            "default" to listOf("left_ptr", "arrow", "top_left_arrow"),
            "context-menu" to listOf("left_ptr"),
            "help" to listOf("question_arrow", "whats_this", "left_ptr"),
            "pointer" to listOf("hand2", "hand1", "hand", "pointing_hand"),
            "progress" to listOf("left_ptr_watch", "half-busy", "watch"),
            "wait" to listOf("watch", "left_ptr_watch"),
            "cell" to listOf("plus", "crosshair"),
            "crosshair" to listOf("cross", "tcross"),
            "text" to listOf("xterm", "ibeam"),
            "vertical-text" to listOf("xterm"),
            "alias" to listOf("dnd-link", "link"),
            "copy" to listOf("dnd-copy"),
            "move" to listOf("fleur", "dnd-move", "size_all"),
            "no-drop" to listOf("dnd-none", "not-allowed", "crossed_circle"),
            "not-allowed" to listOf("crossed_circle", "forbidden", "circle"),
            "grab" to listOf("openhand", "hand1", "fleur"),
            "grabbing" to listOf("closedhand", "fleur", "dnd-move"),
            "e-resize" to listOf("right_side", "ew-resize", "size_hor"),
            "w-resize" to listOf("left_side", "ew-resize", "size_hor"),
            "n-resize" to listOf("top_side", "ns-resize", "size_ver"),
            "s-resize" to listOf("bottom_side", "ns-resize", "size_ver"),
            "ne-resize" to listOf("top_right_corner", "nesw-resize", "size_bdiag"),
            "sw-resize" to listOf("bottom_left_corner", "nesw-resize", "size_bdiag"),
            "nw-resize" to listOf("top_left_corner", "nwse-resize", "size_fdiag"),
            "se-resize" to listOf("bottom_right_corner", "nwse-resize", "size_fdiag"),
            "ew-resize" to listOf("sb_h_double_arrow", "h_double_arrow", "size_hor"),
            "ns-resize" to listOf("sb_v_double_arrow", "v_double_arrow", "size_ver"),
            "nesw-resize" to listOf("fd_double_arrow", "size_bdiag"),
            "nwse-resize" to listOf("bd_double_arrow", "size_fdiag"),
            "col-resize" to listOf("sb_h_double_arrow", "split_h", "ew-resize"),
            "row-resize" to listOf("sb_v_double_arrow", "split_v", "ns-resize"),
            "all-scroll" to listOf("fleur", "move"),
            "zoom-in" to listOf("zoom_in"),
            "zoom-out" to listOf("zoom_out"),
            "dnd-ask" to listOf("dnd-copy", "copy"),
            "all-resize" to listOf("fleur", "move", "size_all"),
        )

        /** The cursor names programs can ask for (cursor-shape-v1), in the protocol's order (1 = "default"). */
        @JvmField
        val SHAPES: List<String> = listOf(
            "default", "context-menu", "help", "pointer", "progress", "wait", "cell", "crosshair",
            "text", "vertical-text", "alias", "copy", "move", "no-drop", "not-allowed", "grab",
            "grabbing", "e-resize", "n-resize", "ne-resize", "nw-resize", "s-resize", "se-resize",
            "sw-resize", "w-resize", "ew-resize", "ns-resize", "nesw-resize", "nwse-resize",
            "col-resize", "row-resize", "all-scroll", "zoom-in", "zoom-out", "dnd-ask", "all-resize",
        )
    }
}
