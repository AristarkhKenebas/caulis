# Caulis

A toolkit for writing Wayland compositors in Kotlin and Java.

Status: early development.

A complete compositor — tiling with gaps, workspaces, the usual keys — in a few lines:

```kotlin
fun main() = caulis {
    layout = Dwindle(Gaps(inner = 8, outer = 12))
    bind("Super+Return") { launch("foot") }
    onStart { launch("foot") }
}
```

It opens as a window when started inside a Wayland session, and takes the whole screen when
started from a text console. The same in Java:

```java
Caulis.create()
        .layout(Layouts.Dwindle(new Gaps(8, 12)))
        .bind("Super+Return", c -> c.launch("foot"))
        .run();
```

**New here? Start with [Your first compositor](docs/getting-started.md)**, or create a
ready project right away: `templates/new-compositor.sh ~/my-compositor`.

See `examples/` (`minimal`, `minimal-java`, `custom-layout`, `window-rules`).

Many layouts are built in (master/stack on any side, centred master, i3-like strips,
dwindle, bspwm-like spiral, grid, columns, rows, monocle, scrolling) — and describing your
own takes a few lines of building blocks:

```kotlin
val Mine = tiling("Mine") {
    row { window(weight = 60); column(weight = 40) { rest() } }
}
```

Change layouts while running: windows glide to their new places.

Round corners, borders, shadows and blur behind see-through windows are one line:
`effects = Effects.MODERN.copy(blur = Blur())`.

## Modules

| Module | What it is |
| --- | --- |
| `caulis-native` | C libraries through the Foreign Function & Memory API, the event loop |
| `caulis-protocol` | the Wayland protocol (server and client side), generated from XML |
| `caulis-render` | drawing on the GPU (OpenGL ES; Vulkan later) |
| `caulis-geometry` | rectangles |
| `caulis-tiling` | the layout language and the built-in layouts (pure Kotlin) |
| `caulis-core` | surfaces, windows, input, outputs, the nested and screen backends |
| `caulis-wm` | `caulis { }`: a running compositor in a few lines |
| `caulis-cursor-bibata` | the Bibata cursor themes, ready to use (optional; GPL-3.0, as Bibata) |

## Requirements

- JDK 27
- Linux

## Build

```sh
./gradlew build            # build and test everything
./gradlew dokkaGenerate    # API documentation: build/dokka/html/index.html
```

## License

[MPL-2.0](LICENSE), except `caulis-cursor-bibata`, which is GPL-3.0 as the Bibata cursors
it contains (see [its license](caulis-cursor-bibata/LICENSE)).
