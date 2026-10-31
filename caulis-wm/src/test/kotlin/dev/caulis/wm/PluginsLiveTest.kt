package dev.caulis.wm

import dev.caulis.ipc.CaulisIpc
import dev.caulis.plugins.PluginManifest
import dev.caulis.plugins.PluginState
import testplugins.wm.Shared
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jar plugins in a running compositor: every hook works, and goes when the plugin does. */
class PluginsLiveTest {
    private val harness = CaulisTest()

    private fun jar(folder: Path, id: String, main: String, version: String = "1.0") {
        val file = folder.resolve("$id.jar")
        val temporary = Files.createTempFile(folder, ".writing", ".part")
        val root = Path.of(AllHooksMarker.location())
        JarOutputStream(Files.newOutputStream(temporary)).use { out ->
            out.putNextEntry(JarEntry(PluginManifest.FILE_NAME))
            out.write("id=$id\nversion=$version\napi=caulis:0.1\nmain=$main\n".toByteArray())
            out.closeEntry()
            Files.walk(root.resolve("testplugins/wm")).use { all ->
                all.filter { it.toString().endsWith(".class") && "Shared" !in it.fileName.toString() }.forEach { path ->
                    out.putNextEntry(JarEntry(root.relativize(path).toString()))
                    out.write(Files.readAllBytes(path))
                    out.closeEntry()
                }
            }
        }
        Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private object AllHooksMarker {
        fun location(): String = java.io.File(testplugins.wm.AllHooks::class.java.protectionDomain.codeSource.location.toURI()).path
    }

    private fun setup(folder: Path, configure: Caulis.() -> Unit = {}): Caulis.() -> Unit = {
        plugins {
            directories = listOf(folder)
            settleMillis = 20
            stateFile = folder.resolveSibling(folder.fileName.toString() + ".state")
            leakCheckMillis = 0
            sharePackages("testplugins.wm.Shared")
        }
        configure()
    }

    private fun CaulisTest.state(caulis: Caulis, id: String) = with(this) { caulis.onLoop { r -> r.rplugins.manager?.list()?.firstOrNull { it.id == id }?.state } }

    private fun held(r: Running) = listOf(
        r.config.windowOpened.size, r.config.keyHandlers.size, r.config.bindings.size, r.config.effectRules.size, r.config.rules.size,
        r.rplugins.actions.size, r.rplugins.commands.size, r.rplugins.layouts.size, r.desktop.screenShader == null,
    )

    @Test
    fun `every hook works, survives a reload of the configuration, and goes with the plugin`() = with(harness) {
        val folder = Files.createTempDirectory("plugins")
        Shared.events.clear()
        jar(folder, "all-hooks", "testplugins.wm.AllHooks")
        try {
            withCaulis(setup(folder)) { caulis, client ->
                // (Started a moment after the compositor.)
                client.waitFor { state(caulis, "all-hooks") == PluginState.ENABLED }
                // What there is without it.
                val before = caulis.onLoop { r ->
                    r.rplugins.command(listOf("disable", "all-hooks"))
                    held(r).also { r.rplugins.command(listOf("enable", "all-hooks")) }
                }
                caulis.onLoop { r -> assertNotNull(r.desktop.screenShader) }

                client.window { it.setAppId("first") }.draw()
                client.waitFor { "opened first" in Shared.events && "tick" in Shared.events && "drawn" in Shared.events }

                // The plugin's key, its input hook taking F12, its action, its command.
                caulis.onLoop { r ->
                    r.rinput.key(0, 125, true)
                    r.rinput.key(0, 68, true)
                    r.rinput.key(0, 68, false)
                    r.rinput.key(0, 125, false)
                    r.rinput.key(0, 88, true)
                    r.rinput.key(0, 88, false)
                }
                client.waitFor { "key" in Shared.events && "F12" in Shared.events }
                caulis.onLoop { it.config.run("plugin run all-hooks-action hello") }
                client.waitFor { "action hello" in Shared.events }
                caulis.onLoop { it.config.run("all-hooks-action direct") }
                client.waitFor { "action direct" in Shared.events }
                val socket = caulis.onLoop { r -> r.ipc!!.path }
                assertEquals("said one two", CaulisIpc.connect(socket).use { it.eval("cmd all-hooks one two", "plugin") })
                assertTrue("all-hooks" in CaulisIpc.connect(socket).use { it.eval("list", "plugin") })
                caulis.onLoop { it.config.run("plugin layout all-hooks") }
                caulis.onLoop { r -> assertEquals("all-hooks", r.desktop.layout.name) }

                // A new configuration keeps it all (as what onStart registers).
                caulis.onLoop { r ->
                    val next = Caulis()
                    r.config.plugins.copyInto(next.plugins)
                    r.rreload.reconfigure(next)
                }
                Shared.events.clear()
                caulis.onLoop { it.config.run("workspace 2") }
                client.waitFor { "workspace 2" in Shared.events && "tick" in Shared.events }

                // Off: everything is taken away.
                caulis.onLoop { r -> assertEquals("disabled", r.rplugins.command(listOf("disable", "all-hooks"))) }
                assertTrue("disable" in Shared.events && "scope closed" in Shared.events)
                caulis.onLoop { r ->
                    assertEquals(before, held(r))
                    assertFalse(r.desktop.layout.name == "all-hooks")
                    assertTrue(r.rplugins.manager!!.list().single().held.isEmpty())
                }
                Shared.events.clear()
                Thread.sleep(200)
                assertFalse("tick" in Shared.events, "its timer stopped")
                caulis.onLoop { r -> assertEquals("enabled", r.rplugins.command(listOf("enable", "all-hooks"))) }
                caulis.onLoop { r -> assertEquals("disabled", r.rplugins.command(listOf("disable", "all-hooks"))) }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a plugin that throws is disabled with a notice and the compositor goes on`() = with(harness) {
        val folder = Files.createTempDirectory("plugins")
        jar(folder, "thrower", "testplugins.wm.ThrowsOnWindow")
        try {
            withCaulis(setup(folder)) { caulis, client ->
                client.waitFor { state(caulis, "thrower") == PluginState.ENABLED }
                client.window { it.setAppId("a") }.draw()
                client.waitFor { state(caulis, "thrower") == PluginState.FAILED }
                client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { "broken plugin" in it.text } } }
                client.window { it.setAppId("b") }.draw()
                client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.size == 2 } }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a plugin that holds up the compositor is reported, interrupted, and disabled`() = with(harness) {
        val folder = Files.createTempDirectory("plugins")
        Shared.events.clear()
        jar(folder, "hangs", "testplugins.wm.HangsOnKey")
        try {
            withCaulis(setup(folder) { plugins { hangReportMillis = 100; hangInterruptMillis = 300; hangQuarantineMillis = 0 } }) { caulis, client ->
                client.waitFor { state(caulis, "hangs") == PluginState.ENABLED }
                caulis.onLoop { it.config.post { run("hang-now") } }
                client.waitFor { state(caulis, "hangs") == PluginState.FAILED }
                assertTrue("interrupted" in Shared.events)
                client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { "froze" in it.title.orEmpty() || "froze" in it.text } } }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `enabling and disabling again and again leaves no class loader behind`() = with(harness) {
        val folder = Files.createTempDirectory("plugins")
        Shared.events.clear()
        jar(folder, "all-hooks", "testplugins.wm.AllHooks")
        try {
            withCaulis(setup(folder)) { caulis, client ->
                client.waitFor { state(caulis, "all-hooks") == PluginState.ENABLED }
                client.window { it.setAppId("first") }.draw()
                val loaders = ArrayList<WeakReference<ClassLoader>>()
                repeat(ROUNDS) { round ->
                    loaders += WeakReference(caulis.onLoop { r -> assertNotNull(r.rplugins.manager!!.classLoaderOf("all-hooks")) })
                    Shared.events.clear()
                    // Let it draw and tick, so that everything holding drawings and timers has seen them.
                    caulis.onLoop { r -> r.desktop.requestRedraw() }
                    client.waitFor { "drawn" in Shared.events }
                    caulis.onLoop { r ->
                        assertEquals("disabled", r.rplugins.command(listOf("disable", "all-hooks")))
                        r.desktop.requestRedraw()
                    }
                    Thread.sleep(30)
                    caulis.onLoop { r ->
                        if (round % 2 == 0) assertEquals("enabled", r.rplugins.command(listOf("enable", "all-hooks")))
                        else {
                            assertEquals("enabled", r.rplugins.command(listOf("enable", "all-hooks")))
                            assertEquals("reloaded", r.rplugins.command(listOf("reload", "all-hooks")))
                        }
                    }
                }
                caulis.onLoop { r -> assertEquals("disabled", r.rplugins.command(listOf("disable", "all-hooks"))) }
                caulis.onLoop { r -> r.desktop.requestRedraw() }
                Thread.sleep(100)
                var left = -1
                for (attempt in 1..40) {
                    System.gc()
                    left = loaders.count { it.get() != null }
                    if (left == 0) break
                    caulis.onLoop { r -> r.desktop.requestRedraw() }
                    Thread.sleep(100)
                }
                assertEquals(0, left, "class loaders still in memory (a heap dump shows what holds them)")
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no plugins cost no thread and nothing on the way to a frame`() = with(harness) {
        val folder = Files.createTempDirectory("plugins")
        try {
            withCaulis(setup(folder)) { caulis, client ->
                client.window { it.setAppId("a") }.draw()
                Thread.sleep(100)
                caulis.onLoop { r -> assertEquals(emptyList(), r.rplugins.manager!!.list()) }
                val names = Thread.getAllStackTraces().keys.map { it.name }
                assertFalse("caulis-plugin-watchdog" in names, "$names")
                caulis.onLoop { r -> assertNull(r.rplugins.manager!!.classLoaderOf("nothing")) }
            }
            // And off altogether: not even the manager.
            withCaulis(setup(folder) { plugins { enabled = false } }) { caulis, _ ->
                caulis.onLoop { r -> assertNull(r.rplugins.manager) }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a Lua plugin runs in its sandbox, reloads on save, and an endless loop disables it`() = with(harness) {
        val folder = Files.createTempDirectory("plugins")
        val file = folder.resolve("sandboxed.lua")
        fun save(text: String) {
            val temporary = folder.resolve(".saving")
            Files.writeString(temporary, text)
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        save(
            """
            -- version: 1.0
            local seen = {}
            for _, name in ipairs({ "io", "debug", "luajava", "coroutine", "dofile", "loadfile", "package" }) do
              if _G[name] ~= nil then seen[#seen + 1] = name end
            end
            plugin.command("sandbox-leaks", function() return table.concat(seen, ",") end)
            plugin.command("sandbox-exec", function() return tostring(pcall(plugin.run, "exec true")) end)
            plugin.command("sandbox-os", function() return tostring(os.execute) .. tostring(os.getenv) end)
            plugin.command("sandbox-version", function() return "first" end)
            plugin.bind("Super+F10", function() plugin.notify("from lua") end)
            plugin.action("spin", function() while true do end end)
            """.trimIndent(),
        )
        try {
            withCaulis(setup(folder) { scriptCallLimitMillis = 100 }) { caulis, client ->
                client.waitFor { state(caulis, "sandboxed") == PluginState.ENABLED }
                fun cmd(name: String) = caulis.onLoop { r -> r.rplugins.command(listOf("cmd", name)) }
                assertEquals("", cmd("sandbox-leaks"), "nothing that reaches outside")
                assertEquals("false", cmd("sandbox-exec"))
                assertEquals("nilnil", cmd("sandbox-os"))
                assertEquals("first", cmd("sandbox-version"))
                // Saved again: it runs anew.
                save("-- version: 2.0\nplugin.command('sandbox-version', function() return 'second' end)\nplugin.action('spin', function() while true do end end)")
                client.waitFor { caulis.onLoop { r -> r.rplugins.commands["sandbox-version"] != null && r.rplugins.command(listOf("cmd", "sandbox-version")) == "second" } }
                assertFalse(caulis.onLoop { r -> "sandbox-leaks" in r.rplugins.commands }, "the first one's commands went")
                // A loop that does not end is stopped by its time limit, and the plugin goes.
                caulis.onLoop { it.config.run("spin") }
                client.waitFor { state(caulis, "sandboxed") == PluginState.FAILED }
                client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { "too long" in it.text } } }
                // A mistake when saved: told, and nothing is left running.
                save("this is not lua")
                client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { "could not start" in it.title.orEmpty() || "does not work" in it.title.orEmpty() } } }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val ROUNDS = 12
    }
}
