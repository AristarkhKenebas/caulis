package dev.caulis.lua

import dev.caulis.wm.config.ActionType
import dev.caulis.wm.config.BindType
import dev.caulis.wm.config.ChoiceType
import dev.caulis.wm.config.ColorType
import dev.caulis.wm.config.CombinationType
import dev.caulis.wm.config.DecimalType
import dev.caulis.wm.config.EitherType
import dev.caulis.wm.config.FileType
import dev.caulis.wm.config.FlagType
import dev.caulis.wm.config.IntegerType
import dev.caulis.wm.config.ListType
import dev.caulis.wm.config.MapType
import dev.caulis.wm.config.OffOrType
import dev.caulis.wm.config.SectionType
import dev.caulis.wm.config.SettingType
import dev.caulis.wm.config.Settings
import dev.caulis.wm.config.TextType

/**
 * LuaLS annotations of the `caulis` table (`--print-lua-stubs`): an editor with the Lua
 * language server then completes every setting and function and says what is wrong. The
 * settings come from the same description config.json is checked with.
 */
internal object LuaStubs {

    fun text(): String = buildString {
        val classes = LinkedHashMap<String, String>()
        appendLine("---@meta caulis")
        appendLine("-- The caulis table of config.lua, for the Lua language server (LuaLS). Generated: my-compositor --print-lua-stubs")
        appendLine()
        settingsClass("caulis.Settings", Settings.root, classes)
        classes.values.forEach { append(it).appendLine() }
        append(API)
    }

    // A ---@class for [section] called [name] (and the classes inside it), into [classes].
    private fun settingsClass(name: String, section: SectionType, classes: MutableMap<String, String>) {
        if (name in classes) return
        classes[name] = "" // (reserved: inner ones come after it)
        classes[name] = buildString {
            appendLine("---@class $name")
            for (field in section.fields) {
                val type = luaType(field.type, "$name.${field.name}", classes)
                appendLine("---@field ${field.name}? $type ${field.description.replace("\n", " ")}")
            }
        }
    }

    private fun luaType(type: SettingType, path: String, classes: MutableMap<String, String>): String = when (type) {
        is IntegerType -> "integer"
        is DecimalType -> "number"
        FlagType -> "boolean"
        is TextType, is FileType, ColorType, ActionType, is CombinationType -> "string"
        BindType -> "string|{ action: string, repeat?: boolean, release?: boolean, locked?: boolean, description?: string }"
        is ChoiceType -> type.values.joinToString("|") { "\"$it\"" }
        is ListType -> "(${luaType(type.item, path, classes)})[]"
        is MapType -> "table<string, ${luaType(type.value, path, classes)}>"
        is EitherType -> luaType(type.first, path, classes) + "|" + luaType(type.second, "$path.more", classes)
        is OffOrType -> "false|" + luaType(type.value, path, classes)
        is SectionType -> path.also { settingsClass(it, type, classes) }
        else -> "any"
    }

    // The functions, written by hand (they are code, not settings).
    private val API = """
        ---@class caulis.Window
        ---@field id integer the same while it is open
        ---@field appId string
        ---@field title string
        ---@field pid integer
        ---@field workspace integer 0: none (a scratchpad)
        ---@field monitor string its monitor's connector
        ---@field focused boolean
        ---@field floating boolean
        ---@field maximized boolean
        ---@field fullscreen boolean
        ---@field minimized boolean
        ---@field x11 boolean
        ---@field urgent boolean
        ---@field x integer
        ---@field y integer
        ---@field width integer
        ---@field height integer

        ---@class caulis.Monitor
        ---@field name string
        ---@field workspace integer
        ---@field scale number
        ---@field x integer
        ---@field y integer
        ---@field width integer
        ---@field height integer
        ---@field focused boolean
        ---@field primary boolean

        ---@class caulis.State
        ---@field windows caulis.Window[]
        ---@field monitors caulis.Monitor[]
        ---@field focused integer? the focused window's id
        ---@field workspace integer
        ---@field locked boolean

        ---@class caulis.Key
        ---@field unbind fun() takes the key away for good
        ---@field setEnabled fun(on: boolean) off: the key goes to programs until it is on again

        ---@class caulis.Handle
        ---@field cancel fun() stops it (a timer, a listener)
        ---@field remove fun() the same

        ---@class caulis.LayoutWindow
        ---@field appId string
        ---@field title string
        ---@field id integer?

        ---@class caulis.Rect
        ---@field x integer
        ---@field y integer
        ---@field width integer
        ---@field height integer

        ---@class caulis.BindOptions
        ---@field repeating? boolean repeats while held
        ---@field release? boolean on letting go
        ---@field locked? boolean also while the screen is locked
        ---@field description? string
        ---@field mode? string a key mode's own key

        ---@alias caulis.Event "start"|"reload"|"unload"|"window-opened"|"window-closed"|"window-changed"|"focus-changed"|"workspace-changed"|"monitor-added"|"monitor-removed"|"notice"|"lid"

        ---@class caulis
        caulis = {}

        ---What config.json can say.
        ---@param settings caulis.Settings
        function caulis.settings(settings) end

        ---A rule for some windows.
        ---@param rule caulis.Settings.rules
        function caulis.rule(rule) end

        ---A key: an action as text ("exec firefox") or a function.
        ---@param combination string
        ---@param action string|fun()
        ---@param options? caulis.BindOptions
        ---@return caulis.Key
        function caulis.bind(combination, action, options) end

        ---Turns a key mode on (nil or "reset": the usual keys).
        ---@param name? string
        function caulis.mode(name) end

        ---Runs fn when the event happens.
        ---@param event caulis.Event
        ---@param fn function
        ---@return caulis.Handle
        function caulis.on(event, fn) end

        ---Runs an action ("workspace 2"), on the window with that id or the focused one.
        ---@param action string
        ---@param window? integer
        function caulis.run(action, window) end

        ---@return caulis.State
        function caulis.state() end

        ---@return caulis.Window[]
        function caulis.windows() end

        ---@param id integer
        ---@return caulis.Window?
        function caulis.window(id) end

        ---@return caulis.Window?
        function caulis.focusedWindow() end

        ---@return caulis.Monitor[]
        function caulis.monitors() end

        ---@return integer
        function caulis.workspace() end

        ---Runs fn once, millis from now.
        ---@param millis integer
        ---@param fn fun()
        ---@return caulis.Handle
        function caulis.after(millis, fn) end

        ---Runs fn every millis (at least 10) until cancelled or reloaded.
        ---@param millis integer
        ---@param fn fun()
        ---@return caulis.Handle
        function caulis.every(millis, fn) end

        ---A layout of your own (it becomes the layout): one rectangle per window.
        ---@param name string
        ---@param fn fun(windows: caulis.LayoutWindow[], area: caulis.Rect, focused: integer): caulis.Rect[]
        function caulis.layout(name, fn) end

        ---@param text string
        ---@param level? "info"|"warning"|"error"
        function caulis.notify(text, level) end

        ---@param text string
        function caulis.log(text) end

        ---A file next to the configuration, as a full path.
        ---@param name string
        ---@return string
        function caulis.file(name) end
    """.trimIndent() + "\n"
}
