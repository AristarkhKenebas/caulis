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
started from a text console. Change the code while it runs: saved, compiled and taken on in
under a second, windows stay where they are (a mistake leaves the old version running and says
where it is). The same in Java:

```java
Caulis.create()
        .layout(Layouts.Dwindle(new Gaps(8, 12)))
        .bind("Super+Return", c -> c.launch("foot"))
        .run();
```

**New here? Start with [Your first compositor](docs/getting-started.md)**, or create a
ready project right away: `templates/new-compositor.sh ~/my-compositor`.

What changed in each release: [CHANGELOG.md](CHANGELOG.md).

Caulis is on Maven Central, and every release is also in its own Maven repository on GitHub
Pages (the same coordinates and signatures — a mirror for the months when Central's free monthly
limit is used up, so a new version may be there first):

```kotlin
repositories {
    mavenCentral()
    maven("https://aristarkhkenebas.github.io/caulis/maven") {
        content { includeGroup("io.github.aristarkhkenebas") } // (only Caulis is looked up there)
    }
    google() // (only for caulis-compose's Compose)
}
dependencies { implementation("io.github.aristarkhkenebas:caulis-wm:0.1.7") }
```

See `examples/` (`minimal`, `minimal-java`, `custom-layout`, `window-rules`, `desktop`, `ipc`).

Many layouts are built in (master/stack on any side, centred master, i3-like strips,
dwindle, bspwm-like spiral, grid, columns, rows, monocle, scrolling) — and describing your
own takes a few lines of building blocks:

```kotlin
val Mine = tiling("Mine") {
    row { window(weight = 60); column(weight = 40) { rest() } }
}
```

Change layouts while running. Animations are off by default and turned on Hyprland-like — all
with one line, or each by name with its time, curve and look: windows appearing, going away,
gliding, minimizing into the dock with a genie, workspaces sliding — made of building blocks
(`fade() + scale(0.9)`), or your own function or shader. Rules say where windows
open and how they look: `rule { appId("firefox"); workspace = 2 }`,
`rule { program("pavucontrol"); floating = true; size(700, 500); center() }`. Every usual key has a name
and can be changed or turned off (`keys { close = "Super+Shift+Q"; quit = null }`).

Several monitors work, each with its own workspace (or one workspace over all), panels and
scale. Mistakes in the configuration are shown on screen — as a strip at the top, cards in
a corner, or however you draw them.

The background is a colour, a gradient, a picture, your own drawing or a moving shader —
per monitor and workspace if you like; wallpaper programs (swaybg, hyprpaper, ...) work as
well and draw on top. Borders and shadows can be gradients, turning gradients, rainbows, glows or shaders of your
own, and change smoothly when the focus moves. Round corners, borders, shadows and blur behind see-through windows are one line:
`effects = Effects.MODERN.copy(blur = Blur())`. Caulis comes with sharp cursors of its own;
any installed cursor theme works too.

Screenshots and recordings (grim, wf-recorder, OBS, screen sharing in browsers through
xdg-desktop-portal-wlr) work, also of single windows.

X11 programs run too (Xwayland starts only when the first one does): their windows tile,
get title bars and effects like the rest, are sharp and the right size on scaled screens,
and copy and paste works between X11 and Wayland programs. Programs are started the
Wayland way when they can, and through X11 when that fails.

## Modules

| Module | What it is |
| --- | --- |
| `caulis-log` | the log: levels 0–3 for everything or single areas (`CAULIS_LOG="1,focus=3"`) |
| `caulis-native` | C libraries through the Foreign Function & Memory API, the event loop |
| `caulis-dbus` | a small D-Bus client on the event loop (brought by `caulis-core`) |
| `caulis-protocol` | the Wayland protocol (server and client side), generated from XML |
| `caulis-render` | drawing on the GPU (OpenGL ES; Vulkan later) |
| `caulis-geometry` | rectangles |
| `caulis-tiling` | the layout language and the built-in layouts (pure Kotlin) |
| `caulis-core` | surfaces, windows, input, outputs, the nested and screen backends |
| `caulis-xwayland` | X11 programs: Xwayland started on demand, their window manager, the shared clipboard |
| `caulis-ipc` | the IPC socket's messages (`caulis.proto`, for programs in any language) and a JVM client |
| `caulis-wm` | `caulis { }`: a running compositor in a few lines |
| `caulis-compose` | parts of the compositor's own interface in Compose: bars, docks, launchers, pop-ups (optional) |
| `caulis-screenshot` | screenshots in the compositor: an area, a window or a screen, saved and copied (optional) |
| `caulis-lua` | `config.lua`: the configuration in Lua — settings, keys, rules and events (optional) |
| `caulis-script` | `config.kts`: the configuration as a Kotlin script (optional; brings the Kotlin compiler, which runs in a short-lived Java of its own when the script changed — never inside the compositor) |
| `caulis-reload` | fast hot reload while developing: compiles your configuration inside the running compositor (optional, `run` task only) |

## Requirements

- JDK 27 (the classes are Java 26 bytecode)
- Linux, with the system libraries Caulis calls directly: libdrm, libgbm, libEGL, libGLESv2
  (Mesa, or a driver with GBM), libinput, libudev, libseat (seatd or systemd-logind),
  libxkbcommon — and, for X11 programs, Xwayland 23.1 or newer with libxcb, libxcb-composite,
  libxcb-xfixes and libxcb-render (separate packages on Debian and Ubuntu: without them X11
  programs are off, nothing else)

Or with [Nix](https://nixos.org): `nix develop` (or `nix-shell`) gives a shell with JDK 27,
Xwayland and every library. The flake also has Home Manager and NixOS modules for writing a
compositor's settings in Nix (see the guide, step 25).

## Build

```sh
./gradlew build            # build and test everything
./gradlew dokkaGenerate    # API documentation: build/dokka/html/index.html
ci/build-newm.sh           # newm (../newm, else a fresh clone) built and tested against these sources
```

## License

[MPL-2.0](LICENSE).
