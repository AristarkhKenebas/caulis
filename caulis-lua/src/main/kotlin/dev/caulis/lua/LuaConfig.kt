package dev.caulis.lua

import dev.caulis.core.Notice
import dev.caulis.core.NoticeLevel
import dev.caulis.core.Window
import dev.caulis.log.Logger
import dev.caulis.wm.Caulis
import dev.caulis.wm.config.Actions
import dev.caulis.wm.config.ConfigFile
import dev.caulis.wm.config.ConfigLanguage
import dev.caulis.wm.config.JsonValue
import dev.caulis.wm.config.Settings
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.nio.file.Files
import java.nio.file.Path

/**
 * config.lua: the configuration in Lua. One table, `caulis`, says everything:
 *
 * ```lua
 * caulis.settings {                                  -- what config.json can say
 *   layout = { name = "master-stack", gaps = { inner = 8 } },
 *   keys = { quit = false },
 * }
 * caulis.bind("Super+B", "exec firefox")             -- an action, as text
 * caulis.bind("Super+N", function() caulis.notify("Hello") end)
 * caulis.rule { appId = "mpv", floating = true }
 * caulis.on("window-opened", function(w)
 *   if w.appId == "firefox" then caulis.run("move-to-workspace 2", w.id) end
 * end)
 * ```
 *
 * A mistake is reported with its line and the rest still applies. Lua code that runs too
 * long (a loop that never ends) is stopped, so the desktop never freezes.
 */
class LuaConfig : ConfigLanguage {

    override val fileName = "config.lua"

    override fun apply(caulis: Caulis, file: Path): List<String> = Script(caulis, file).load()

    // One run of config.lua: its Lua state, and what it reports.
    private class Script(val caulis: Caulis, val file: Path) {
        val log = Logger("lua")
        val name = file.fileName.toString()
        val problems = ArrayList<String>()
        val globals: Globals = JsePlatform.debugGlobals()

        // When the Lua code running now must stop (it runs too long).
        var deadline = Long.MAX_VALUE

        fun load(): List<String> {
            val code = try {
                Files.readString(file)
            } catch (e: Exception) {
                return listOf("$name cannot be read: ${e.message ?: e}")
            }
            // Modules of your own next to it: require("keys") is keys.lua there.
            val folder = file.parent.toAbsolutePath()
            globals.get("package").set("path", "$folder/?.lua;$folder/?/init.lua")
            globals.set("caulis", api())
            // Every so many instructions: has it run too long?
            globals.get("debug").get("sethook").call(
                object : VarArgFunction() {
                    override fun invoke(args: Varargs): Varargs {
                        if (System.nanoTime() > deadline) throw LuaError("it runs too long (an endless loop?)")
                        return NONE
                    }
                },
                LuaValue.valueOf(""),
                LuaValue.valueOf(10_000),
            )
            deadline = System.nanoTime() + LOAD_LIMIT
            try {
                globals.load(code, "@$name").call()
            } catch (e: LuaError) {
                problems += message(e)
            } finally {
                deadline = Long.MAX_VALUE
            }
            return problems
        }

        // --- the caulis table --------------------------------------------------------------------

