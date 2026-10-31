import dev.caulis.wm.caulis
import java.nio.file.Files

// A compositor configured in Lua alone. This little program never needs changing: all you write
// is ~/.config/my-compositor/config.lua, read again whenever you save it.
fun main() = caulis {
    title = "My Lua compositor"
    configName = "my-compositor"            // your folder of files: ~/.config/my-compositor

    // The first time, config.lua is written there from this project.
    val config = file("config.lua")
    if (!Files.exists(config)) {
        Files.createDirectories(config.parent)
        object {}.javaClass.getResourceAsStream("/config.lua")!!.use { Files.copy(it, config) }
    }
}
