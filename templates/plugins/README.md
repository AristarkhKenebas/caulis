# Plugin templates

A plugin of each kind, small enough to read in a minute. They work on any Caulis compositor.
The whole story is in [docs/plugins.md](../../docs/plugins.md).

| Folder | What | To install |
|---|---|---|
| `lua/hello.lua` | a Lua plugin: one file, sandboxed, reloads on save | copy it into `~/.config/<compositor>/plugins/` |
| `kotlin/` | a jar plugin in Kotlin | `gradle jar`, copy `build/libs/*.jar` there |
| `java/` | a jar plugin in Java | the same |

A jar plugin runs with your rights and is not sandboxed. Install only what you trust.
