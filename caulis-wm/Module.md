# Module caulis-wm

**Start here.** A complete compositor in a few lines:

```kotlin
fun main() = caulis {
    layout = Dwindle()
    bind("Super+Return") { launch("foot") }
}
```

Everything is configured on [dev.caulis.wm.Caulis]: the layout, keys (the usual ones in
[dev.caulis.wm.Keys], each can be changed or turned off), monitors, rules for particular
programs (`floatWhen`), events (`onWindowOpened`, ...), and what happens at start (`onStart`).
It runs as a window inside your current session, or on the whole screen from a text console.

# Package dev.caulis.wm

The `caulis { }` entry point and the `Caulis` class (also usable from Java).
