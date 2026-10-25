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

    private companion object {
        const val KEY_SUPER = 125
        const val KEY_B = 48
    }
}
