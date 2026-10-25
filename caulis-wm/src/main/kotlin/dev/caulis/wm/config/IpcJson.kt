package dev.caulis.wm.config

import dev.caulis.ipc.v1.Event
import dev.caulis.ipc.v1.Monitor
import dev.caulis.ipc.v1.Rect
import dev.caulis.ipc.v1.State
import dev.caulis.ipc.v1.Window

/** IPC messages as JSON, for the command line (`--state`, `--events`) and so for scripts. */
internal object IpcJson {

    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields), 0, 0)
    private fun text(value: String) = JsonValue.Str(value, 0, 0)
    private fun number(value: Number) = JsonValue.Num(value.toDouble(), 0, 0)
    private fun flag(value: Boolean) = JsonValue.Bool(value, 0, 0)
    private fun list(items: List<JsonValue>) = JsonValue.Arr(items, 0, 0)

    private fun rect(r: Rect) = obj("x" to number(r.x), "y" to number(r.y), "width" to number(r.width), "height" to number(r.height))

    fun window(w: Window): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to number(w.id),
            "appId" to text(w.appId),
            "title" to text(w.title),
            "pid" to number(w.pid),
            "workspace" to number(w.workspace),
            "monitor" to text(w.monitor),
            "focused" to flag(w.focused),
            "floating" to flag(w.floating),
            "maximized" to flag(w.maximized),
            "fullscreen" to flag(w.fullscreen),
            "minimized" to flag(w.minimized),
            "x11" to flag(w.x11),
            "urgent" to flag(w.urgent),
        )
        if (w.hasRect()) fields["rect"] = rect(w.rect)
        return JsonValue.Obj(fields, 0, 0)
    }

    fun monitor(m: Monitor) = obj(
        "name" to text(m.name),
        "area" to rect(m.area),
        "usableArea" to rect(m.usableArea),
        "scale" to number(m.scale),
        "pixelWidth" to number(m.pixelWidth),
        "pixelHeight" to number(m.pixelHeight),
        "workspace" to number(m.workspace),
        "focused" to flag(m.focused),
        "primary" to flag(m.primary),
    )

    fun state(s: State) = obj(
        "windows" to list(s.windowsList.map(::window)),
        "workspaces" to list(
            s.workspacesList.map {
                obj("number" to number(it.number), "monitor" to text(it.monitor), "active" to flag(it.active), "windows" to number(it.windows), "layout" to text(it.layout), "name" to text(it.name))
            },
        ),
        "monitors" to list(s.monitorsList.map(::monitor)),
        "focusedWindow" to number(s.focusedWindow),
        "focusedMonitor" to text(s.focusedMonitor),
        "scale" to number(s.scale),
        "locked" to flag(s.locked),
        "primaryMonitor" to text(s.primaryMonitor),
        "keyMode" to text(s.keyMode),
        "colorScheme" to text(s.colorScheme),
    )

    /** An event: `{"event": "window-opened", ...}`. */
    fun event(e: Event): JsonValue.Obj = when (e.whatCase) {
        Event.WhatCase.WINDOW_OPENED -> obj("event" to text("window-opened"), "window" to window(e.windowOpened))
        Event.WhatCase.WINDOW_CLOSED -> obj("event" to text("window-closed"), "id" to number(e.windowClosed))
        Event.WhatCase.WINDOW_CHANGED -> obj("event" to text("window-changed"), "window" to window(e.windowChanged))
        Event.WhatCase.FOCUS_CHANGED -> obj("event" to text("focus-changed"), "id" to number(e.focusChanged))
        Event.WhatCase.WORKSPACE_SHOWN -> obj("event" to text("workspace-shown"), "number" to number(e.workspaceShown.number), "monitor" to text(e.workspaceShown.monitor))
        Event.WhatCase.MONITORS_CHANGED -> obj("event" to text("monitors-changed"), "monitors" to list(e.monitorsChanged.monitorsList.map(::monitor)))
        Event.WhatCase.NOTICE -> obj(
            "event" to text("notice"),
            "level" to text(e.notice.level.name.removePrefix("LEVEL_").lowercase()),
            "title" to text(e.notice.title),
            "text" to text(e.notice.text),
        )
        Event.WhatCase.CONFIG_RELOADED -> obj("event" to text("config-reloaded"), "problems" to list(e.configReloaded.problemsList.map(::text)))
        Event.WhatCase.LOCK_CHANGED -> obj("event" to text("lock-changed"), "locked" to flag(e.lockChanged))
        Event.WhatCase.KEY_MODE_CHANGED -> obj("event" to text("key-mode-changed"), "mode" to text(e.keyModeChanged))
        Event.WhatCase.COLOR_SCHEME_CHANGED -> obj("event" to text("color-scheme-changed"), "colorScheme" to text(e.colorSchemeChanged))
        else -> obj("event" to text("unknown"))
    }
}
