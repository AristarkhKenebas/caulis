package dev.caulis.reload

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compiling a configuration project inside this JVM, the way hot reload does. */
class InProcessCompilerTest {

    private val root: Path = Files.createTempDirectory("caulis-reload-test")
    private val compiler = InProcessCompiler()
    private val classpath = System.getProperty("java.class.path").split(File.pathSeparatorChar).filter { it.isNotEmpty() }.map { Path.of(it) }

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun file(path: String, text: String) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        file.writeText(text.trimIndent())
    }

    private fun project() {
        file("build.gradle.kts", """plugins { kotlin("jvm") version "2.4.21"; application }""")
        file(
            "src/main/kotlin/Main.kt",
            """
            import dev.caulis.wm.caulis
            fun main() = caulis { title = Greeting.text() + " " + Names.name() }
            """,
        )
        file("src/main/kotlin/Names.kt", """object Names { fun name() = "compositor" }""")
        file("src/main/java/Greeting.java", """public class Greeting { public static String text() { return "My"; } }""")
    }

    @Test
    fun `Kotlin and Java of a project compile, quickly once warm`() {
        project()
        var millis = 0L
        repeat(3) { round ->
            val output = Files.createTempDirectory(root, "out")
            val started = System.nanoTime()
            val result = compiler.compile(root, classpath, output)
            millis = (System.nanoTime() - started) / 1_000_000
            println("compile ${round + 1}: $millis ms")
            assertTrue(result.compiled, result.problems.joinToString("\n"))
            assertTrue(Files.exists(output.resolve("MainKt.class")))
            assertTrue(Files.exists(output.resolve("Greeting.class")))
            URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
                assertEquals("compositor", loader.loadClass("Names").getField("INSTANCE").get(null).let { it.javaClass.getMethod("name").invoke(it) })
            }
        }
        assertTrue(millis < 5000, "warm compiles take well under a Gradle build: $millis ms")
    }

    @Test
    fun `mistakes are reported with file and line`() {
        project()
        file("src/main/kotlin/Main.kt", "import dev.caulis.wm.caulis\nfun main() = caulis {\n    lyaout = 3\n}")
        val result = compiler.compile(root, classpath, Files.createTempDirectory(root, "out"))
        assertFalse(result.compiled)
        assertTrue(result.problems.any { it.startsWith("src/main/kotlin/Main.kt:3: ") && "lyaout" in it }, result.problems.joinToString("\n"))
    }

    @Test
    fun `projects that need their Gradle build are left to it`() {
        project()
        assertNull(compiler.cannotCompile(root))
        file("build.gradle.kts", """plugins { kotlin("jvm") version "2.4.21"; kotlin("plugin.serialization") version "2.4.21" }""")
        assertNotNull(compiler.cannotCompile(root)).let { assertTrue("compiler plugin" in it, it) }
    }
}
