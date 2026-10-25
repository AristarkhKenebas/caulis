package dev.caulis.wm.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The files made from [Settings] that live in the repository — the Nix module's options and
 * the JSON Schema for editors — say what the code says. After changing a setting:
 *
 * ```
 * CAULIS_UPDATE_GENERATED=1 ./gradlew :caulis-wm:test --tests '*GeneratedFilesTest' --rerun
 * ```
 */
class GeneratedFilesTest {

    private val root: Path = Path.of("").toAbsolutePath().parent

    private fun same(relative: String, text: String) {
        val file = root.resolve(relative)
        if (System.getenv("CAULIS_UPDATE_GENERATED") == "1") {
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        val committed = if (Files.exists(file)) Files.readString(file) else ""
        assertEquals(text, committed, "$relative is out of date (see GeneratedFilesTest)")
    }

    @Test
    fun `the Nix options are up to date`() = same("nix/options.nix", CommandLine.nixOptions())

    @Test
    fun `the JSON Schema is up to date`() = same("docs/config.schema.json", CommandLine.jsonSchema() + "\n")
}