        fun api(): LuaTable {
            val api = LuaTable()
            fun function(name: String, body: (Varargs) -> Varargs) {
                api.set(name, object : VarArgFunction() {
                    override fun invoke(args: Varargs): Varargs = body(args)
                })
            }
            function("settings") { args ->
                settings(args.checktable(1), "settings")
                LuaValue.NONE
            }
            function("rule") { args ->
                val rule = args.checktable(1)
                val wrapped = LuaTable().also { it.set("rules", LuaTable().also { list -> list.set(1, rule) }) }
                settings(wrapped, "rule")
                LuaValue.NONE
            }
            function("bind") { args ->
                val combination = args.checkjstring(1)
                val action = args.arg(2)
                val run: Caulis.() -> Unit = when {
                    action.isfunction() -> {
                        val fn = action.checkfunction();
                        { call("the key $combination", fn) }
                    }
                    action.isstring() -> try {
                        Actions.parse(action.tojstring())
                    } catch (e: IllegalArgumentException) {
                        throw LuaError(e.message ?: "not an action")
                    }
                    else -> throw LuaError("bind: an action (\"exec firefox\") or a function, not ${action.typename()}")
                }
                // { repeating = true, release = true, locked = true, description = "...", mode = "resize" } ("repeat" is a word of Lua)
                val more = args.arg(3)
                val options = if (more.istable()) {
                    val t = more.checktable()
                    dev.caulis.wm.BindOptions(
                        repeat = t.get("repeating").optboolean(false),
                        release = t.get("release").optboolean(false),
                        whileLocked = t.get("locked").optboolean(false),
                        description = t.get("description").optjstring(null),
                    )
                } else {
                    dev.caulis.wm.BindOptions()
                }
                val mode = if (more.istable()) more.get("mode").optjstring(null) else null
                try {
                    if (mode != null) caulis.mode(mode) { bind(combination, options, run) } else caulis.bind(combination, options, run)
                } catch (e: IllegalArgumentException) {
                    throw LuaError(e.message ?: "not a key combination")
                }
                LuaValue.NONE
            }
            function("mode") { args ->
                running("mode") { enterMode(args.optjstring(1, null)?.takeIf { it != "reset" }) }
                LuaValue.NONE
            }
            function("on") { args ->
                val event = args.checkjstring(1)
                val fn = args.checkfunction(2)
                when (event) {
                    "start" -> caulis.onStart { call("on start", fn) }
                    "reload" -> caulis.onReload { call("on reload", fn) }
                    "window-opened" -> caulis.onWindowOpened { w -> call("on window-opened", fn, window(w)) }
                    "window-closed" -> caulis.onWindowClosed { w -> call("on window-closed", fn, window(w)) }
                    "focus-changed" -> caulis.onFocusChanged { w -> call("on focus-changed", fn, w?.let(::window) ?: LuaValue.NIL) }
                    "workspace-changed" -> caulis.onWorkspaceChanged { n -> call("on workspace-changed", fn, LuaValue.valueOf(n)) }
                    "lid" -> caulis.onLid { closed -> call("on lid", fn, LuaValue.valueOf(closed)) }
                    "notice" -> caulis.onNotice { n: Notice -> call("on notice", fn, LuaValue.valueOf(n.text), LuaValue.valueOf(n.level.name.lowercase())) }
                    else -> throw LuaError("there is no event \"$event\" (there are: ${EVENTS.joinToString()})")
                }
                LuaValue.NONE
            }
            function("run") { args ->
                val action = args.checkjstring(1)
                val window = if (args.isnil(2)) null else running("run") { windowWithId(args.checklong(2)) ?: throw LuaError("there is no window ${args.checklong(2)}") }
                running("run") {
                    try {
                        run(action, window)
                    } catch (e: IllegalArgumentException) {
                        throw LuaError(e.message ?: "not an action")
                    }
                }
                LuaValue.NONE
            }
            function("state") { running("state") { state() } }
            function("after") { args ->
                val millis = args.checklong(1)
                val fn = args.checkfunction(2)
                running("after") { after(millis) { call("a timer", fn) } }
                LuaValue.NONE
            }
            function("notify") { args ->
                val level = when (args.optjstring(2, "info")) {
                    "info" -> NoticeLevel.INFO
                    "warning" -> NoticeLevel.WARNING
                    "error" -> NoticeLevel.ERROR
                    else -> throw LuaError("a notice is \"info\", \"warning\" or \"error\"")
                }
                running("notify") { notify(args.checkjstring(1), level) }
                LuaValue.NONE
            }
            function("log") { args ->
                log.info(args.checkjstring(1))
                LuaValue.NONE
            }
            function("file") { args -> LuaValue.valueOf(caulis.file(args.checkjstring(1)).toString()) }
            return api
        }

        // Settings written in Lua, read as config.json is (each checked; good ones apply).
        fun settings(table: LuaTable, what: String) {
            val line = currentLine()
            val json = try {
                toJson(table, line) as JsonValue.Obj
            } catch (e: IllegalArgumentException) {
                throw LuaError("$what: ${e.message}")
            }
            val read = ConfigFile.read(json)
            for (problem in read.problems) problems += problem.replaceFirst(Regex("^line (\\d+): "), "$name:$1: ")
            Settings.apply(caulis, read.values)
        }

        fun toJson(value: LuaValue, line: Int): JsonValue = when {
            value.isboolean() -> JsonValue.Bool(value.toboolean(), line, 0)
            value.isnumber() && value.type() == LuaValue.TNUMBER -> JsonValue.Num(value.todouble(), line, 0)
            value.isstring() -> JsonValue.Str(value.tojstring(), line, 0)
            value.istable() -> {
                val table = value.checktable()
                val length = table.length()
                val keys = table.keys()
                if (length > 0 && keys.size == length) {
                    JsonValue.Arr((1..length).map { toJson(table.get(it), line) }, line, 0)
                } else {
                    val fields = LinkedHashMap<String, JsonValue>()
                    for (key in keys.sortedBy { it.tojstring() }) {
                        if (!key.isstring() || key.isnumber()) throw IllegalArgumentException("names are words, not ${key.tojstring()}")
                        fields[key.tojstring()] = toJson(table.get(key), line)
                    }
                    JsonValue.Obj(fields, line, 0)
                }
            }
            else -> throw IllegalArgumentException("a ${value.typename()} cannot be a setting")
        }

