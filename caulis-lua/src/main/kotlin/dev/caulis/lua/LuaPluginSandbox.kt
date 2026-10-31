package dev.caulis.lua

import dev.caulis.core.Window
import dev.caulis.geometry.Rect
import dev.caulis.plugins.PluginContext
import dev.caulis.tiling.Layout
import dev.caulis.tiling.LayoutWindow
import dev.caulis.wm.plugins.PluginApi
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.LoadState
import java.nio.file.Files
import java.nio.file.Path

/**
 * A Lua plugin's world. Only what cannot reach outside: Lua itself (no files, os only for the time,
 * no coroutines: each would be a thread to leak), `require` of Lua files in its own folder, and the
 * table `plugin`. Each call has a time limit, also inside pcall and string patterns. Raw keys and the
 * pointer are not offered: a sandboxed plugin cannot listen to what you type. Starting programs
 * needs the user's word: `plugins { allow("id", "exec") }`.
 */
internal class LuaPluginSandbox(
    private val context: PluginContext<PluginApi>,
    private val folder: Path,
    private val extras: LuaPluginExtras?,
) {
    private class TooLong : Error("it runs too long (an endless loop?)", null, false, false)

    private val api = context.api
    private val log = context.log
    private val id = context.manifest.id
    private val allowed: Set<String> get() = api.caulis.plugins.allow[id].orEmpty()
    private val globals = Globals()

    @Volatile
    private var deadline = Long.MAX_VALUE

    private fun limit(): Long = api.caulis.scriptCallLimitMillis * 1_000_000L

    private val hook = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            checkTime()
            return NONE
        }
    }

    private fun checkTime() {
        if (System.nanoTime() > deadline) throw TooLong()
    }

    init {
        globals.load(JseBaseLib())
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(JseMathLib())
        globals.load(DebugLib())
        LoadState.install(globals)
        LuaC.install(globals)
        val debug = globals.get("debug")
        val sethook = debug.get("sethook")
        // Not for it: its own limit, and the way into anything's insides.
        globals.set("debug", LuaValue.NIL)
        for (name in listOf("dofile", "loadfile", "collectgarbage", "print")) globals.set(name, LuaValue.NIL)
        // Binary chunks can break the virtual machine: text only.
        val load = globals.get("load")
        globals.set("load", function { args ->
            val mode = LuaValue.valueOf("t")
            load.invoke(LuaValue.varargsOf(arrayOf(args.arg1(), args.arg(2), mode, args.arg(4))))
        })
        val loaded = LuaTable()
        globals.set("package", LuaValue.NIL)
        globals.set("require", function { args -> require(args.checkjstring(1), loaded) })
        globals.set("print", function { args ->
            log.info((1..args.narg()).joinToString(" ") { args.arg(it).tojstring() })
            LuaValue.NONE
        })
        globals.set("os", LuaTable().also { os ->
            os.set("time", function { LuaValue.valueOf((System.currentTimeMillis() / 1000).toDouble()) })
            os.set("clock", function { LuaValue.valueOf(System.nanoTime() / 1e9) })
            os.set("date", function { args ->
                LuaValue.valueOf(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern(args.optjstring(1, "yyyy-MM-dd HH:mm"))))
            })
        })
        // String patterns run in Java, where the hook never comes: a copy that looks at the clock.
        LuaPatterns { checkTime() }.install(globals.get("string").checktable())
        // string.rep can ask for gigabytes in one instruction.
        val rep = globals.get("string").get("rep")
        globals.get("string").set("rep", function { args ->
            val size = args.arg1().checkjstring().length.toLong() * args.arg(2).checklong()
            if (size > MAX_STRING) throw LuaError("string.rep: too long")
            rep.invoke(args)
        })
        globals.set("plugin", table())
        sethook.call(hook, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_EVERY))
    }

    /** Runs the plugin's file: a mistake makes its start fail. */
    fun run(code: String, name: String) {
        limited(LOAD_LIMIT) { globals.load(code, "@$name", globals).call() }
    }

    private inline fun <T> limited(nanos: Long, body: () -> T): T {
        val outer = deadline
        deadline = minOf(outer, System.nanoTime() + nanos)
        try {
            return body()
        } catch (e: LuaError) {
            if (e.cause is TooLong) throw TooLong()
            throw RuntimeException(message(e))
        } catch (e: StackOverflowError) {
            throw RuntimeException("it calls itself without end")
        } finally {
            deadline = outer
        }
    }

    private fun message(e: LuaError) = (e.message ?: "an error").lineSequence().first().removePrefix("@")

    private fun require(name: String, loaded: LuaTable): LuaValue {
        loaded.get(name).takeIf { !it.isnil() }?.let { return it }
        if (!MODULE.matches(name)) throw LuaError("require: \"$name\" is not a file of this plugin")
        val file = folder.resolve(name.replace('.', '/') + ".lua").normalize()
        if (!file.startsWith(folder) || !Files.isRegularFile(file)) throw LuaError("require: no $name.lua next to the plugin")
        val value = globals.load(Files.readString(file), "@${file.fileName}", globals).call()
        val result = if (value.isnil()) LuaValue.TRUE else value
        loaded.set(name, result)
        return result
    }

    private fun function(body: (Varargs) -> Varargs) = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = body(args)
    }

    // A Lua function to call back: inside the plugin's time limit. What it throws reaches the guard.
    private fun callback(fn: LuaValue): LuaCallback = LuaCallback { arguments ->
        val values = arguments.map(::toLua).toTypedArray()
        toKotlin(limited(limit()) { fn.invoke(LuaValue.varargsOf(values)).arg1() })
    }

    private fun toKotlin(value: LuaValue): Any? = when {
        value.isnil() -> null
        value.isboolean() -> value.toboolean()
        value.isnumber() -> value.todouble()
        value.isstring() -> value.tojstring()
        value.isfunction() -> callback(value)
        value.istable() -> {
            val length = value.length()
            if (length > 0) (1..length).map { toKotlin(value.get(it)) } else {
                val map = LinkedHashMap<String, Any?>()
                var key = LuaValue.NIL
                while (true) {
                    val next = value.next(key)
                    key = next.arg1()
                    if (key.isnil()) break
                    map[key.tojstring()] = toKotlin(next.arg(2))
                }
                map
            }
        }
        else -> null
    }

    private fun toLua(value: Any?): LuaValue = when (value) {
        null -> LuaValue.NIL
        is LuaValue -> value
        is Boolean -> LuaValue.valueOf(value)
        is Number -> LuaValue.valueOf(value.toDouble())
        is String -> LuaValue.valueOf(value)
        is Map<*, *> -> LuaTable().also { t -> value.forEach { (k, v) -> t.set(k.toString(), toLua(v)) } }
        is List<*> -> LuaTable().also { t -> value.forEachIndexed { i, v -> t.set(i + 1, toLua(v)) } }
        is AutoCloseable -> handle(value)
        else -> LuaValue.valueOf(value.toString())
    }

    private fun handle(closing: AutoCloseable): LuaTable = LuaTable().also { t ->
        val remove = function { closing.close(); LuaValue.NONE }
        t.set("remove", remove)
        t.set("cancel", remove)
    }

    private fun windowTable(w: Window): LuaTable = LuaTable().apply {
        set("id", LuaValue.valueOf(api.caulis.idOf(w).toDouble()))
        set("appId", w.appId)
        set("title", w.title)
    }

    private fun table(): LuaTable {
        val plugin = LuaTable()
        fun put(name: String, body: (Varargs) -> Varargs) = plugin.set(name, function(body))
        plugin.set("id", id)
        plugin.set("version", context.manifest.version.toString())
        plugin.set("settings", toLua(context.settings.values))

        put("log") { args -> log.info(args.arg1().tojstring()); LuaValue.NONE }
        put("notify") { args -> api.notify(args.checkjstring(1)); LuaValue.NONE }

        put("on") { args -> handle(on(args.checkjstring(1), args.checkfunction(2))) }
        put("bind") { args ->
            val target = args.arg(2)
            handle(
                api.bind(args.checkjstring(1), dev.caulis.wm.BindOptions(description = args.optjstring(3, "") ?: "")) {
                    if (target.isfunction()) callback(target)(emptyList()) else run(target.checkjstring(), null)
                },
            )
        }
        put("action") { args ->
            val fn = args.checkfunction(2)
            handle(api.action(args.checkjstring(1), description = args.optjstring(3, "") ?: "") { text, window ->
                callback(fn)(listOf(text, window?.let(::windowTable)))
            })
        }
        put("command") { args ->
            val fn = args.checkfunction(2)
            handle(api.command(args.checkjstring(1)) { text -> callback(fn)(listOf(text))?.toString() })
        }
        put("every") { args ->
            val fn = args.checkfunction(2)
            handle(api.every(args.checklong(1).coerceAtLeast(MIN_TIMER)) { callback(fn)(emptyList()) })
        }
        put("after") { args ->
            val fn = args.checkfunction(2)
            handle(api.after(args.checklong(1)) { callback(fn)(emptyList()) })
        }
        put("run") { args -> run(args.checkjstring(1), args.arg(2).takeIf { it.isnumber() }?.tolong()); LuaValue.NONE }
        put("launch") { args -> launch(args.checkjstring(1)); LuaValue.NONE }
        put("windows") { _ -> toLua(api.desktop.allWindows.map(::windowTable)) }
        put("focused") { _ -> api.desktop.focusedWindow?.let(::windowTable) ?: LuaValue.NIL }
        put("workspace") { _ -> LuaValue.valueOf(api.desktop.activeWorkspace.number) }
        put("layout") { args ->
            val name = args.checkjstring(1)
            val fn = args.checkfunction(2)
            handle(api.layout(name) { LuaPluginLayout(name, callback(fn)) })
        }
        put("get") { args -> kept().getProperty(args.checkjstring(1))?.let { LuaValue.valueOf(it) } ?: args.arg(2) }
        put("set") { args ->
            val kept = kept()
            kept.setProperty(args.checkjstring(1), args.arg(2).checkjstring())
            Files.newBufferedWriter(keptFile()).use { kept.store(it, null) }
            LuaValue.NONE
        }
        extras?.add(
            object : LuaPluginTable {
                override fun function(name: String, body: (List<Any?>) -> Any?) = put(name) { args ->
                    toLua(body((1..args.narg()).map { toKotlin(args.arg(it)) }))
                }

                override fun handle(closing: AutoCloseable): Any = this@LuaPluginSandbox.handle(closing)
            },
            api,
        )
        return plugin
    }

    private fun keptFile() = context.dataDir.resolve("kept.properties")

    private fun kept() = java.util.Properties().also { p ->
        if (Files.isRegularFile(keptFile())) Files.newBufferedReader(keptFile()).use(p::load)
    }

    private fun on(event: String, fn: LuaValue): AutoCloseable {
        val call = callback(fn)
        return when (event) {
            "window-opened" -> api.onWindowOpened { w: Window -> call(listOf(windowTable(w))) }
            "window-closed" -> api.onWindowClosed { w: Window -> call(listOf(windowTable(w))) }
            "window-changed" -> api.onWindowChanged { w: Window -> call(listOf(windowTable(w))) }
            "focus-changed" -> api.onFocusChanged { w: Window? -> call(listOf(w?.let(::windowTable))) }
            "workspace-changed" -> api.onWorkspaceChanged { n: Int -> call(listOf(n)) }
            "monitor-added" -> api.onMonitorAdded { m -> call(listOf(m)) }
            "monitor-removed" -> api.onMonitorRemoved { m -> call(listOf(m)) }
            "reload" -> api.onReload { call(emptyList()) }
            "sleep" -> api.onSleep { call(emptyList()) }
            "wake" -> api.onWake { call(emptyList()) }
            "lock-changed" -> api.onLockChanged { l -> call(listOf(l)) }
            else -> throw LuaError("plugin.on: there is no event \"$event\"")
        }
    }

    private fun run(action: String, window: Long?) {
        val word = action.trim().substringBefore(' ')
        // What leaves the sandbox: starting programs, and what could turn its own limits off.
        if (word in NEEDS_EXEC && "exec" !in allowed) throw LuaError("plugin.run(\"$word\"): needs your word: plugins { allow(\"$id\", \"exec\") }")
        if (word in NOT_FOR_LUA) throw LuaError("plugin.run(\"$word\"): not for a Lua plugin")
        api.run(action, window?.let { api.caulis.windowWithId(it) })
    }

    private fun launch(command: String) {
        if ("exec" !in allowed) throw LuaError("plugin.launch: needs your word: plugins { allow(\"$id\", \"exec\") }")
        api.caulis.launch(command)
    }

    private companion object {
        const val HOOK_EVERY = 1000
        const val LOAD_LIMIT = 2_000_000_000L
        const val MAX_STRING = 16L * 1024 * 1024
        const val MIN_TIMER = 100L
        val MODULE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")
        val NEEDS_EXEC = setOf("exec", "terminal")
        val NOT_FOR_LUA = setOf("plugin", "quit", "lock", "dump-state")
    }
}

// A layout whose arrangement Lua says; what is wrong goes to the plugin's guard.
private class LuaPluginLayout(override val name: String, private val fn: LuaCallback) : Layout {
    @Suppress("UNCHECKED_CAST")
    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int): List<Rect> {
        val box = mapOf("x" to area.x, "y" to area.y, "width" to area.width, "height" to area.height)
        val list = windows.map { mapOf("appId" to it.appId, "title" to it.title) }
        val result = fn(listOf(list, box, focused + 1)) as? List<Map<String, Any?>> ?: throw IllegalStateException("the layout $name did not return rectangles")
        return result.map { r ->
            Rect((r["x"] as Number).toInt(), (r["y"] as Number).toInt(), (r["width"] as Number).toInt().coerceAtLeast(1), (r["height"] as Number).toInt().coerceAtLeast(1))
        }
    }
}
