# Plugins

A plugin adds to a running compositor without touching its configuration or its code, and can be
replaced while it runs. There are two kinds:

| | Lua | Jar (Kotlin, Java) |
|---|---|---|
| To write | one file | a small Gradle project |
| Can reach | the table `plugin` | everything: every hook, every class |
| Safety | **sandboxed**: no files, no programs unless you allow them, no Java, no raw keys, a time limit per call | **not sandboxed** |
| Reload | on save | when the jar is replaced |

> **A jar plugin runs with your rights and is not sandboxed.** It can read your files, your keystrokes
> and anything on the screen, like any program you start. Installing one is running a program: install
> only what you trust. Containment below keeps a *broken* plugin from taking the desktop down; it does
> nothing against a *malicious* one.

## Installing

Put the file into `plugins` in your configuration's folder (`~/.config/<compositor>/plugins/`). It starts
by itself. Replace it and it reloads; delete it and it is unloaded. Files others can write to are refused
(that would run their code as you).

```
my-compositor --plugin list
my-compositor --plugin disable some-plugin      # stays off, also after a restart
my-compositor --plugin enable some-plugin
my-compositor --plugin reload [some-plugin]
```

The same is the action `plugin ...`, for keys: `"Super+F8": "plugin reload"`. A key can also name what
plugins offer, before they have started: `plugin run <action> [argument]`, `plugin layout <name>`,
`plugin cmd <name>`. Nothing starts or stops by itself while the screen is locked, and none of these
answer then.

## Settings

Kotlin: `plugins { ... }`; `config.json` and `config.lua`: `"plugins": { ... }`.

| Setting | Default | What |
|---|---|---|
| `enabled` | `true` | false: no plugin runs, no folder is followed (restart) |
| `directories` | `plugins` in the configuration's folder | where plugins are (restart) |
| `disabled` | none | ids that stay off |
| `watch` | `true` | follows the folders: hot reload (restart) |
| `settleMillis` | 300 | a changed file must be quiet this long before it is read |
| `failuresBeforeDisable` | 1 | how many failures before a plugin is disabled |
| `hangReportMillis` | 2000 | a call into a plugin longer than this is reported with its stack; 0: never (restart) |
| `hangInterruptMillis` | 5000 | a call this long has its thread interrupted; 0: never (restart) |
| `hangQuarantineMillis` | 10000 | a call this long leaves the plugin off at the next start; 0: never (restart) |
| `disableAfterHang` | `true` | a plugin that held up the desktop is disabled once its call returns |
| `leakCheckMillis` | 30000 | after an unload: when to look whether its classes left memory; 0: never |
| `maxLeakedGenerations` | 8 | with this many left in memory, hot reloads wait for a restart |
| `allowUnsafePermissions` | `false` | run also what others can write to |
| `stateFile` | `plugins.properties` in the state folder | where what `plugin disable` and a hang said is kept |
| `settings` | none | what each plugin is told, by id |
| `allow` | none | what a Lua plugin may do beyond its sandbox, by id: `exec` starts programs |

```kotlin
plugins {
    disabled = setOf("noisy")
    settings("hello", "greeting" to "Hi")
    allow("my-launcher", "exec")
}
```

The log: area `plugins` for the system, `plugin-<id>` for each plugin (`CAULIS_LOG=plugin-hello=3`).

## Tutorial: a Lua plugin

Save this as `~/.config/<compositor>/plugins/hello.lua`:

```lua
-- id: hello
-- version: 1.0
plugin.bind("Super+F9", function()
  plugin.notify("Hello: " .. #plugin.windows() .. " windows")
end)
```

Press Super+F9. Change the text and save: it runs anew. A mistake gets a notice, and the plugin stays
off until you save again.

