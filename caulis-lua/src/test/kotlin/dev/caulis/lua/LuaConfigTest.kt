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
    fun `code that never ends is stopped however it tries to escape, and Java errors are mistakes too`() {
        val usual = LuaConfig.LOAD_LIMIT
        LuaConfig.LOAD_LIMIT = 300_000_000L
        try {
            for ((code, expected) in listOf(
                "debug.sethook() while true do end" to "attempt to call nil", // there is no sethook to take the limit away
                "coroutine.wrap(function() while true do end end)()" to "runs too long",
                "while true do pcall(function() while true do end end) end" to "runs too long",
                "local function f() return 1 + f() end f()" to "", // a stack overflow (LuaJ reports it its own way)
                "local s = string.rep('x', 2^31)" to "",
                // An array beyond the VM's limit: an OutOfMemoryError, a problem and not the end of the desktop.
                "local s = string.rep('x', 2^31 - 1)" to "too much memory",
                // Patterns that backtrack without end: LuaJ matches in Java, where the hook never comes.
                "string.find(string.rep('a', 40), '(.-)(.-)(.-)(.-)(.-)(.-)(.-)(.-)b')" to "runs too long",
                "local s = ('a'):rep(40) s:match('(.-)(.-)(.-)(.-)(.-)(.-)(.-)(.-)b')" to "runs too long",
                "for w in string.gmatch(('a'):rep(40), '(.*)(.*)(.*)(.*)(.*)(.*)(.*)b') do end" to "runs too long",
                "while true do pcall(string.gsub, ('a'):rep(40), '(.-)(.-)(.-)(.-)(.-)(.-)(.-)(.-)b', '') end" to "runs too long",
                "coroutine.wrap(function() string.find(('a'):rep(40), '(.-)(.-)(.-)(.-)(.-)(.-)(.-)(.-)b') end)()" to "runs too long",
                "luajava.bindClass('java.lang.System'):exit(1)" to "index",
                // Java classes through require: not found (they would bring the hooks and Java back).
                "require('org.luaj.vm2.lib.jse.LuajavaLib')" to "not found",
                "require('org.luaj.vm2.lib.DebugLib') debug.sethook() while true do end" to "not found",
                "local d = require('debug') d.sethook() while true do end" to "attempt to call nil",
                "package.loadlib('x', 'y')" to "attempt to call nil",
                "os.exit(1)" to "os.exit",
                "io.popen('sleep 5')" to "io.popen",
            )) {
                val start = System.nanoTime()
                val (_, problems) = apply(code)
                val seconds = (System.nanoTime() - start) / 1e9
                assertEquals(1, problems.size, "$code: $problems")
                assertTrue(expected in problems.single(), "$code: ${problems.single()}")
                assertTrue(seconds < 3, "$code took $seconds s")
            }
        } finally {
            LuaConfig.LOAD_LIMIT = usual
        }
    }

    @Test
    fun `string patterns still match as Lua's do`() {
        val (_, problems) = apply(
            """
            assert(string.find("hello world", "o w") == 5)
            assert(select(3, ("key = value"):find("(%w+)%s*=%s*(%w+)")) == "key")
            assert(string.match("2024-10-27", "(%d+)-(%d+)") == "2024")
            assert(string.gsub("hello world", "(%w+)", "%1 %1") == "hello hello world world")
            assert(select(2, string.gsub("abc", "", "-")) == 4)
            assert(string.gsub("${'$'}name-x", "%${'$'}(%w+)", { name = "lua" }) == "lua-x")
            assert(string.match("f(a(b)c)", "%b()") == "(a(b)c)")
            assert(string.find("THE (quick) fox", "%f[%a]%a+", 5) == 6)
            local words = {}
            for w in ("one two three"):gmatch("%a+") do words[#words + 1] = w end
            assert(#words == 3 and words[3] == "three")
            assert(not pcall(string.find, "x", "[a"))
            """,
        )
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `os execute does not wait, and at the top of the file only starts once the compositor runs`() {
        val marker = folder.resolve("started")
        val start = System.nanoTime()
        val (_, problems) = apply(
            """
            local ok, how, status = os.execute("sleep 2; touch '$marker'")
            assert(ok == true and how == "exit" and status == 0)
            assert(os.execute() == true)
            caulis.launch("touch '$marker'")
            assert(io.read() == nil) -- the standard input is empty: nothing waits
            """,
        )
        val seconds = (System.nanoTime() - start) / 1e9
        assertEquals(emptyList(), problems)
        assertTrue(seconds < 1.5, "config.lua took $seconds s")
        Thread.sleep(300)
        assertTrue(!Files.exists(marker), "nothing was started while the file was only read")
    }

    @Test
    fun `a bad regular expression is a problem with its line, the settings after it still apply`() {
        val (caulis, problems) = apply(
            """
            caulis.rule { appIdPattern = "(" }
            caulis.settings { scale = 1.5 }
            """,
        )
        assertEquals(1, problems.size, "$problems")
        assertTrue("regular expression" in problems.single(), problems.single())
        assertEquals(1.5, caulis.scale)
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
        val example = guide.substringAfter(". Lua: config.lua\n").substringAfter("```lua\n").substringBefore("```")
        assertTrue("caulis.settings" in example)
        assertEquals(emptyList(), apply(example).second)
    }

    @Test
    fun `the input guide's Lua is right`() {
        val section = Files.readString(Path.of("../docs/getting-started.md")).substringAfter(". Mice, touchpads, tablets, and keys that do more\n").substringBefore("\n## ")
        val example = section.substringAfter("```lua\n").substringBefore("```")
        assertTrue("mode = \"resize\"" in example)
        assertEquals(emptyList(), apply(example).second)
    }
}