        // The line of config.lua running now.
        fun currentLine(): Int {
            val info = globals.get("debug").get("getinfo")
            for (level in 1..20) {
                val frame = info.call(LuaValue.valueOf(level), LuaValue.valueOf("Sl"))
                if (frame.isnil()) break
                if (frame.get("short_src").tojstring().endsWith(name)) return frame.get("currentline").toint()
            }
            return 0
        }

        // --- calling Lua from the compositor -----------------------------------------------------

        // Calls [fn] (a key, an event, a timer); a mistake is shown, never thrown at the desktop.
        fun call(what: String, fn: LuaValue, vararg args: LuaValue) {
            deadline = System.nanoTime() + CALL_LIMIT
            try {
                fn.invoke(LuaValue.varargsOf(args))
            } catch (e: LuaError) {
                val message = "$what: ${message(e)}"
                log.warn(message)
                caulis.runningOrNull()?.notify(message, NoticeLevel.WARNING)
            } finally {
                deadline = Long.MAX_VALUE
            }
        }

        // "config.lua:5: ...", however LuaJ wrote where.
        fun message(e: LuaError): String =
            (e.message ?: "an error").lineSequence().first().removePrefix("@").replaceFirst(Regex("^([^:\\s]+):(\\d+):? "), "$1:$2: ")

        fun <T> running(what: String, body: Caulis.() -> T): T {
            if (caulis.runningOrNull() == null) throw LuaError("$what works while the compositor runs (in a key, an event or a timer)")
            return caulis.body()
        }

        fun Caulis.runningOrNull(): Caulis? = try {
            desktop
            this
        } catch (e: IllegalStateException) {
            null
        }

        // --- what is on screen -------------------------------------------------------------------

        fun window(w: Window): LuaTable {
            val d = caulis.desktop
            return LuaTable().apply {
                set("id", LuaValue.valueOf(caulis.idOf(w).toDouble()))
                set("appId", w.appId)
                set("title", w.title)
                set("pid", w.pid)
                set("workspace", d.workspaceNumberOf(w) ?: 0)
                set("monitor", d.monitorOf(w).name)
                set("focused", LuaValue.valueOf(w === d.focusedWindow))
                set("floating", LuaValue.valueOf(d.isFloating(w)))
                set("maximized", LuaValue.valueOf(w.maximized))
                set("fullscreen", LuaValue.valueOf(w.fullscreen))
                set("minimized", LuaValue.valueOf(d.isMinimized(w)))
                set("x11", LuaValue.valueOf(w is dev.caulis.xwayland.XWindow))
                set("urgent", LuaValue.valueOf(w.urgent))
                set("x", w.x)
                set("y", w.y)
                set("width", w.visibleRect.width)
                set("height", w.visibleRect.height)
            }
        }

        fun state(): LuaTable {
            val d = caulis.desktop
            val windows = LuaTable()
            d.allWindows.filter { it.mapped }.forEachIndexed { i, w -> windows.set(i + 1, window(w)) }
            val monitors = LuaTable()
            d.monitors.forEachIndexed { i, m ->
                monitors.set(
                    i + 1,
                    LuaTable().apply {
                        set("name", m.name)
                        set("workspace", m.workspace.number)
                        set("scale", LuaValue.valueOf(m.scale))
                        set("x", m.area.x)
                        set("y", m.area.y)
                        set("width", m.area.width)
                        set("height", m.area.height)
                        set("focused", LuaValue.valueOf(m === d.focusedMonitor))
                        set("primary", LuaValue.valueOf(m === d.primaryMonitor))
                    },
                )
            }
            return LuaTable().apply {
                set("windows", windows)
                set("monitors", monitors)
                set("focused", d.focusedWindow?.let { LuaValue.valueOf(caulis.idOf(it).toDouble()) } ?: LuaValue.NIL)
                set("workspace", d.activeWorkspace.number)
                set("locked", LuaValue.valueOf(d.locked))
            }
        }
    }

    private companion object {
        val EVENTS = listOf("start", "reload", "window-opened", "window-closed", "focus-changed", "workspace-changed", "notice", "lid")

        // How long Lua code may run: the whole file, and one key, event or timer.
        const val LOAD_LIMIT = 5_000_000_000L
        const val CALL_LIMIT = 500_000_000L
    }
}
