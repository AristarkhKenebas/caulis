package dev.caulis.script

import dev.caulis.wm.Caulis
import dev.caulis.wm.config.ConfigFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** config.kts: Kotlin without a project, compiled by the compositor. */
class KotlinScriptConfigTest {

    private val folder: Path = Files.createTempDirectory("caulis-kts")
    private val cache: Path = Files.createTempDirectory("caulis-kts-cache")

    init {
        System.setProperty("caulis.scriptCache", cache.toString())
    }

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
        cache.toFile().deleteRecursively()
    }

    private fun apply(code: String): Pair<Caulis, List<String>> {
        Files.writeString(folder.resolve("config.kts"), code.trimIndent())
        val caulis = Caulis().apply { configDir = folder }
        return caulis to ConfigFile.applyTo(caulis)
    }

    @Test
    fun `all of Caulis, as in Main_kt, and quick the second time`() {
        val code = """
            layout = MasterStack(Gaps(inner = 4), ratio = 0.6)
            scale = 1.25
            keys.quit = null
            bind("Super+B") { launch("firefox") }
            rule { appId("mpv"); floating = true }
            effects = Effects.MODERN.copy(cornerRadius = 14)
            onWindowOpened { window -> if (window.appId == "music") run("move-to-workspace 2", window) }
        """
        val first = System.nanoTime()
        val (caulis, problems) = apply(code)
        val firstMillis = (System.nanoTime() - first) / 1_000_000
        assertEquals(emptyList(), problems)
        assertEquals("Master", caulis.layout.name)
        assertEquals(1.25, caulis.scale)
        assertEquals(null, caulis.keys.all()["quit"])
        assertEquals(14, caulis.effects.cornerRadius)

        val second = System.nanoTime()
        val (again, _) = apply(code)
        val secondMillis = (System.nanoTime() - second) / 1_000_000
        assertEquals(1.25, again.scale)
        assertTrue(secondMillis < firstMillis, "from the cache: $secondMillis ms, compiled: $firstMillis ms")
    }

    @Test
    fun `mistakes say their line`() {
        val compile = apply(
            """
            scale = 1.25
            lyaout = Dwindle()
            """,
        ).second
        assertTrue(compile.single().startsWith("config.kts:2: Unresolved reference"), compile.toString())

        val running = apply(
            """
            scale = 1.25
            bind("Super+Nonsense") { }
            """,
        ).second
        assertTrue(running.single().startsWith("config.kts:2: ") && "Nonsense" in running.single(), running.toString())
    }

    @Test
    fun `the guide's example is right`() {
        val guide = Files.readString(Path.of("../docs/getting-started.md"))
        val example = guide.substringAfter(". Kotlin without a project: config.kts\n").substringAfter("```kotlin\n").substringAfter("```kotlin\n").substringBefore("```")
        assertTrue("layout = " in example, example)
        assertEquals(emptyList(), apply(example).second)
    }

    @Test
    fun `the input guide's Kotlin is right`() {
        val section = Files.readString(Path.of("../docs/getting-started.md")).substringAfter(". Mice, touchpads, tablets, and keys that do more\n").substringBefore("\n## ")
        val blocks = section.split("```kotlin\n").drop(1).map { it.substringBefore("```") }
        assertEquals(5, blocks.size)
        assertEquals(emptyList(), apply(blocks.joinToString("\n") + "\nonLid { closed -> if (closed) lock() }").second)
    }

    @Test
    fun `the light and dark guide's Kotlin is right`() {
        val section = Files.readString(Path.of("../docs/getting-started.md")).substringAfter(". Light and dark\n").substringBefore("\n## ")
        val blocks = section.split("```kotlin\n").drop(1).map { it.substringBefore("```") }
        assertEquals(4, blocks.size)
        // (Two schedules in a row: the second wins, both must compile.)
        val code = blocks.joinToString("\n") // (config.kts knows ColorScheme, ColorSchedule and LocalTime)
        assertEquals(emptyList(), apply(code).second)
    }

    @Test
    fun `the window director's Kotlin is right`() {
        val section = Files.readString(Path.of("../docs/getting-started.md")).substringAfter("**Placing windows yourself, every frame**").substringBefore("\n## ")
        val block = section.substringAfter("```kotlin\n").substringBefore("```")
        assertTrue("WindowDirector" in block)
        assertEquals(emptyList(), apply(block).second)
    }

    @Test
    fun `the drawings' Kotlin is right`() {
        val section = Files.readString(Path.of("../docs/getting-started.md")).substringAfter("**Pictures of your own that take no input**").substringBefore("\n## ")
        val block = section.substringAfter("```kotlin\n").substringBefore("```")
        assertTrue("addDrawing" in block)
        assertEquals(emptyList(), apply("import dev.caulis.geometry.Rect\n" + block).second)
    }

    @Test
    fun `the display modes' Kotlin is right`() {
        val section = Files.readString(Path.of("../docs/getting-started.md")).substringAfter("**A laptop with a projector").substringBefore("\n## ")
        val block = section.substringAfter("```kotlin\n").substringBefore("```")
        assertTrue("useDisplays" in block)
        assertEquals(emptyList(), apply(block).second)
    }

    @Test
    fun `the everyday guide's Kotlin is right`() {
        val section = Files.readString(Path.of("../docs/getting-started.md")).substringAfter(". Everyday things: idle, screens, clipboard managers, games\n").substringBefore("\n## ")
        val block = section.substringAfter("```kotlin\n").substringBefore("```")
        assertTrue("onIdle" in block)
        assertEquals(emptyList(), apply(block).second)
    }
}
