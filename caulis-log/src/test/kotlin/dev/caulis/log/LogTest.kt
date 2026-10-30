package dev.caulis.log

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Levels for everything and for single areas, and the black box. */
class LogTest {

    private val lines = ArrayList<String>()

    init {
        Log.toConsole = false
        Log.listener = { synchronized(lines) { lines += it } }
    }

    @AfterTest
    fun reset() {
        Log.level = Log.PROBLEMS
        Log.AREAS.keys.forEach(Log::clearLevel)
        Log.listener = null
        Log.toConsole = true
    }

    @Test
    fun `every area Caulis logs in is a known one (log(area, level) takes it)`() {
        // (The modules' sources, next to this one's folder: each Logger("name") they make.)
        val root = java.nio.file.Path.of("..").toAbsolutePath().normalize()
        val made = java.nio.file.Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") && "/src/main/" in it.toString() && "/build/" !in it.toString() }
                .toList().flatMap { file -> Regex("""Logger\("([a-z-]+)"\)""").findAll(java.nio.file.Files.readString(file)).map { it.groupValues[1] }.toList() }
                .toSortedSet()
        }
        assertTrue(made.isNotEmpty(), "sources found under $root")
        val unknown = made - Log.AREAS.keys
        assertTrue(unknown.isEmpty(), "areas logged in but not in Log.AREAS: $unknown")
    }

    @Test
    fun `each level shows its own lines and the ones below`() {
        val log = Logger("windows")
        var made = 0
        for (level in 0..3) {
            Log.level = level
            lines.clear()
            log.fatal("f")
            log.error("e")
            log.info("i")
            log.trace { made++; "t" }
            assertEquals(listOf("f", "e", "i", "t").take(level + 1), lines.map { it.substringAfterLast(' ') }, "level $level")
        }
        assertEquals(1, made, "a level 3 text is only made when level 3 is on")
    }

    @Test
    fun `single areas can be more detailed than the rest`() {
        Log.configure("1,focus=3")
        Logger("focus").trace("focus step")
        Logger("windows").trace("window step")
        Logger("windows").warn("window problem")
        assertEquals(2, lines.size, "$lines")
        assertTrue(lines[0].endsWith("T focus    focus step"), lines[0])
        assertTrue(lines[1].endsWith("W windows  window problem"), lines[1])
        assertEquals(3, Log.levelOf("focus"))
        assertEquals(1, Log.levelOf("render"))
    }

    @Test
    fun `wrong settings are reported, not fatal`() {
        Log.configure("7,focus=x,nonsense=2")
        assertEquals(Log.PROBLEMS, Log.level, "unchanged")
        assertTrue(lines.size >= 2, "both mistakes reported: $lines")
    }

    @Test
    fun `the black box keeps recent events even when they are not shown`() {
        Log.level = Log.PROBLEMS
        Logger("windows").info("a window opened, not shown at level 1")
        assertTrue(lines.none { "not shown" in it })
        assertTrue(Log.recent().last().endsWith("a window opened, not shown at level 1"))
    }

    @Test
    fun `a problem said every frame is said once`() {
        val seen = ArrayList<String>()
        Log.watch { seen += it.message }.use {
            val log = Logger("test")
            repeat(500) { n -> log.error("Your thing failed on Window(\"title $n\", app)", IllegalArgumentException("\"auto\" is not a colour")) }
        }
        // A few let through, then held back (a count follows after a while).
        assertTrue(seen.count { it.startsWith("Your thing failed") } <= 5, "${seen.size}")
    }
}