The comments at the top are optional (the id is then the file's name). A plugin of several files is a
folder with `main.lua` and `caulis-plugin.properties`; `require("other")` finds Lua files in that folder.

What `plugin` offers:

| Call | What |
|---|---|
| `plugin.id`, `plugin.version`, `plugin.settings` | who it is, what the configuration tells it |
| `plugin.bind(keys, fn or action [, description])` | a key |
| `plugin.on(event, fn)` | `window-opened`, `window-closed`, `window-changed`, `focus-changed`, `workspace-changed`, `monitor-added`, `monitor-removed`, `lock-changed`, `reload`, `sleep`, `wake` |
| `plugin.action(name, fn [, description])` | an action others can name |
| `plugin.command(name, fn)` | a command for other programs |
| `plugin.every(ms, fn)`, `plugin.after(ms, fn)` | timers (at least 100 ms apart) |
| `plugin.layout(name, fn)` | a layout: `fn(windows, area, focused)` returns one rectangle per window |
| `plugin.run(action [, windowId])` | an action as text; `exec` and `terminal` need `allow`; `quit`, `lock`, `plugin` and `dump-state` are not for Lua |
| `plugin.launch(command)` | starts a program; needs `allow` |
| `plugin.windows()`, `plugin.focused()`, `plugin.workspace()` | what is on screen |
| `plugin.notify(text)`, `plugin.log(text)` | to the user, to the log |
| `plugin.get(key [, default])`, `plugin.set(key, text)` | a little text kept between sessions |

Each call that registers returns a handle: `handle.remove()`. Everything goes by itself when the plugin does.

The sandbox: there is no `io`, no `debug`, no `coroutine`, no `load` of binary chunks, `os` only has
`time`, `clock` and `date` (`os.date("HH:mm")`: Java's date patterns, not C's). One call may run as long as `scriptCallLimitMillis`: longer, also inside
`pcall` or a string pattern, and the plugin is disabled. Keys and the pointer as they come are not offered,
so a Lua plugin cannot listen to what you type.

## Tutorial: a jar plugin

Copy `templates/plugins/kotlin` (or `java`). `caulis-plugin.properties` says who it is:

```
id = hello-kotlin
version = 1.0
api = caulis:0.1
main = example.Hello
depends = other-plugin>=1.0, nice-to-have?
```

The class:

```kotlin
class Hello : CaulisPlugin {
    override fun enable(context: PluginContext<PluginApi>) {
        val api = context.api
        api.bind("Super+F9") { api.notify("Hello") }
        api.onWindowOpened { window -> context.log.info("opened ${window.appId}") }
    }
}
```

`gradle jar`, then copy the jar into the folder (`cp` to a temporary name and `mv`, or just copy: it waits
until the file stops changing). Each enabling gets a new instance. Save what should outlive it in
`context.dataDir`, in `disable()`.

`context.api` is `PluginApi`:

| What | Calls |
|---|---|
| Window and desktop events | `onWindowOpened`, `onWindowClosed`, `onWindowChanged`, `onFocusChanged`, `onWorkspaceChanged`, `onMonitorAdded`, `onMonitorRemoved`, `onColorSchemeChanged`, `onNotice`, `onLid`, `onLockChanged`, `onReload`, `onSleep`, `onWake`, `onQuit` |
| Input interception (return true to take it; not on a locked screen) | `onKey`, `onPointerMotion`, `onPointerButton`, `onScroll`, `onGesture` |
| Keys and actions | `bind`, `action`, `run`, `notify` |
| IPC | `command`: `--plugin cmd <name> ...` |
| Timers | `every`, `after`, `post` (from any thread) |
| Drawing and shaders | `addDrawing` (at any `DrawingLevel`), `addWidget`, `effectsFor`, `panelEffects`, `screenShader` |
| Layouts and rules | `layout`, `useLayout`, `rule`, `panelRule` |
| Protocols | `global`, `display` |
| Settings | `settings` (what the configuration tells it) |
| Everything else | `caulis`, `desktop`: as in a configuration |

What goes through `PluginApi` is tracked and guarded. What you get from `caulis` and `desktop` directly is
not: put each closable into `context.scope` (`scope.add(thing)`), or it stays behind, and catch your own
errors there. Threads: `scope.thread`, `scope.executor`, which end with the plugin.

Rules that keep a plugin unloadable: don't register anything in a static place of the compositor or of a
library it shares without taking it away; no `ThreadLocal` on the compositor's threads; no threads outside
`scope`. A leftover is found and reported in the log (`leakCheckMillis`).

### Class loaders

Each jar has its own class loader, so two plugins can bring different versions of a library. Its jar comes
first, then the plugins it `depends` on, then the compositor. The API, Kotlin and the JDK always come from
the compositor. Plugins talk to each other through classes of a plugin they depend on; when that one
reloads, they reload too.

## When things go wrong

- **It throws** (in any callback through `PluginApi`, also out of memory): it is disabled, you get a notice,
  and everything it registered goes. `plugin enable <id>` or a new file tries again.
- **Its new file does not work**: the one before goes on, and you get a notice.
- **It holds up the compositor's thread**: after 2 seconds the log gets its stack, after 5 its thread is interrupted
  (ends a sleep or a wait), after 10 it is written down so that the next start leaves it off. When the call returns,
  it is disabled and you get the notice. **A loop that never returns cannot be stopped from inside the JVM**: the
  desktop stays frozen until the compositor is killed, and starts without that plugin the next time.
- **The API is too new or too old**: refused with a notice; the compositor offers `caulis 0.1`. A plugin for 0.1 runs on
  0.1 and later 0.x, not on 1.0.
- **What a plugin needs is missing**: refused with a notice, started when it comes.

## For compositor authors

`caulis-plugins` knows nothing of a desktop: implement `PluginHost<MyApi>` and make a `PluginManager`. With `caulis-wm`
it is already there; to offer more, subclass `PluginApi` and say so:

```kotlin
plugins {
    api("mine", 0, 1) { handle, caulis -> MyApi(handle, caulis) }
    sharePackages("com.example.mine.")
}
```

Turn plugins off in a kiosk: `plugins { enabled = false }`.

With no plugin in the folders, plugins cost one thread waiting in the kernel: nothing per frame, no timers.
