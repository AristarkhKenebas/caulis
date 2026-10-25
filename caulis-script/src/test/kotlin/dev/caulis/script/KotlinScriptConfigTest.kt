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
        val example = guide.substringAfter("## 27.").substringAfter("```kotlin\n").substringAfter("```kotlin\n").substringBefore("```")
        assertTrue("layout = " in example, example)
        assertEquals(emptyList(), apply(example).second)
    }
}
