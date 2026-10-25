package dev.caulis.lua

import org.luaj.vm2.lib.jse.JsePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The LuaLS annotations in the repository (docs/lua/caulis.lua) say what the code says. After
 * changing a setting or the caulis table:
 *
 * ```
 * CAULIS_UPDATE_GENERATED=1 ./gradlew :caulis-lua:test --tests '*LuaStubsTest' --rerun
 * ```
 */
class LuaStubsTest {

    private val file: Path = Path.of("").toAbsolutePath().parent.resolve("docs/lua/caulis.lua")

    @Test
    fun `the stubs are up to date, and Lua itself reads them`() {
        val text = LuaStubs.text()
        if (System.getenv("CAULIS_UPDATE_GENERATED") == "1") {
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        assertEquals(text, if (Files.exists(file)) Files.readString(file) else "", "docs/lua/caulis.lua is out of date (see LuaStubsTest)")
        assertTrue("---@field isFloating? boolean" in text, "settings of rules are there")
        assertTrue("function caulis.every(millis, fn) end" in text)
        JsePlatform.standardGlobals().load(text, "caulis.lua").call() // (only annotations and empty functions)
    }
}
