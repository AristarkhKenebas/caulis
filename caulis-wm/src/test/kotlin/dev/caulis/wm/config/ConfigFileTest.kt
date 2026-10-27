package dev.caulis.wm.config

import dev.caulis.render.Color
import dev.caulis.wm.Caulis
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** config.json: read, checked (with lines and guesses), and applied, as a user writes it. */
class ConfigFileTest {

    private fun problems(text: String) = ConfigFile.read(text).problems

    @Test
    fun `comments and trailing commas are fine, a broken file says where`() {
        val read = ConfigFile.read(
            """
            {
              // the layout
              "layout": { "name": "dwindle", },   /* trailing commas are forgiven */
              "scale": 1.25,
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        assertEquals(1.25, read.values["scale"])

        val broken = problems("{\n  \"scale\": 1.25\n  \"terminal\": \"foot\"\n}")
        assertEquals(1, broken.size)
        assertTrue(broken[0].startsWith("line 3"), broken[0])

        assertEquals(1, problems("{ \"scale\": 1, \"scale\": 2 }").size, "said twice")
        assertTrue(problems("[1, 2]").single().contains("object"))
    }

    @Test
    fun `a wrong name gets a guess and its line, the rest still applies`() {
        val read = ConfigFile.read("{\n  \"scale\": 1.5,\n  \"terminl\": \"foot\"\n}")
        val problem = read.problems.single()
        assertTrue(problem.startsWith("line 3: terminl:"), problem)
        assertTrue("did you mean \"terminal\"" in problem, problem)
        assertEquals(1.5, read.values["scale"], "the good one stays")

        val deep = problems("{ \"effects\": { \"cornerRadus\": 8 } }").single()
        assertTrue("effects.cornerRadus" in deep && "cornerRadius" in deep, deep)
    }

    @Test
    fun `numbers outside their limits and wrong kinds are caught`() {
        assertTrue("0 to 500" in problems("{ \"layout\": { \"gaps\": { \"inner\": 1000 } } }").single())
        assertTrue("0 to 500" in problems("{ \"layout\": { \"gaps\": { \"left\": -4 } } }").single())
        assertTrue("whole number" in problems("{ \"layout\": { \"gaps\": { \"outer\": 2.5 } } }").single())
        assertTrue("0.5 to 4" in problems("{ \"scale\": 9 }").single())
        assertTrue("true or false" in problems("{ \"hideCursorWhileTyping\": \"yes\" }").single())
        assertTrue("colour" in problems("{ \"lockColor\": \"red\" }").single())
        assertTrue("2 items" in problems("{ \"rules\": [ { \"appId\": \"x\", \"size\": [300] } ] }").single())
        assertTrue("is not one of" in problems("{ \"layout\": { \"name\": \"dwindel\" } }").single())
    }

    @Test
    fun `a special workspace's name with a space is a problem with its line, not a broken window later`() {
        val problem = problems("{\n  \"rules\": [\n    { \"appId\": \"x\", \"specialWorkspace\": \"my music\" }\n  ]\n}").single()
        assertTrue(problem.startsWith("line 3:") && "one word" in problem, problem)
        assertEquals(emptyList(), problems("{ \"rules\": [ { \"appId\": \"x\", \"specialWorkspace\": \"special:music\" } ] }"))
        assertTrue("one word" in kotlin.runCatching { dev.caulis.wm.WindowRule().specialWorkspace = "my music" }.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun `the focus after unlocking is a setting`() {
        val caulis = Caulis()
        val read = ConfigFile.read("{ \"unlockFocus\": \"newest-before\" }")
        assertEquals(emptyList(), read.problems)
        Settings.apply(caulis, read.values)
        assertEquals(dev.caulis.core.UnlockFocus.NEWEST_BEFORE, caulis.unlockFocus)
        assertTrue("is not one of" in problems("{ \"unlockFocus\": \"newest\" }").single())
    }

    @Test
    fun `choices forgive case and dashes`() {
        val read = ConfigFile.read("{ \"layout\": { \"name\": \"Master_Stack\" }, \"monitorEdges\": \"PROPORTIONAL\" }")
        assertEquals(emptyList(), read.problems)
        @Suppress("UNCHECKED_CAST")
        assertEquals("master-stack", (read.values["layout"] as Map<String, Any?>)["name"])
        assertEquals("proportional", read.values["monitorEdges"])
    }

    @Test
    fun `keys can be changed or turned off, own keys take actions`() {
        assertEquals(emptyList(), problems("{ \"keys\": { \"close\": \"Super+W\", \"quit\": false, \"terminal\": null } }"))
        assertTrue("there is no such setting" in problems("{ \"keys\": { \"closee\": \"Super+W\" } }").single())
        assertTrue(problems("{ \"keys\": { \"close\": \"Super+Nonsense\" } }").isNotEmpty(), "not a key")

        assertEquals(emptyList(), problems("{ \"bind\": { \"Super+B\": \"exec firefox\", \"Super+Shift+3\": \"move-to-workspace 3\" } }"))
        val action = problems("{ \"bind\": { \"Super+B\": \"exce firefox\" } }").single()
        assertTrue("did you mean \"exec\"" in action, action)
        assertTrue("1 to 99" in problems("{ \"bind\": { \"Super+B\": \"workspace 0\" } }").single())
        assertTrue(problems("{ \"bind\": { \"Super+Nonsense\": \"close\" } }").isNotEmpty(), "the combination is checked too")
    }

    @Test
    fun `actions as text`() {
        Actions.parse("exec foot -e htop")
        Actions.parse("Move Left")
        Actions.parse("layout scrolling-columns")
        assertFailsWith<IllegalArgumentException> { Actions.parse("exec") }
        assertFailsWith<IllegalArgumentException> { Actions.parse("focus sideways") }
        assertTrue("did you mean \"close\"" in assertFailsWith<IllegalArgumentException> { Actions.parse("clsoe") }.message!!)
    }

    @Test
    fun `applied, it sets what it says and leaves the rest`() {
        val caulis = Caulis().apply { terminal = "kitty" }
        val read = ConfigFile.read(
            """
            {
              "layout": { "name": "master-stack", "ratio": 0.6, "gaps": { "inner": 4, "outer": 12, "top": 30 } },
              "scale": 1.5,
              "keys": { "close": "Super+W", "quit": false },
              "bind": { "Super+B": "exec firefox" },
              "rules": [ { "appId": "mpv", "floating": true, "margin": [1, 2, 3, 4] } ],
              "effects": { "preset": "modern", "cornerRadius": 14, "border": false },
              "lockColor": "#102030",
              "startup": [ "waybar" ]
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        Settings.apply(caulis, read.values)

        assertEquals("Master", caulis.layout.name)
        assertEquals(1.5, caulis.scale)
        assertEquals("kitty", caulis.terminal, "not in the file: as the code says")
        assertEquals("Super+W", caulis.keys.all()["close"])
        assertNull(caulis.keys.all()["quit"], "turned off")
        assertEquals(1, caulis.bindings.size)
        assertEquals(1, caulis.rules.size)
        assertEquals(14, caulis.effects.cornerRadius)
        assertNull(caulis.effects.border)
        assertEquals(Color.hex("#102030"), caulis.lockColor)
        assertEquals(1, caulis.startActions.size)
    }

    @Test
    fun `read again from its folder, the file replaces what it added before`() {
        val folder = Files.createTempDirectory("caulis-config")
        try {
            val caulis = Caulis().apply {
                configDir = folder
                rule { appId("own") } // from the code: stays
            }
            assertEquals(emptyList(), ConfigFile.applyTo(caulis), "no file: nothing to say")

            val file = folder.resolve("config.json")
            Files.writeString(file, "{ \"rules\": [ { \"appId\": \"a\" }, { \"appId\": \"b\" } ], \"bind\": { \"Super+B\": \"close\" } }")
            assertEquals(emptyList(), ConfigFile.applyTo(caulis))
            assertEquals(3, caulis.rules.size)
            Files.writeString(file, "{ \"rules\": [ { \"appId\": \"c\" } ], \"layout\": { \"name\": \"dwindle\" }, \"scale\": 7 }")
            val problems = ConfigFile.applyTo(caulis)
            assertEquals(2, caulis.rules.size, "the code's own rule and the file's new one")
            assertEquals(0, caulis.bindings.size)
            assertEquals("Dwindle", caulis.layout.name)
            assertTrue(problems.single().startsWith("config.json, line 1: scale:"), problems.single())

            assertEquals(problems.map { it.removePrefix("config.json, ") }, ConfigFile.check(file))
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the command line checks a file and prints the schema and the Nix options`() {
        val folder = Files.createTempDirectory("caulis-config")
        try {
            val good = folder.resolve("good.json").also { Files.writeString(it, "{ \"scale\": 2 }") }
            val bad = folder.resolve("bad.json").also { Files.writeString(it, "{ \"scale\": 20 }") }
            val caulis = Caulis()
            assertEquals(0, CommandLine.handle(caulis, listOf("--check-config", good.toString())))
            assertEquals(1, CommandLine.handle(caulis, listOf("--check-config", bad.toString())))
            assertNull(CommandLine.handle(caulis, listOf("--something-else")), "starts as usual")
            assertNull(CommandLine.handle(caulis, emptyList()))
        } finally {
            folder.toFile().deleteRecursively()
        }

        val schema = Json.parse(CommandLine.jsonSchema()) as JsonValue.Obj
        assertTrue("properties" in schema.fields, "the schema is JSON itself")
        val nix = CommandLine.nixOptions()
        assertTrue("{ lib }:" in nix && "lib.types.submodule" in nix && "(lib.types.ints.between (0) (500))" in nix)
        assertEquals(nix.count { it == '(' }, nix.count { it == ')' }, "balanced")
        assertEquals(nix.count { it == '{' }, nix.count { it == '}' }, "balanced")
    }

    @Test
    fun `the guide's example is right`() {
        val guide = java.nio.file.Files.readString(java.nio.file.Path.of("../docs/getting-started.md"))
        val example = guide.substringAfter(". Settings as data: config.json\n").substringAfter("```jsonc\n").substringBefore("```")
        assertTrue(example.contains("\"layout\""))
        assertEquals(emptyList(), ConfigFile.read(example).problems)
    }

    @Test
    fun `workspaces and the primary monitor`() {
        val read = ConfigFile.read(
            """
            {
              "workspaceMode": "primary-only",
              "primaryMonitor": "DP-2",
              "workspaces": { "count": 5, "rules": [ { "workspace": 2, "name": "web", "monitor": "DP-2", "layout": { "name": "columns" }, "persistent": true } ] },
              "bind": { "Super+Tab": "workspace back", "Super+P": "focus-monitor primary", "Super+Shift+P": "move-to-monitor HDMI-A-1" }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        assertEquals(dev.caulis.core.WorkspaceMode.PRIMARY_ONLY, caulis.workspaceMode)
        assertEquals("DP-2", caulis.primaryMonitor)
        assertEquals(5, caulis.workspaceCount)
        val rule = caulis.workspaceRules.single()
        assertEquals("web" to "Columns", rule.name to rule.layout?.name)
        assertTrue("1 to 99" in problems("{ \"workspaces\": { \"count\": 0 } }").single())
        assertTrue("is not a workspace" in problems("{ \"bind\": { \"Super+Tab\": \"workspace sideways\" } }").single())
    }

    @Test
    fun `input, key modes, keys that repeat, the lid`() {
        val read = ConfigFile.read(
            """
            {
              "keyboard": { "layouts": "us,ru", "variants": ",phonetic", "repeatRate": 40, "repeatDelay": 250 },
              "input": {
                "focusFollowsMouse": true,
                "pointers": { "speed": -0.3, "accelProfile": "flat" },
                "touchpads": { "naturalScroll": true, "tapButtons": "left-right-middle", "clickMethod": "fingers", "disableWhileTyping": true },
                "devices": { "Logitech*": { "speed": -0.6, "scrollFactor": 2, "scrollButton": "MouseMiddle" } }
              },
              "bind": {
                "XF86AudioRaiseVolume": { "action": "exec wpctl set-volume @DEFAULT_SINK@ 5%+", "repeat": true, "locked": true },
                "Super": "overview",
                "Super+WheelDown": "workspace next",
                "MouseSide": "workspace back",
                "Super+R": "mode resize"
              },
              "modes": { "resize": { "Left": { "action": "resize left", "repeat": true }, "Escape": "mode reset" } },
              "lid": { "close": "lock" }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        assertEquals(40 to 250, caulis.repeatRate to caulis.repeatDelay)
        assertEquals(",phonetic", caulis.keyboardVariants)
        assertTrue(caulis.focusFollowsMouse)
        assertEquals(dev.caulis.core.input.AccelProfile.FLAT, caulis.input.pointers.accelProfile)
        assertEquals(0x112, caulis.input.devices.getValue("Logitech*").scrollButton)
        assertEquals(listOf(null, null, null, null, null, "resize", "resize"), caulis.bindings.map { it.mode })
        assertTrue(caulis.bindings.first().options.let { it.repeat && it.whileLocked })
        assertTrue("1 to" in problems("{ \"input\": { \"pointers\": { \"speed\": 3 } } }").single())
        assertTrue("needs an \"action\"" in problems("{ \"bind\": { \"Super+B\": { \"repeat\": true } } }").single())
    }

    @Test
    fun `idle timers, turned monitors, activation`() {
        val read = ConfigFile.read(
            """
            {
              "monitors": { "DP-2": { "transform": "90" }, "eDP-1": { "transform": "flipped-180" } },
              "idle": [ { "after": 300, "action": "dpms off", "resume": "dpms on" }, { "after": 600, "action": "lock" } ],
              "focusOnActivate": false,
              "fullscreenKeepsAwake": true,
              "bind": { "Super+U": "focus-urgent", "Super+Shift+I": "keep-awake toggle" }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), read.problems)
        val caulis = Caulis()
        Settings.apply(caulis, read.values)
        val field = Caulis::class.java.getDeclaredField("monitorSettings").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val monitors = field.get(caulis) as Map<String, Caulis.MonitorSettings>
        assertEquals(dev.caulis.core.Transform.ROTATE_90, monitors.getValue("DP-2").transform)
        assertEquals(dev.caulis.core.Transform.FLIPPED_180, monitors.getValue("eDP-1").transform)
        assertEquals(2, caulis.idleWatches.size)
        assertEquals(false to true, caulis.focusOnActivate to caulis.fullscreenKeepsAwake)
        assertTrue("it needs \"after\"" in problems("{ \"idle\": [ { \"action\": \"lock\" } ] }").single())
        assertTrue("is not on, off or toggle" in problems("{ \"bind\": { \"Super+D\": \"dpms sideways\" } }").single())
    }
}
