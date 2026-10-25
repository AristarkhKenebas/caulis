package dev.caulis.wm

import dev.caulis.core.NoticeLevel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** config.json while running: its mistakes on screen, and taken on again when saved. */
class ConfigJsonTest {

    private val harness = CaulisTest()

    @Test
    fun `a mistake shows up, saving the file again takes it on without restarting`() = with(harness) {
        val folder = Files.createTempDirectory("caulis-config")
        val file = folder.resolve("config.json")
        Files.writeString(file, "{ \"terminal\": \"foot\", \"terminl\": \"x\" }")
        try {
            withCaulis({
                useConfigFile = true
                configDir = folder
                terminal = "kitty"
            }) { caulis, client ->
                client.waitFor { caulis.onLoop { r -> r.desktop.notices.any { it.level == NoticeLevel.WARNING && "did you mean \"terminal\"" in it.text } } }
                assertEquals("foot", caulis.onLoop { r -> r.config.terminal }, "the good setting applies")

                Files.writeString(file, "{ \"terminal\": \"alacritty\" }")
                client.waitFor { caulis.onLoop { r -> r.config.terminal == "alacritty" } }
                client.waitFor { caulis.onLoop { r -> r.desktop.notices.none { it.level == NoticeLevel.WARNING } } }
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
