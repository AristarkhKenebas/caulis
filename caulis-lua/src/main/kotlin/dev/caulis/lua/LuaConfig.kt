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
 * local clock = caulis.every(1000, function() ... end)  -- every second; clock.cancel() stops it
 * local key = caulis.bind("Super+G", "exec gimp")      -- key.setEnabled(false), key.unbind()
 * caulis.layout("halves", function(windows, area, focused)
 *   -- one rectangle per window: { x =, y =, width =, height = }
 * end)
 * ```
 *
 * A mistake is reported with its line and the rest still applies. Lua code that runs too
 * long (a loop that never ends, also inside pcall or a coroutine) is stopped, so the desktop
 * does not freeze. It is your own code, as trusted as config.kts: os and io are there
 * (os.execute starts programs), only the debug hooks and Java itself (luajava) are not. Code typed at
 * `--repl` (or sent with `--lua`) runs in this same state, with its variables.
 */
class LuaConfig : ConfigLanguage {

    // Lua code ran past its time: an Error, so Lua's pcall cannot catch it and go on.
    private class TooLong : Error("it runs too long (an endless loop?)", null, false, false)

    override val fileName = "config.lua"

    override fun apply(caulis: Caulis, file: Path): List<String> = Script(caulis, file).load()

    override fun stubs(): String = LuaStubs.text()

    // One run of config.lua: its Lua state, and what it reports.
    private class Script(val caulis: Caulis, val file: Path) {
        val log = Logger("lua")
        val name = file.fileName.toString()
        val problems = ArrayList<String>()
        val globals: Globals = JsePlatform.standardGlobals()

        // When the Lua code running now must stop (it runs too long). (Coroutines run on
        // threads of their own, and read it there.)
        @Volatile
        var deadline = Long.MAX_VALUE

        // debug.sethook and debug.getinfo, kept for ourselves: the code itself must not take
        // its time limit away.
        val sethook: LuaValue
        val getinfo: LuaValue

        // Every so many instructions: has it run too long? Thrown as an Error, which pcall
        // does not catch.
        val hook = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                if (System.nanoTime() > deadline) throw TooLong()
                return NONE
            }
        }

        init {
            globals.load(org.luaj.vm2.lib.DebugLib())
            val debug = globals.get("debug")
            sethook = debug.get("sethook")
            getinfo = debug.get("getinfo")
            // What is left of debug: a traceback for your own messages.
            globals.set("debug", LuaTable().also { it.set("traceback", debug.get("traceback")) })
            // Java from Lua could reach into the compositor's insides.
            globals.set("luajava", LuaValue.NIL)
            // A coroutine runs on a thread of its own: it gets the time limit too.
            val coroutine = globals.get("coroutine")
            val create = coroutine.get("create")
            coroutine.set("create", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val thread = create.call(args.arg1())
                    sethook.invoke(LuaValue.varargsOf(arrayOf(thread, hook, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_EVERY))))
                    return thread
                }
            })
            coroutine.set(
                "wrap",
                globals.load(
                    """
                    local create, resume, pack, unpack = coroutine.create, coroutine.resume, table.pack, table.unpack
                    return function(f)
                      local co = create(f)
                      return function(...)
                        local r = pack(resume(co, ...))
                        if not r[1] then error(r[2], 0) end
                        return unpack(r, 2, r.n)
                      end
                    end
                    """.trimIndent(),
                    "=coroutine.wrap",
                ).call(),
            )
        }

        // Runs [body] with [limit] nanoseconds at most (or less, when called from Lua code
        // that has less time left).
        inline fun <T> limited(limit: Long, body: () -> T): T {
            val outer = deadline
            deadline = minOf(outer, System.nanoTime() + limit)
            try {
                return body()
            } finally {
                deadline = outer
            }
        }

        // What went wrong in Lua code, in words (also errors Java throws for it: a stack
        // overflow, a string too big).
        fun problem(e: Throwable): String {
            if (e is OutOfMemoryError) throw e
            return when (e) {
                is TooLong -> "it runs too long (an endless loop?)"
                is LuaError -> if (e.cause is TooLong) "it runs too long (an endless loop?)" else message(e)
                is StackOverflowError -> "it calls itself without end (the stack overflowed)"
                else -> "${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            }
        }

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
            sethook.call(hook, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_EVERY))
            try {
                limited(LOAD_LIMIT) { globals.load(code, "@$name").call() }
            } catch (e: Throwable) {
                problems += problem(e)
            }
            caulis.evaluator("lua", ::eval)
            return problems
        }

        // Code typed at --repl: run in this state; what it returns, as text.
        fun eval(code: String): Result<String> = try {
            val results = limited(CALL_LIMIT) { globals.load(code, "=repl").invoke() }
            Result.success((1..results.narg()).joinToString("\t") { show(results.arg(it)) })
        } catch (e: Throwable) {
            Result.failure(IllegalArgumentException(problem(e)))
        }

        // A value as Lua's prompt would show it (tables one level deep).
        fun show(value: LuaValue, depth: Int = 0): String = when {
            value.isstring() && !value.isnumber() -> if (depth == 0) value.tojstring() else "\"${value.tojstring()}\""
            value.istable() && depth < 2 -> {
                val table = value.checktable()
                val keys = table.keys()
                val length = table.length()
                if (length > 0 && keys.size == length) {
                    (1..length).joinToString(", ", "{ ", " }") { show(table.get(it), depth + 1) }
                } else {
                    keys.sortedBy { it.tojstring() }.joinToString(", ", "{ ", " }") { "${it.tojstring()} = ${show(table.get(it), depth + 1)}" }.let { if (keys.isEmpty()) "{}" else it }
                }
            }
            else -> value.tojstring()
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
            // A handle: functions for what was just made (to stop or change it later).
            fun handle(vararg functions: Pair<String, (Varargs) -> Unit>): LuaTable = LuaTable().also { table ->
                for ((name, body) in functions) table.set(name, object : VarArgFunction() {
                    override fun invoke(args: Varargs): Varargs {
                        body(args)
                        return LuaValue.NONE
                    }
                })
            }
            fun closing(what: AutoCloseable) = handle("remove" to { _ -> what.close() }, "cancel" to { _ -> what.close() })
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
                val binding = try {
                    if (mode != null) {
                        var made: Caulis.Binding? = null
                        caulis.mode(mode) { made = bind(combination, options, run) }
                        made!!
                    } else {
                        caulis.bind(combination, options, run)
                    }
                } catch (e: IllegalArgumentException) {
                    throw LuaError(e.message ?: "not a key combination or mode")
                }
                handle("unbind" to { _ -> binding.unbind() }, "setEnabled" to { a -> binding.enabled = a.arg1().toboolean() })
            }
            function("mode") { args ->
                running("mode") { enterMode(args.optjstring(1, null)?.takeIf { it != "reset" }) }
                LuaValue.NONE
            }
            function("on") { args ->
                val event = args.checkjstring(1)
                val fn = args.checkfunction(2)
                val listening: AutoCloseable = when (event) {
                    "start" -> {
                        // (Taken away before it ran, it does not run.)
                        var removed = false
                        caulis.onStart { if (!removed) call("on start", fn) }
                        AutoCloseable { removed = true }
                    }
                    "reload" -> caulis.onReload { call("on reload", fn) }
                    "unload" -> caulis.onUnload { call("on unload", fn) }
                    "window-opened" -> caulis.onWindowOpened { w -> call("on window-opened", fn, window(w)) }
                    "window-closed" -> caulis.onWindowClosed { w -> call("on window-closed", fn, window(w)) }
                    "window-changed" -> caulis.onWindowChanged { w -> call("on window-changed", fn, window(w)) }
                    "focus-changed" -> caulis.onFocusChanged { w -> call("on focus-changed", fn, w?.let(::window) ?: LuaValue.NIL) }
                    "workspace-changed" -> caulis.onWorkspaceChanged { n -> call("on workspace-changed", fn, LuaValue.valueOf(n)) }
                    "monitor-added" -> caulis.onMonitorAdded { name -> call("on monitor-added", fn, LuaValue.valueOf(name)) }
                    "monitor-removed" -> caulis.onMonitorRemoved { name -> call("on monitor-removed", fn, LuaValue.valueOf(name)) }
                    "lid" -> caulis.onLid { closed -> call("on lid", fn, LuaValue.valueOf(closed)) }
                    "notice" -> caulis.onNotice { n: Notice -> call("on notice", fn, LuaValue.valueOf(n.text), LuaValue.valueOf(n.level.name.lowercase())) }
                    else -> throw LuaError("there is no event \"$event\" (there are: ${EVENTS.joinToString()})")
                }
                closing(listening)
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
            function("windows") { running("windows") { list(desktop.allWindows.filter { it.mapped }.map(::window)) } }
            function("window") { args -> running("window") { windowWithId(args.checklong(1))?.let(::window) ?: LuaValue.NIL } }
            function("focusedWindow") { running("focusedWindow") { desktop.focusedWindow?.let(::window) ?: LuaValue.NIL } }
            function("monitors") { running("monitors") { state().get("monitors") } }
            function("workspace") { running("workspace") { LuaValue.valueOf(desktop.activeWorkspace.number) } }
            function("after") { args ->
                val millis = args.checklong(1)
                val fn = args.checkfunction(2)
                // (Also in the file itself: it then counts from when the compositor runs.)
                closing(caulis.after(millis) { call("a timer", fn) })
            }
            function("every") { args ->
                val millis = args.checklong(1)
                val fn = args.checkfunction(2)
                if (millis < 10) throw LuaError("every: at least 10 milliseconds apart, not $millis")
                closing(caulis.every(millis) { call("a repeating timer", fn) })
            }
            function("layout") { args ->
                val layoutName = args.checkjstring(1)
                val fn = args.checkfunction(2)
                val layout = LuaLayout(layoutName, fn)
                caulis.layout = layout
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
            for (problem in Settings.applyEach(caulis, read.values)) problems += "$name:$line: $problem"
        }

        fun toJson(value: LuaValue, line: Int): JsonValue = when {
            value.isboolean() -> JsonValue.Bool(value.toboolean(), line, 0)
            value.isnumber() && value.type() == LuaValue.TNUMBER -> JsonValue.Num(value.todouble(), line, 0)
            value.isstring() -> JsonValue.Str(value.tojstring(), line, 0)
            value.istable() -> {
                val table = value.checktable()
                val length = table.length()
                val keys = table.keys()
                // An empty table is an empty list ({}: "nothing to start"); a section with
                // nothing in it means the same then.
                if (keys.isEmpty()) {
                    JsonValue.Arr(emptyList(), line, 0)
                } else if (length > 0 && keys.size == length) {
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
            val info = getinfo
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
            try {
                limited(CALL_LIMIT) { fn.invoke(LuaValue.varargsOf(args)) }
            } catch (e: Throwable) {
                val message = "$what: ${problem(e)}"
                log.warn(message)
                caulis.runningOrNull()?.notify(message, NoticeLevel.WARNING)
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

        fun list(items: List<LuaValue>): LuaTable = LuaTable().also { t -> items.forEachIndexed { i, v -> t.set(i + 1, v) } }

        // A layout whose arrangement Lua says: fn(windows, area, focused) returns a rectangle
        // per window. A mistake (or a missing rectangle) puts the windows side by side instead.
        inner class LuaLayout(override val name: String, private val fn: LuaValue) : dev.caulis.tiling.Layout {
            private val fallback = dev.caulis.tiling.Columns()
            private var said: String? = null

            override fun arrange(windows: List<dev.caulis.tiling.LayoutWindow>, area: dev.caulis.geometry.Rect, focused: Int): List<dev.caulis.geometry.Rect> {
                if (windows.isEmpty()) return emptyList()
                val list = list(windows.map { w ->
                    LuaTable().apply {
                        set("appId", w.appId)
                        set("title", w.title)
                        (w as? Window)?.let { real -> caulis.runningOrNull()?.let { set("id", LuaValue.valueOf(it.idOf(real).toDouble())) } }
                    }
                })
                val box = LuaTable().apply {
                    set("x", area.x)
                    set("y", area.y)
                    set("width", area.width)
                    set("height", area.height)
                }
                val problem = try {
                    val result = limited(CALL_LIMIT) { fn.call(list, box, LuaValue.valueOf(focused + 1)) }
                    if (!result.istable()) "it returned ${result.typename()}, not a table of rectangles" else {
                        val rects = (1..windows.size).map { i ->
                            val r = result.get(i)
                            if (!r.istable()) return@map null
                            dev.caulis.geometry.Rect(r.get("x").toint(), r.get("y").toint(), r.get("width").toint().coerceAtLeast(1), r.get("height").toint().coerceAtLeast(1))
                        }
                        if (rects.all { it != null }) {
                            said = null
                            return rects.map { it!! }
                        }
                        "it gave ${rects.count { it != null }} rectangles for ${windows.size} windows"
                    }
                } catch (e: Throwable) {
                    problem(e)
                }
                if (problem != said) {
                    said = problem
                    log.warn("The layout \"$name\": $problem (the windows are side by side meanwhile)")
                }
                return fallback.arrange(windows, area, focused)
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

    internal companion object {
        val EVENTS = listOf(
            "start", "reload", "unload", "window-opened", "window-closed", "window-changed", "focus-changed",
            "workspace-changed", "monitor-added", "monitor-removed", "notice", "lid",
        )

        // How long Lua code may run: the whole file, and one key, event or timer.
        @Volatile
        var LOAD_LIMIT = 5_000_000_000L // (tests shorten it)
        const val CALL_LIMIT = 500_000_000L

        // How many Lua instructions between two looks at the clock.
        const val HOOK_EVERY = 10_000
    }
}
