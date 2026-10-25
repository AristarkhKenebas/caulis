package dev.caulis.lua

import dev.caulis.wm.Caulis
import dev.caulis.wm.config.ConfigFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** config.lua as a user writes it: applied, with mistakes found by line. */
class LuaConfigTest {

    private val folder: Path = Files.createTempDirectory("caulis-lua")

    @AfterTest
    fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    // Applies [code] as config.lua to a new configuration: it, and the problems.
    private fun apply(code: String, more: Map<String, String> = emptyMap()): Pair<Caulis, List<String>> {
        Files.writeString(folder.resolve("config.lua"), code.trimIndent())
        more.forEach { (name, text) -> Files.writeString(folder.resolve(name), text) }
        val caulis = Caulis().apply { configDir = folder }
        return caulis to ConfigFile.applyTo(caulis)
    }

    @Test
    fun `settings, keys and rules`() {
        val (caulis, problems) = apply(
            """
            caulis.settings {
              layout = { name = "master-stack", gaps = { inner = 4, outer = 12 } },
              scale = 1.5,
              keys = { quit = false, close = "Super+W" },
              startup = { "waybar" },
            }
            caulis.bind("Super+B", "exec firefox")
            caulis.bind("Super+N", function() caulis.notify("hi") end)
            caulis.rule { appId = "mpv", floating = true, margin = { 1, 2, 3, 4 } }
            caulis.on("window-opened", function(w) end)
            """,
        )
        assertEquals(emptyList(), problems)
        assertEquals("Master", caulis.layout.name)
        assertEquals(1.5, caulis.scale)
        assertNull(caulis.keys.all()["quit"])
        assertEquals("Super+W", caulis.keys.all()["close"])
    }

    @Test
    fun `rules that ask a window's state, with tags and unless`() {
        val (caulis, problems) = apply(
            """
            caulis.rule { anyOf = { { appId = "mpv" }, { appId = "vlc" } }, tags = { "video" }, keepAwake = true }
            caulis.rule { hasTag = "video", unless = { isXwayland = true }, cornerRadius = 0, ignore = { "maximize" } }
            caulis.rule { isFloating = true, shadow = true, minSize = { 300, 200 }, pinned = false }
            caulis.bind("Super+P", "pin")
            caulis.bind("Super+T", "tag +starred")
            """,
        )
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `mistakes say their line, the rest still applies`() {
        val (caulis, problems) = apply(
            """
            caulis.settings { scale = 1.25 }
            caulis.settings {
              terminl = "foot",
            }
            caulis.bind("Super+B", "exce firefox")
            """,
        )
        assertEquals(1.25, caulis.scale, "the good one stays")
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems[0].startsWith("config.lua:2: terminl: there is no such setting (did you mean \"terminal\"?)"), problems[0])
        assertTrue(problems[1].startsWith("config.lua:5:") && "did you mean \"exec\"" in problems[1], problems[1])
    }

    @Test
    fun `Lua's own mistakes, and code that never ends`() {
        val syntax = apply("caulis.settings { scale = }").second.single()
        assertTrue(syntax.startsWith("config.lua:1:"), syntax)

        val event = apply("caulis.on(\"window-opend\", function() end)").second.single()
        assertTrue("there is no event \"window-opend\"" in event, event)

        val started = System.nanoTime()
        val endless = apply("while true do end").second.single()
        assertTrue("runs too long" in endless, endless)
        assertTrue(System.nanoTime() - started < 10_000_000_000, "stopped")

        val notYet = apply("caulis.run(\"workspace 2\")").second.single()
        assertTrue("while the compositor runs" in notYet, notYet)
    }

    @Test
    fun `modules of your own next to it`() {
        val (caulis, problems) = apply(
            """
            local looks = require("looks")
            caulis.settings { scale = looks.scale }
            """,
            mapOf("looks.lua" to "return { scale = 2 }"),
        )
        assertEquals(emptyList(), problems)
        assertEquals(2.0, caulis.scale)
    }

    @Test
    fun `applied again, it replaces what it added`() {
        val (caulis, _) = apply("caulis.rule { appId = \"a\" }\ncaulis.rule { appId = \"b\" }")
        val field = Caulis::class.java.getDeclaredField("rules").apply { isAccessible = true }
        val rules = { field.get(caulis) as List<*> }
        assertEquals(2, rules().size)
        Files.writeString(folder.resolve("config.lua"), "caulis.rule { appId = \"c\" }")
        ConfigFile.applyTo(caulis)
        assertEquals(1, rules().size)
    }

    @Test
    fun `the guide's example is right`() {
        val guide = Files.readString(Path.of("../docs/getting-started.md"))
        val example = guide.substringAfter("## 26.").substringAfter("```lua\n").substringBefore("```")
        assertTrue("caulis.settings" in example)
        assertEquals(emptyList(), apply(example).second)
    }

    @Test
    fun `the input guide's Lua is right`() {
        val section = Files.readString(Path.of("../docs/getting-started.md")).substringAfter("## 28.").substringBefore("\n## ")
        val example = section.substringAfter("```lua\n").substringBefore("```")
        assertTrue("mode = \"resize\"" in example)
        assertEquals(emptyList(), apply(example).second)
    }
}
