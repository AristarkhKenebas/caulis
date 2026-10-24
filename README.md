# Caulis

A toolkit for writing Wayland compositors in Kotlin and Java.

irisWM is the compositor built with it (in `apps/iriswm`, a separate project).

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
        .layout(new Dwindle(new Gaps(8, 12)))
        .bind("Super+Return", c -> c.launch("foot"))
        .run();
```

**New here? Start with [Your first compositor](docs/getting-started.md)**, or create a
ready project right away: `templates/new-compositor.sh ~/my-compositor`.

See `examples/` (`minimal`, `minimal-java`, `custom-layout`, `window-rules`). Layouts: `Floating`, `Dwindle`,
`MasterStack`, `Scrolling` — or your own (implement `Layout`: windows and an area in,
rectangles out). Change them while running; windows move to their new places right away.

## Modules

| Module | What it is |
| --- | --- |
| `caulis-native` | C libraries through the Foreign Function & Memory API, the event loop |
| `caulis-protocol` | the Wayland protocol (server and client side), generated from XML |
| `caulis-render` | drawing on the GPU (OpenGL ES; Vulkan later) |
| `caulis-core` | surfaces, windows, input, outputs, layouts, the nested and screen backends |
| `caulis-wm` | `caulis { }`: a running compositor in a few lines |

## Requirements

- JDK 27
- Linux

## Build

```sh
./gradlew build            # build and test everything
./gradlew dokkaGenerate    # API documentation: build/dokka/html/index.html
```

## Try it

irisWM, the compositor built with Caulis, lives in `apps/iriswm` as a separate project
with its own build. See [its README](apps/iriswm/README.md):

```sh
cd apps/iriswm
./gradlew installDist
build/install/iriswm/bin/iriswm
```

## License

[MPL-2.0](LICENSE)
