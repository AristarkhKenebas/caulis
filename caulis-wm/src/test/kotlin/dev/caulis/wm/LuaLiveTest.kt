package dev.caulis.wm

import dev.caulis.core.NoticeLevel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** config.lua in a running compositor: keys, events and timers as Lua functions. */
class LuaLiveTest {

    private val harness = CaulisTest()

    @Test
    fun `Lua functions on keys, events and timers, and a mistake in one is shown, not fatal`() = with(harness) {
        val folder = Files.createTempDirectory("caulis-lua")
        Files.writeString(
            folder.resolve("config.lua"),
            """
            caulis.bind("Super+B", function() caulis.run("workspace 3") end)
            caulis.on("window-opened", function(w)
              if w.appId == "music" then caulis.run("move-to-workspace 2", w.id) end
            end)
            caulis.on("start", function()
              caulis.after(50, function() caulis.notify("the timer ran") end)
            end)
            caulis.on("focus-changed", function(w)
              if w and w.appId == "broken" then error("boom") end
            end)
            """.trimIndent(),
        )
        try {
            withCaulis({
                useConfigFile = true
                configDir = folder
            }) { caulis, client ->
                client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { it.text == "the timer ran" } } }

                client.window { it.setAppId("music") }.draw()
                client.waitFor { caulis.onLoop { r -> r.desktop.allWindows.any { it.appId == "music" && r.desktop.workspaceNumberOf(it) == 2 } } }

                client.window { it.setAppId("broken") }.draw()
                client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { it.level == NoticeLevel.WARNING && "config.lua:9: boom" in it.text } } }

                caulis.onLoop { r ->
                    r.key(0, KEY_SUPER, true)
                    r.key(0, KEY_B, true)
                    r.key(0, KEY_B, false)
                    r.key(0, KEY_SUPER, false)
                }
                client.waitFor { caulis.onLoop { r -> r.desktop.activeWorkspace.number } == 3 }
                assertEquals(1, caulis.onLoop { r -> r.desktop.allWindows.count { it.appId == "broken" } }, "still running")
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `handles, repeating timers, queries, a Lua layout, and the REPL over IPC`() = with(harness) {
        val folder = Files.createTempDirectory("caulis-lua")
        Files.writeString(
            folder.resolve("config.lua"),
            """
            ticks = 0
            opened = {}
            key = caulis.bind("Super+B", function() caulis.run("workspace 3") end)
            listener = caulis.on("window-opened", function(w) table.insert(opened, w.appId) end)
            caulis.on("start", function()
              clock = caulis.every(20, function()
                ticks = ticks + 1
                if ticks == 3 then clock.cancel() end
              end)
            end)
            caulis.layout("halves", function(windows, area, focused)
              local out = {}
              for i = 1, #windows do
                out[i] = { x = area.x + (i - 1) * 100, y = area.y, width = 100, height = 100 }
              end
              return out
            end)
            """.trimIndent(),
        )
        try {
            withCaulis({
                useConfigFile = true
                configDir = folder
                configName = "luatest"
            }) { caulis, client ->
                val socket = caulis.socketDirectory!!.resolve("luatest-${caulis.onLoop { it.socket }}.sock")
                dev.caulis.ipc.CaulisIpc.connect(socket).use { ipc ->
                    client.waitFor { ipc.eval("return ticks") == "3" }
                    Thread.sleep(150)
                    assertEquals("3", ipc.eval("return ticks"), "the timer stopped itself")

                    client.window { it.setAppId("first") }.draw()
                    client.window { it.setAppId("second") }.draw()
                    client.waitFor { caulis.onLoop { r -> r.desktop.windows.size } == 2 }
                    assertEquals("first\tsecond", ipc.eval("return opened[1], opened[2]"))
                    assertEquals("2", ipc.eval("return #caulis.windows()"))
                    assertEquals("second", ipc.eval("return caulis.focusedWindow().appId"))
                    caulis.onLoop { r ->
                        assertEquals("halves", r.desktop.activeWorkspace.layout.name)
                        val xs = r.desktop.windows.map { r.desktop.screenRect(it)!!.x }.sorted()
                        kotlin.test.assertTrue(xs[1] - xs[0] in 98..102, "side by side, 100 apart, as Lua said (borders aside): $xs")
                    }

                    // A listener taken away hears nothing more.
                    ipc.eval("listener.remove()")
                    client.window { it.setAppId("third") }.draw()
                    client.waitFor { caulis.onLoop { r -> r.desktop.windows.size } == 3 }
                    assertEquals("2", ipc.eval("return #opened"))

                    // A key switched off goes to programs; on again, it works.
                    ipc.eval("key.setEnabled(false)")
                    press(caulis)
                    assertEquals(1, caulis.onLoop { r -> r.desktop.activeWorkspace.number })
                    ipc.eval("key.setEnabled(true)")
                    press(caulis)
                    client.waitFor { caulis.onLoop { r -> r.desktop.activeWorkspace.number } == 3 }

                    // A mistake typed in is answered, not fatal.
                    val mistake = kotlin.runCatching { ipc.eval("nil + 1") }.exceptionOrNull()
                    kotlin.test.assertTrue(mistake is dev.caulis.ipc.IpcException && "repl" in mistake.message.orEmpty(), "$mistake")
                    assertEquals("{ a = 1, b = \"x\" }", ipc.eval("return { a = 1, b = 'x' }"))
                }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a configuration without a single key still has a way out`() = with(harness) {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        dev.caulis.log.Log.listener = { lines += it }
        try {
            withCaulis({
                keys { all().keys.forEach { name -> set(name, null) } }
            }) { caulis, client ->
                client.waitFor { lines.any { "no keys at all" in it } }
                caulis.onLoop { r ->
                    r.keys.key(KEY_SUPER, true)
                    r.keys.key(KEY_SHIFT, true)
                    kotlin.test.assertTrue(r.keys.has(KEY_ESC), "Super+Shift+Escape quits")
                    r.keys.releaseAll()
                }
            }
        } finally {
            dev.caulis.log.Log.listener = null
        }
    }

    @Test
    fun `a timer in the file itself starts when the compositor runs, and saving the file never doubles it`() = with(harness) {
        val folder = Files.createTempDirectory("caulis-lua")
        val file = folder.resolve("config.lua")
        Files.writeString(file, "ticks = (ticks or 0)\ncaulis.every(30, function() ticks = ticks + 1 end)\n")
        try {
            withCaulis({
                useConfigFile = true
                configDir = folder
                configName = "luatimer"
            }) { caulis, client ->
                val socket = caulis.socketDirectory!!.resolve("luatimer-${caulis.onLoop { it.socket }}.sock")
                dev.caulis.ipc.CaulisIpc.connect(socket).use { ipc ->
                    client.waitFor { (ipc.eval("return ticks").toIntOrNull() ?: 0) >= 3 }
                    // Saved again (a new state): one timer, not two.
                    Files.writeString(file, "ticks = 0\ncaulis.every(30, function() ticks = ticks + 1 end)\n-- saved again\n")
                    client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { "config.lua" in it.text } } }
                    val start = ipc.eval("return ticks").toInt()
                    Thread.sleep(600)
                    val ticked = ipc.eval("return ticks").toInt() - start
                    kotlin.test.assertTrue(ticked in 10..30, "about 20 ticks in 600 ms from one timer, not 40: $ticked")
                }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    private fun CaulisTest.press(caulis: Caulis) = caulis.onLoop { r ->
        r.key(0, KEY_SUPER, true)
        r.key(0, KEY_B, true)
        r.key(0, KEY_B, false)
        r.key(0, KEY_SUPER, false)
    }

    private companion object {
        const val KEY_SHIFT = 42
        const val KEY_ESC = 1
        const val KEY_SUPER = 125
        const val KEY_B = 48
    }
}
