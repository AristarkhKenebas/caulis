# Your first compositor

This guide takes you from nothing to a working Wayland compositor of your own: windows,
tiling, workspaces, your own keys. No knowledge of Wayland or graphics is needed — if you
can write a bit of Kotlin (or Java), you can follow along.

## What you need

- Linux with a Wayland session (GNOME, KDE, Sway, ...) to try things in
- A GPU driver with OpenGL ES 3 — on almost every Linux with Mesa (AMD, Intel) or NVIDIA 545+
- JDK 27 (Gradle can also download it for you)
- Or just [Nix](https://nixos.org): `nix develop` in the Caulis folder (or in your project)
  gives a shell with JDK 27, Xwayland and every library — see step 25
- A terminal program for your compositor to open, e.g. [foot](https://codeberg.org/dnkl/foot)

## 1. Get Caulis

Caulis is on Maven Central (`io.github.aristarkhkenebas:caulis-wm`), so your project needs no
copy of it. Every release is also in Caulis' own Maven repository on GitHub Pages,
`https://aristarkhkenebas.github.io/caulis/maven`, with the same coordinates: a mirror for the
months when Central's free monthly limit is used up (a new version may be there first), so the
files below name both. The template and the examples come with its sources, though:

```sh
git clone <caulis repository> ~/caulis
```

## 2. Create your project

The quickest way — a ready project from the template:

```sh
~/caulis/templates/new-compositor.sh ~/my-compositor
cd ~/my-compositor
./gradlew run
```

(It uses Caulis from Maven Central or its GitHub Pages mirror; `new-compositor.sh --sources ~/my-compositor` builds it from
your Caulis folder instead.)

Then skip to step 3. If you prefer to see every piece, make a folder with three files:

`settings.gradle.kts`:

```kotlin
rootProject.name = "my-compositor"

plugins {
    // Downloads JDK 27 if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // The same releases (same coordinates), for when one is not on Central yet: Central's
        // free monthly limit. Only Caulis is looked up there.
        maven("https://aristarkhkenebas.github.io/caulis/maven") {
            content { includeGroup("io.github.aristarkhkenebas") }
        }
    }
}

// (Caulis comes from Maven Central or that mirror. To build it from the sources of step 1
// instead — your changes to Caulis at once — add: includeBuild("../caulis").)
```

`build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

kotlin {
    jvmToolchain(27)
    // Kotlin 2.4 writes Java 26 bytecode at most; it runs fine on Java 27.
    compilerOptions { jvmTarget = JvmTarget.JVM_26 }
}

tasks.withType<JavaCompile>().configureEach { options.release = 26 }

dependencies {
    implementation("io.github.aristarkhkenebas:caulis-wm:0.1.5")
}

application {
    mainClass = "MainKt"
    // Caulis talks to C libraries (graphics, input) and needs this permission.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "--sun-misc-unsafe-memory-access=allow")
}

// Only while developing with ./gradlew run: a change to Main.kt is compiled right inside the
// running compositor, taken on in about a second (step "Change it while it runs").
val development by configurations.creating
dependencies {
    development("io.github.aristarkhkenebas:caulis-reload:0.1.5")
}
tasks.named<JavaExec>("run") {
    classpath += development
}
```

`src/main/kotlin/Main.kt`:

```kotlin
import dev.caulis.tiling.Dwindle
import dev.caulis.wm.caulis

fun main() = caulis {
    layout = Dwindle()
    bind("Super+Return") { launch("foot") }
    onStart { launch("foot") }
}
```

Copy the Gradle wrapper from Caulis (`gradlew` and the `gradle/wrapper` folder) into your
folder, or run `gradle wrapper` if you have Gradle installed.

## 3. Run it

```sh
./gradlew run
```

A window opens: that is your compositor, running inside your normal session. A terminal
appears in it. Start more programs inside it from any terminal with the socket name it
printed:

```sh
WAYLAND_DISPLAY=wayland-1 foot
```

Each new window takes half of the previous one's space — that is the `Dwindle` layout.

> Inside another desktop, keys with **Super** are usually taken by that desktop. Try them
> when running on the real screen (step 7), or bind other keys while testing.

### Change it while it runs

Leave it running, change `Main.kt` and save: in under a second the compositor takes the
change on — keys, layouts, looks, rules, events — and your windows and programs stay where
they are. A notice at the top says *Configuration reloaded*.

If the new code does not compile, or fails (a key name it does not know, an exception), the
old configuration simply keeps running and a red notice says what is wrong and on which
line, e.g. `Main.kt:15: Unresolved reference 'lyaout'`. Fix it and save again; the notice
goes by itself.

- A few settings only change after a restart (the notice names them): `backend`, `xwayland`,
  `workspaceMode`, `nestedMonitors`, `hardwareCursor`, `directScanout`, `ipc`, `configName`,
  `title`, ... Keyboard layouts and options, Num Lock and key repeat change at once.
- A layout that remembers your arrangement (`BinaryTree`, `ManualTiling`, `ScrollingColumns`,
  `Canvas`) keeps it; a new kind of layout replaces it.
- `onStart` runs only once; `onReload { }` runs after each reload. Start programs (a bar,
  a wallpaper, `swayidle`) in `onStart`: the rest of `main` runs again on every save, and for
  `--check-config` too.
- Threads your own code started keep running; `exitProcess` in `main` would end everything.
- Your code is compiled right inside the running compositor (the module `caulis-reload`,
  which the starter project adds to `./gradlew run` only), and only what changed: well under
  a second from saving. The compiler warms up during the first half minute after starting,
  so a change right away takes longer. Projects with Kotlin compiler plugins, Compose, kapt/KSP or their own
  compiler settings are compiled by their Gradle build instead (several seconds); the log
  says so.

It works when you start with `./gradlew run` (from the project); an installed compositor
does not watch its sources (and contains no compiler). Turn it off with `hotReload = false`.

### Make a compositor from inside one

Once your desktop itself is a Caulis compositor (yours, or one made with it), the one you
are making runs as a window inside it — `./gradlew run` from a terminal there — and you
keep writing code next to it, every save taken on at once. While that window has the
keyboard, **all** keys go to it, Super ones too (it asks your desktop for that through the
keyboard-shortcuts-inhibit protocol); **Super+Escape** gives your desktop its keys back,
clicking the window again gives them to it once more (`keys.restoreShortcuts` changes the
key). Quitting and the text consoles stay your desktop's. GNOME and KDE honour the same
request too (GNOME asks you once whether to allow it).

Without any window at all — for scripts, tests, or a monitor to share — it runs
**headless**: on monitors that are nowhere on screen, drawn on the GPU all the same.
Programs connect as usual; screenshots (`grim`), screen sharing and the IPC socket (step 26)
see everything:

```sh
CAULIS_MODE=headless ./gradlew run              # or mode = Caulis.Mode.HEADLESS in Main.kt
WAYLAND_DISPLAY=wayland-1 foot &                 # programs inside it
WAYLAND_DISPLAY=wayland-1 grim shot.png          # what it shows
my-compositor --state
```

`headlessMonitors = 2` gives two; each is 1920x1080 unless `monitor("HEADLESS-1") { mode(2560, 1440) }`
says otherwise. `CAULIS_MODE=nested` and `CAULIS_MODE=screen` choose the other modes the
same way.

## 4. Keys

Without writing anything, you already have the usual keys. Each has a name (left column):

| Name | Keys | What they do |
| --- | --- | --- |
| `terminal` | Super+Return | open the terminal (`terminal = "foot"` changes which) |
| `close` | Super+Q | close the window |
| `dumpState` | Super+F12 | write the state (windows, focus, panels) to the log — see *When something goes wrong* |
| `lock` | Super+L | lock the screen (`lockCommand = "swaylock"` changes the locker) |
| `maximize` / `fullscreen` / `minimize` | Super+Up / Super+F / Super+Down | maximize / fullscreen / minimize |
| `float` | Super+V | take the window out of the layout (floating) and back |
| `nextWindow` | Alt+Tab | next window (also brings minimized ones back) |
| `overview` | Super+Tab | overview: the whole workspace zoomed out — click a window to go there, Escape to go back |
| `focusLeft` / `focusRight` | Super+Left / Super+Right | focus the neighbour (or the monitor beside) |
| `workspace` | Super + 1 … 9 | go to a workspace |
| `moveToWorkspace` | Super+Shift + 1 … 9 | move the window to a workspace |
| `move` | Super+Shift + arrows | move the window in the layout; past its edge, to the monitor beside |
| `resize` | Super+Ctrl + arrows | resize the window (layouts that remember sizes) |
| `preselect` | Super+Alt + arrows | where the next window goes / move the canvas / into the next column |
| `rotate` / `balance` / `columnWidth` | Super+R / Super+B / Super+W | tiling: rotate, even out, next column width |
| `splitBeside` / `splitBelow` | Super+S / Super+Shift+S | manual tiling: the next window beside / below |
| `windowDrag` | Super + drag | move (left button) or resize (right button) a window |
| `quit` | Ctrl+Alt+Backspace | quit — not on a locked screen, unless `quitWhileLocked = true` |
| `consoles` | Ctrl+Alt + F1 … F12 | text consoles (on the real screen) — also on a locked screen |

Change any of them, or turn it off with `null`, in `keys { }`:

```kotlin
keys {
    close = "Super+Shift+Q"
    dumpState = null          // off
    workspace = "Alt"         // Alt+1 … 9; for groups you give only the modifiers
    quit = "Ctrl+Alt+Delete"  // or null: no key quits
    quitWhileLocked = false   // the default: on a locked screen the quit key does nothing
}
```

On a locked screen the quit key does nothing by default: anyone at the keyboard could quit
the compositor (and with a compositor started from a text console, land in your logged-in
shell). `quitWhileLocked = true` lets it work there too.

Write keys however you like — case, spaces and order do not matter: `"Super+Shift+Q"`,
`"super shift q"`, `"wIn+Q"`, `"Mod4+Q"`, or in parts: `combo("super", "shift", "q")`. Media
keys (`XF86AudioRaiseVolume`, `XF86MonBrightnessUp`, ...), the number block (`KP_1`) and
F13…F24 work too, and with a Russian layout `Super+Й` is the same key as `Super+Q`. A typo
is reported with a guess: `Unknown key "Retrun" (did you mean Return?)`.

`keys { off() }` turns all of them off except `quit` and `consoles` (turn those off yourself
if you mean it — the log then warns that only ssh or the power button gets you out of a
stuck screen). A wrong name ("Super+Qq") is reported right where you wrote it.

Add your own with `bind`; on the same keys as a usual one, yours wins:

```kotlin
bind("Super+B") { launch("firefox") }
bind("Super+Q") { closeFocused() }
bind("super", "shift", "s") { launch("grim") }   // in parts
```

If two usual keys end up on the same combination, the one you changed wins, and a notice
on screen says so (see *Notices* below).

## 5. Layouts

| Layout | What it does |
| --- | --- |
| `Floating` | no tiling: windows go where you put them |
| `MasterStack()` | one big window, the rest stacked beside it (`side = Side.RIGHT/TOP/BOTTOM` too) |
| `CenteredMaster()` | the big window in the middle, the others left and right |
| `MasterStrips()` | the big window, the others as thin title strips — the focused one open (like i3) |
| `Dwindle()` | each new window takes half of the last one's space (like Hyprland) |
| `Spiral()` | halving in a spiral: left, top, right, bottom (like bspwm) |
| `Grid()` | an even grid |
| `Columns()` / `Rows()` | equal columns / rows |
| `Monocle()` | every window fills the screen, the focused one on top |
| `BinaryTree()` | a tree of splits, remembered (like bspwm): preselect where the next window goes, swap, resize, rotate, balance |
| `BspSpiral()` | the same tree, filled in a spiral |
| `ManualTiling()` | containers like i3: you choose whether the next window goes beside or below |
| `ScrollingColumns()` | columns on a strip that scrolls sideways (like niri); a column can hold several windows |
| `Canvas()` | one big canvas with a grid (like newm): windows keep their cells, you move the view |

The last five remember how you arranged things, and every workspace has its own (with
`Canvas`, every workspace is its own canvas). Their keys:

| Keys | What they do |
| --- | --- |
| Super+Shift+arrows | move the window (swap, move the column, move by a cell) |
| Super+Ctrl+arrows | make the window bigger that way |
| Super+Alt+arrows | preselect where the next window goes (trees), move the view (canvas), move into the next column (columns) |
| Super+S / Super+Shift+S | the next window goes beside / below (manual tiling) |
| Super+R / Super+B | rotate / balance the tree |
| Super+W | next column width (1/3, 1/2, 2/3, full) |

Focusing a window that is out of view (Alt+Tab, a dock) scrolls or moves the view to it.

All of them take gaps: `Dwindle(Gaps(inner = 8, outer = 12))`. `builtInLayouts()` lists
them all, e.g. to go through them with a key.

You can change it while running, e.g. on a key:

```kotlin
bind("Super+T") { layout = if (layout === Floating) Dwindle() else Floating }
```

Windows jump to their new places; with animations on (`animations { enabled = true }`)
they glide there instead (see *Animations*).


Gaps are the space between tiled windows (`inner`) and between them and the screen's edges
(`outer`, or each edge on its own), in desktop units from 0 to 500:

```kotlin
layout = Dwindle(Gaps(inner = 8, outer = 12))
layout = Dwindle(Gaps(inner = 8, outer = 12, top = 4))   // less at the top, under a bar
layout = Columns(Gaps(0, 0))                             // windows touch each other and the edges
```

For one program, a rule adds room around its windows (each side on its own), or with a
negative margin lets them reach into the gaps — never past the monitor:

```kotlin
rule { appId("org.gnome.Calculator"); margin(40) }
rule { appId("mpv"); margin(-8) }                        // a video up to the edges
rule { appId("spotify"); margin(top = 20, right = 0, bottom = 0, left = 0) }
```

Some layouts take more settings, and messages while running (as Hyprland's `layoutmsg`) —
the action `layout-message …`, on a key or from `caulisctl`:

```kotlin
layout = MasterLayout(ratio = 0.6, side = Side.LEFT, masters = 1, newWindow = NewWindow.STACK_TOP)
layout = BinaryTree(splitRatio = 0.6)                                // the old window keeps 60 %
layout = ScrollingColumns(newWidth = 0.5, centerFocused = true)     // the focused column in the middle
bind("Super+Equal") { run("layout-message ratio +0.05") }
bind("Super+Return") { run("layout-message swap-with-master") }
```

| Layout | Messages |
| --- | --- |
| `MasterLayout` (also `MasterStack`) | `ratio 0.6` / `+0.05` / `-0.05`, `side left\|right\|top\|bottom`, `masters +1` / `-1` / `2`, `swap-with-master`, `focus-master`, `cycle next\|prev` |
| `BinaryTree` | `toggle-split`, `swap-split`, `ratio 0.6` / `+0.05` |
| `ScrollingColumns` | `width 0.5` / `+0.1`, `center` |

In config.json the same are fields of `layout`: `"masters"`, `"newWindow"` (`stack-end`,
`stack-top`, `master`), `"splitRatio"`, `"columnWidths"`, `"newColumnWidth"`, `"centerFocused"`.

A program you need to keep an eye on — to know its windows by its process, to write to it,
to stop it later — is started with `spawn`: inside the compositor like `launch`, giving back
its `Process` (its words, no shell):

```kotlin
val wob = spawn(listOf("wob"))                            // its input is wob.outputStream
wob?.outputStream?.write("50\n".toByteArray())
```

`sessionBus` is the session's D-Bus, shared and on the compositor's thread — to offer objects
of your own to other programs (`export`, `requestName`, `emitSignal`) or to call theirs.

## 6. Rules and events

Rules say where windows open and how they look — one `rule { }` per kind of window:

```kotlin
rule { appId("firefox"); workspace = 2 }                         // opens there; you stay where you are
rule { appId("spotify"); workspace = 9; focus = false }          // and does not take the keyboard
rule { appId("pavucontrol"); floating = true; size(700, 500); center() }
rule { title("Picture-in-Picture"); floating = true; size(480, 270); position(20, 20) }
rule { appId("mpv"); titleBars = TitleBars.NONE; opacity = 1f }  // looks: as long as it fits
rule { program("steam"); monitor = "HDMI-A-1"; maximized = true }
```

Which windows: `appId("…")` (the whole id, case does not matter), `title("…")` (contained
in the title), `program("…")` (the executable, as `ps` shows it), `Regex(…)` for either, or
`where { window -> … }` for anything else; all that are given must fit. What happens:
`workspace`, `monitor`, `goToWorkspace = true` (show it), `floating`, `size(w, h)`,
`position(x, y)`, `center()`, `maximized`, `fullscreen`, `focus = false`, and for its looks
`titleBars`, `opacity`, `effects { it.copy(…) }`.

Where and how a window starts is decided before its first frame, so it never flashes up
somewhere else. Some programs set their title only after opening (Spotify, browsers): such
a window gets the rule when it starts to fit, once — move it elsewhere afterwards and it
stays. The app id of a program: open it and look in the log (*Opened …*), or press Super+F12.

### Rules that follow the window

A rule can also ask how a window is *now* — and what it says holds while that is so:

```kotlin
rule { isFloating(); cornerRadius = 14; shadow = true }              // floating windows look different
rule { isFocused(false); opacity = 0.9f }                            // the others a little see-through
rule { appId("mpv"); keepAwake = true; ignore(WindowRequest.MAXIMIZE) } // the screen stays on
rule { title("Picture-in-Picture"); pinned = true; takesFocus = false; size(480, 270) }
rule { appId("org.keepassxc.KeePassXC"); hiddenFromCapture = true }  // black in screen sharing
rule { appId("gimp"); minSize(400, 300); maxSize(1600, 1200) }       // while floating
```

Asking: `isFloating()`, `isFullscreen()`, `isMaximized()`, `isFocused()`, `isPinned()`,
`isXwayland()` (X11 programs; each takes `false` for the opposite), `onWorkspace(3)`,
`initialAppId("…")` and `initialTitle("…")` (as the window was when it opened), `hasTag("…")`;
`unless { … }` for windows that do *not* fit what is inside, `anyOf { … }` for windows that fit
one of the conditions inside (`allOf { … }` groups several into one of them).

Looks: `cornerRadius`, `border = false` (or `borderWidth`, `borderColor(focused, unfocused)`),
`shadow`, `blur` (true or false), `opacity`, `inactiveOpacity`. Behaviour: `minSize(w, h)` and
`maxSize(w, h)` (floating), `keepAwake`, `animated = false`, `hiddenFromCapture`,
`takesFocus = false`, `ignore(WindowRequest.FULLSCREEN, MAXIMIZE, MINIMIZE, ACTIVATE)` (what the
program asks is not done), `pinned = true` (it floats on every workspace its monitor shows).

Tags group windows for other rules — given by a rule while the window fits it, or by hand
with the actions `tag +video`, `tag -video` and `tag video` (on and off):

```kotlin
rule { anyOf { appId("mpv"); appId("vlc"); title("YouTube") }; tags("video") }
rule { hasTag("video"); keepAwake = true; animated = false; border = false }
bind("Super+P") { run("pin") }                                      // pin the focused window, or let it go
```

In config.json and Lua the same words are fields: `{ "isFloating": true, "cornerRadius": 14 }`,
`{ "unless": { "isXwayland": true }, "tags": ["wayland"] }`, `"ignore": ["maximize"]`,
`"minSize": [400, 300]`.

Events, for everything rules do not cover:

```kotlin
onWindowOpened { window -> println("Opened ${window.title}") }
onWindowClosed { window -> println("Closed ${window.title}") }
onWorkspaceChanged { number -> println("Workspace $number") }
```

`floatWhen { }`, `effectsFor { }` and `titleBarsFor { }` still work too (rules are the
shorter way to say the same).

If your code throws an exception, Caulis writes it to the log and keeps running — a mistake
in a key binding never takes your desktop down.

## 7. Scratchpads

Windows you call up with one key, above whatever workspace you are on — a drop-down
terminal, a music player, notes:

```kotlin
// A terminal sliding in from the top (started on the first press):
scratchpad("terminal", key = "Super+Grave", command = "foot --app-id terminal")

// Spotify sliding in from the right, found by its app id:
scratchpad("music", key = "Super+M", command = "spotify",
    style = Scratchpad.Edge(Side.RIGHT, depth = 0.4)) { it.appId == "spotify" }

// A whole workspace of its own above the current one (the rest dimmed):
scratchpad("notes", key = "Super+N", command = "gnome-text-editor",
    style = Scratchpad.Overlay()) { it.appId == "org.gnome.TextEditor" }
```

| Style | Looks like |
| --- | --- |
| `Scratchpad.Edge(side, depth, length, margin)` | a compact window sliding in from an edge |
| `Scratchpad.Center(width, height)` | a floating window in the middle |
| `Scratchpad.Overlay(layout, dim)` | a workspace above the current one, everything else dimmed; a click on the dimmed part hides it |

The key shows or hides it; the first press starts the program. By default a window belongs
to a scratchpad when its app id is the scratchpad's name — that is why the terminal above is
started with `--app-id terminal`.

### Special workspaces (Hyprland's `special`)

A scratchpad is one app summoned by its key. A *special workspace* is a whole workspace
layer: any window can be moved in and out, its windows are tiled with your usual layout,
and it appears over the workspace on the focused monitor — what is behind it dimmed (and
blurred, if you like). It is made the first time it is named; "special" is the one without
a name. It stays independent of the normal workspaces: switching them leaves it where it is.

```kotlin
bind("Super+S") { toggleSpecialWorkspace() }                      // Hyprland: togglespecialworkspace
bind("Super+Shift+S") { moveToSpecialWorkspace() }                // movetoworkspace special
bind("Super+M") { toggleSpecialWorkspace("music") }
bind("Super+Ctrl+S") { moveOutOfSpecialWorkspace() }
rule { appId("spotify"); specialWorkspace = "music" }             // opens there, unseen until shown
specialWorkspaces = SpecialWorkspaces(dim = 0.5f, blur = Blur(6, 3), gap = 60)
```

The same as actions (keys in config.json/Lua, `caulisctl`): `special-workspace toggle|show|hide [name]`,
`move-to-special [name] [silent]`, `move-out-of-special`, and Hyprland's
`move-to-workspace special:music [silent]`. In config.json: `"specialWorkspaces": { "dim": 0.5,
"blur": { "size": 6 }, "gap": 60 }` and a rule's `"specialWorkspace": "music"`.

| Setting | Default | What it does |
| --- | --- | --- |
| `dim` | 0.4 | how much darker everything behind a shown one is |
| `blur` | null | what is behind it blurred (a `Blur`) |
| `gap` | 40 | space between it and the monitor's edges (on top of the layout's gaps) |
| `size` | 1.0 | how much of the monitor it takes (0.2 to 1), in the middle |
| `animation` | `scratchpads` | the animation (by name) its windows come and go with — off unless animations are on |
| `hideWhenEmpty` | true | it hides when its last window closes or leaves |
| `newWindowsJoin` | true | windows opening while it is shown go into it |
| `hideOnLock` | true | shown ones hide when the screen locks (they are behind the lock either way) |
| `clickOutsideHides` | true | a click on the dimmed area hides it |
| `layout` | null | its own layout; null: the usual one (`layout`) |

Showing one gives it the keyboard (its newest window); hiding it gives the keyboard back to
the window that had it. One special workspace shows on a monitor at a time (showing another
hides it), on the monitor with the focus. A window of a hidden one that gets the focus (from
a task bar) shows it. Windows waiting in a hidden one are counted in `allWindows`, not on any
workspace.

## 8. Panels, docks, wallpapers, launchers

Programs made for that (they use the *layer shell*) work as they are — Waybar, eww, swaybg,
fuzzel, mako, or a dock you write yourself. Start them with the compositor:

```kotlin
onStart {
    launch("swaybg -i ~/Pictures/wallpaper.jpg")
    launch("waybar")
}
bind("Super+D") { launch("fuzzel") }
```

(Or with `autostart { app("waybar") }`: one after another, restarted after a crash if you
like — see step 31.)

Docks and taskbars (Waybar's taskbar, nwg-dock, sfwbar, or your own) see every window and
can activate, minimize, maximize and close it; with animations on, a window minimized from
a dock flies into its icon there and comes back out of it (see *Animations*).

A bar that reserves space (as Waybar does) keeps it free: tiled and maximized windows stay
next to it, never under it. `desktop.usableArea` is what is left for windows. A launcher gets
the keyboard while it is open; fullscreen windows cover bars, but not notifications.

## 9. The background

What shows behind everything is yours to choose — still or moving:

```kotlin
background = Background.color(Color.hex("#1e1e2e"))
background = Background.gradient(Color.hex("#1e3c72"), Color.hex("#2a5298"), degrees = 90.0)
background = Background.image(Path.of("/usr/share/backgrounds/forest.jpg"))          // fills the monitor
background = Background.image(Path.of("logo.png"), Background.Fit.CENTER, behind = Color.hex("#000000"))
background = Background.shader(WallpaperShader.AURORA)                               // slowly moving colours
background = Background.painted { g, look -> g.color = java.awt.Color.ORANGE; g.fillOval(0, 0, look.width, look.height) }
```

Pictures: PNG, JPEG, BMP, GIF; `Fit.FILL` (cut to fill, the default), `FIT`, `STRETCH`,
`CENTER`, `TILE`. They are read in the background, so starting is not slowed down.

A different one per monitor and workspace — it melts into the next when you switch:

```kotlin
backgroundFor { monitor, workspace -> Background.image(Path.of("/home/me/walls/$workspace.jpg")) }
backgroundChangeMillis = 400
```

Your own moving wallpaper is a shader: GLSL with one function that gets a point of the
monitor (0 to 1 from the top left) and returns its colour. `time` (seconds), `size`
(pixels), `pointer` (where the pointer is, 0 to 1, or -1 on another monitor) and `workspace`
are there to use, and `image(uv)` reads a picture if you give one:

```kotlin
background = Background.shader(WallpaperShader("""
    vec4 wallpaper(vec2 at) {
        float glow = 0.15 / (distance(at, pointer) + 0.15);              // light follows the pointer
        float wave = 0.5 + 0.5 * sin(at.x * 8.0 + time);
        return vec4(0.05 + 0.2 * glow, 0.1 + 0.1 * wave, 0.25 + 0.3 * glow, 1.0);
    }
""", framesPerSecond = 30))
```

Over several monitors a wallpaper can go on from one to the next instead of repeating:
a picture with `span = true`, and in a shader `desktopAt(at)` (the place on all monitors
together, 0 to 1) or `unitsAt(at)` (in desktop units — seamless even when the monitors have
different scales). `monitor` and `desktop` (x, y, width, height in units), `scale` and
`physicalSize` (millimetres) let a shader fit itself to each monitor:

```kotlin
background = Background.image(file("panorama.jpg"), span = true)
background = Background.shader(WallpaperShader("""
    vec4 wallpaper(vec2 at) {
        vec2 p = desktopAt(at);                                  // one picture over all monitors
        float rings = 0.5 + 0.5 * sin(length(unitsAt(at) - desktop.xy - desktop.zw * 0.5) / 40.0 - time);
        return vec4(0.1 + 0.3 * p.x, 0.15 * rings, 0.3 + 0.2 * p.y, 1.0);
    }
"""))
```

Shaders are easier to write in files of their own, next to your configuration — saved,
they are used at once (no restart, nothing else reloads), and a mistake shows up as a red
notice with its line in your file (`line 7: 'glow' undeclared`), gone as soon as it works:

```kotlin
configName = "my-compositor"     // your folder: ~/.config/my-compositor (or $XDG_CONFIG_HOME/...)
background = Background.shader(WallpaperShader.file(file("aurora.glsl")))
animations = Animations(enabled = true, minimize = WindowAnimation { m ->
    WindowFrame(m.from.union(m.to), shader = WindowShader.file(file("melt.glsl")), shaderFrom = m.from, shaderTo = m.to, progress = m.progress)
})
background = Background.image(file("walls/sea.jpg"))   // pictures too: saved again, shown anew
```

`file("aurora.glsl")` is in your folder, `file("~/walls/sea.jpg")` in your home,
`file("/usr/share/...")` where it says; `configDir = Path.of(...)` puts the folder elsewhere.
The log says where the folder is when the compositor starts.

A shader that uses `time` or `pointer` is drawn `framesPerSecond` times a second, only
while it can be seen (about 6% of one core at 30 per second, nested); one that does not
compile is said once in the log and the usual background shows.

A wallpaper program — swaybg, hyprpaper, swww, mpvpaper, waypaper's backends, anything on
the background layer — simply draws on top: it wins, and a moving shader below it rests.

**A view over the background**: show only a part of it, moving and zooming as your own view
of a big canvas does — fractions of the whole picture, from the top left:

```kotlin
desktop.setBackgroundView(monitor, BackgroundView(x = 0.25, y = 0.1, width = 0.5, height = 0.5))
desktop.setBackgroundView(monitor, null)   // the whole background again
```

A picture or a painted background shows that part stretched over the monitor; a shader gets
it as `view` — `image(viewAt(at))` reads its picture through the view.

## 10. Round corners, borders, shadows, blur

Off by default; the template switches on a common look:

```kotlin
effects = Effects.MODERN // round corners, a border around the focused window, soft shadows
```

Or choose each part yourself (leave out what you do not want):

```kotlin
effects = Effects(
    cornerRadius = 12,
    border = Border(width = 2, focused = Color.hex("#89b4fa"), unfocused = Color.hex("#45475a")),
    shadow = Shadow(softness = 24, offsetY = 6),
    blur = Blur(size = 4, passes = 2), // frosted glass behind see-through windows
    inactiveOpacity = 0.9f,            // windows without the focus are a bit see-through
)
```

Blur only shows where you can see through a window: a terminal with a transparent
background (for foot: `colors-dark.alpha=0.8` in its config) or with `inactiveOpacity`
below 1. Some windows look better without some effects — a video should stay solid:

```kotlin
effectsFor { window, fx -> if (window.appId == "mpv") fx.copy(blur = null, inactiveOpacity = 1f) else fx }
```

Borders and shadows can be more than one colour — a gradient, one that turns around the
window, a rainbow, or a shader of your own (`Paint`, from `dev.caulis.render`):

```kotlin
effects = Effects(
    cornerRadius = 14,
    border = Border(
        width = 3,
        focused = Paint.gradient(Color.hex("#33ccff"), Color.hex("#00ff99"), degrees = 45.0),   // as Hyprland's
        unfocused = Paint.solid(Color.hex("#45475a")),
    ),
    shadow = Shadow(softness = 28, offsetX = 0, offsetY = 0, color = Color.hex("#b46cffaa"), spread = 4), // a purple glow
    inactiveShadow = Shadow(softness = 12, offsetX = 0, offsetY = 4, color = Color.hex("#00000066")),   // Shadow.NONE: none
    focusChangeMillis = 250,   // border and shadow change smoothly when the focus moves
)
```

| Paint | What it is |
| --- | --- |
| `Paint.solid(color)` | one colour |
| `Paint.gradient(a, b, ..., degrees = 45.0)` | up to 8 colours across the window (0°: left to right, 90°: top to bottom) |
| `Paint.conic(a, b, ..., turnsPerSecond = 0.25)` | colours around the window's middle — turning, if you like |
| `Paint.rainbow(seconds = 6.0)` | a rainbow going round |
| `Paint.shader("vec4 paint(vec2 at) { ... }")` | your own: `at` is 0..1 in the window, `time` and `size` are there |
| `Paints.file(file("border.glsl"))` | your own from a file next to your configuration, used again when saved |
| `Paint.parse("rgba(33ccffee) rgba(00ff99ee) 45deg")` | Hyprland's way of writing it (also `rgb(…)`, `0xaarrggbb`, `#rrggbb`) |

Like Hyprland's `col.active_border`, `col.inactive_border`, `col.urgent` and its group
borders, a border has a paint for each state:

```kotlin
border = Border(
    width = 3,
    focused = Paint.parse("rgba(33ccffee) rgba(00ff99ee) 45deg"),
    unfocused = Paint.parse("rgba(595959aa)"),
    urgent = Paint.parse("rgb(ff5555)"),            // a window that wants attention (null: as the others)
    groupedFocused = null, groupedUnfocused = null, // windows in a group (desktop.windowGrouped says which)
    animate = true,                                 // false: turning gradients and time-using shaders stand still
)
rule { appId("firefox"); borderPaint(Paint.parse("rgba(ff8800ff) rgba(ff0088ff) 90deg")) }   // per window
```

In config.json each of `focused`, `unfocused`, `urgent`, `groupedFocused`, `groupedUnfocused`
is a colour, Hyprland's text, or `{ "gradient": [...], "degrees": 45 }`, `{ "conic": [...] }`,
`{ "rainbow": 6 }`, `{ "shader": "glow.glsl" }`; `"animate": false` stops moving ones; a
rule's `"borderPaint": { "focused": ..., "unfocused": ..., "urgent": ... }`.

#### Border shaders

A shader paint is GLSL (OpenGL ES 3) with `vec4 paint(vec2 at)` returning a plain colour.
It can read:

| Name | What it is |
| --- | --- |
| `vec2 at` | 0..1 in the box (the window and its border), from the top left |
| `float time` | seconds, going on while it is seen (stands at 0 with `animate = false`) |
| `vec2 size`, `vec2 pixel` | the box in pixels, this pixel in it |
| `float borderWidth`, `float cornerRadius` | in pixels |
| `float progress` | how far along the border this pixel is: 0..1 clockwise from the top left corner |
| `float focused`, `float urgent`, `float grouped` | 1.0 or 0.0: the window's state |
| `vec4 colors[8]`, `int colorCount` | colours handed to it: `Paint.Shader(...).withColors(a, b)` |

A file (`Paints.file(...)`, or `{ "shader": "glow.glsl" }`) is read again when you save it.
A shader that does not compile is said in a notice with its line ("line 2: ..."), and the
border is drawn in its first colour meanwhile — never black, never frozen. A moving shader
keeps frames coming only while its window is seen.

A rainbow turning around the window:

```glsl
vec4 paint(vec2 at) {
    float hue = fract(progress + time * 0.2);
    vec3 c = clamp(abs(mod(hue * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return vec4(c, 1.0);
}
```

A glow that pulses on the focused window (and blinks red on an urgent one):

```glsl
vec4 paint(vec2 at) {
    float pulse = 0.55 + 0.45 * sin(time * 3.0);
    vec3 base = urgent > 0.5 ? vec3(1.0, 0.3, 0.3) : vec3(0.35, 0.75, 1.0);
    return vec4(base * (focused > 0.5 ? pulse : 0.4), 1.0);
}
```

Marching ants: dashes walking around the border:

```glsl
vec4 paint(vec2 at) {
    float along = progress * 2.0 * (size.x + size.y);   // pixels along the border
    float dash = step(0.5, fract((along - time * 40.0) / 16.0));
    return mix(vec4(0.1, 0.1, 0.1, 1.0), vec4(1.0, 0.85, 0.2, 1.0), dash);
}
```

A shadow's `spread` makes it bigger than the window (a glow around all sides when the
offsets are 0). Moving paints are drawn every frame while seen, but only the thin frame
around the window is redrawn — a few percent of one core.

Windows without the focus can be darker — `dimInactive = 0.3f` in `Effects` (a rule's
`dimInactive = 0f` keeps a video bright). Blurred glass can be toned as in Hyprland:
`Blur(size = 6, passes = 3, tone = BlurTone.HYPRLAND)`, or your own
`BlurTone(noise = 0.01f, contrast = 0.9f, brightness = 0.8f, vibrancy = 0.2f)`.

Panels get no effects unless you ask, by the name they give themselves:

```kotlin
panelRule { namespace("waybar"); blur = true }
panelRule { namespace("fuzzel"); dimAround = 0.4f; cornerRadius = 12 }  // the desktop darker behind the launcher
panelRule { namespace("notifications"); hiddenFromCapture = true }    // not in screen sharing
```

`namespace(Regex(…))`, `layer(LayerShell.Layer.OVERLAY)` and `where { … }` choose panels too;
`blur`, `cornerRadius`, `shadow`, `opacity`, `effects { … }`, `dimAround` and
`hiddenFromCapture` say what happens (`panelEffects { … }` still works).

The whole screen can go through one more shader at the end — shades of grey, a night light,
your own CRT look:

```kotlin
screenShader = ScreenShader.GRAYSCALE
screenShader = ScreenShader.warm(0.4f)                                   // less blue light
screenShader = ScreenShader.file(file("crt.glsl"), moving = true)        // vec4 effect(vec4 color, vec2 at)
```

Your code gets each pixel's `color` at `at` (0..1 from the top left) and returns what is
shown; `screenAt(at)` reads the screen elsewhere, `time` and `size` are there. A file is read
again when you save it; a mistake is written to the log and the screen stays as it is.

Fullscreen windows never get effects. The names come from `dev.caulis.core` (`Effects`,
`Border`, `Shadow`, `Blur`) and `dev.caulis.render.Color`; IntelliJ adds the imports when you
press Alt+Enter on a red name.

## 11. Animations

Animations are **off by default**: windows, workspaces and the overview change at once.
Following fingers is not an animation — a swipe or a pinch still moves with them, only what
comes after they lift (the snap) is at once. Turn them on as in Hyprland: everything with one
line, or each animation by name, with its time, curve and look:

```kotlin
animations {
    enabled = true                                   // all of them (the usual looks and times)
    millis = 220                                     // the global time, for those without their own
    curve = Curve.bezier(0.05, 0.9, 0.1, 1.05)       // the global curve
    animation("windows", millis = 300)               // windowsIn, windowsOut, windowsMove, … too
    animation("windowsIn", style = "popin 80%")
    animation("workspaces", speed = 3.0, style = "slidevert")   // Hyprland's speed: tenths of a second
    animation("minimize", curve = Curve.spring(stiffness = 300.0, damping = 14.0))
    off("fade")                                      // and everything under it
}
animations { on("workspaces") }                      // only this one, the rest stays off
```

A setting for a name is for everything under it, unless something lower down says
otherwise; what no one says comes from the animation's usual (its own time, as below), then
the global `millis` and `curve`. In Java: `caulis.animations(a -> { a.setEnabled(true); a.animation("windowsIn", null, null, null, "popin 80%"); })`.

| Name | Under | What it animates | Usual time, curve | Looks (`style`) |
| --- | --- | --- | --- | --- |
| `windows` | | every animation of windows below | | as `windowsIn` |
| `windowsIn` | windows | a window appearing | global (220 ms), ease-out | `popin [80%]`, `pop [90%]`, `fade`, `scale [90%]`, `slide [left\|right\|up\|down] [40]`, `slide-up`, `none` |
| `windowsOut` | windows | a window going away | global, ease-out | as `windowsIn` |
| `windowsMove` | windows | windows gliding to a new place or size: a layout change, maximize, a neighbour closing, the view settling after a swipe | global, ease-out | `move`, `none` |
| `scratchpads` | windows | a scratchpad sliding in and out | global, ease-out | `move`, `none` |
| `minimize` | windows | a window going into the dock (its icon, or down out of sight) | global, ease-out | `genie` (usual), `fly`, and those of `windowsIn` |
| `restore` | windows | a minimized window coming back | global, ease-out | as `minimize` |
| `workspaces` | | another workspace shown on a monitor (also after a swipe) | global, ease-out | `slide` (usual), `slidevert`, `fade`, `none` |
| `overview` | | the overview zooming out and in, also finishing a pinch | 1.5 × global (330 ms), ease-out | |
| `border` | | border, shadow, opacity and dimming changing when the focus moves | 150 ms (`Effects.focusChangeMillis`) | |
| `screenshot` | | the screenshot chooser coming in: the screen darkening, its toolbar rising | 170 ms (`ChooserBehaviour.appearMillis`), ease-out | |
| `fade` | | every fade below | | |
| `fadeScale` | fade | the picture melting into the new one when a monitor's scale changes | 250 ms (`Animations.scaleChangeMillis`), smooth | |
| `fadeBackground` | fade | one background melting into the next | 400 ms (`backgroundChangeMillis`), smooth | |
| `fadeNotices` | fade | the compositor's notices fading in and out | 180 ms (`NoticeStyle.fadeMillis`), smooth | |
| `fadeOsd` | fade | the volume and brightness display fading in and out | 150 ms, linear | |

Curves: `Curve.EASE_OUT` (the global one by default), `EASE_IN_OUT`, `LINEAR`, `SMOOTH`,
`OVERSHOOT`, a Bézier curve with the numbers Hyprland and CSS use —
`Curve.bezier(0.05, 0.9, 0.1, 1.05)` — a spring, or your own: `Curve { t -> t * t }`. A spring
(`Curve.spring(stiffness = 300.0, damping = 14.0)`; less damping bounces more) takes as long
as it needs to come to rest, unless the animation's time says otherwise.

In config.json and Lua the same, with curves by name — an animation is `true`/`false`, its
look alone, or `{ enabled, millis (or speed), curve, style }`:

```jsonc
"animations": {
  "enabled": true,
  "millis": 220,
  "curves": { "snappy": { "bezier": [0.05, 0.9, 0.1, 1.05] }, "bouncy": { "spring": { "stiffness": 300, "damping": 14 } } },
  "curve": "snappy",                              // the global one; ready: ease-out, ease-in-out, linear, smooth, overshoot
  "windows": { "millis": 300 },
  "windowsIn": "popin 80%",
  "minimize": { "style": "genie", "curve": "bouncy" },
  "workspaces": { "speed": 3, "style": "slidevert" },
  "fade": false
}
```

**Coming from Hyprland.** `animations { enabled = yes }` is `"enabled": true`;
`bezier = snappy, 0.05, 0.9, 0.1, 1.05` is `"curves": { "snappy": { "bezier": [0.05, 0.9, 0.1, 1.05] } }`;
`animation = windowsIn, 1, 3, snappy, popin 80%` is `"windowsIn": { "enabled": true, "speed": 3, "curve": "snappy", "style": "popin 80%" }`
(`speed` is Hyprland's tenths of a second; `millis` says it in milliseconds). The names
`windows`, `windowsIn`, `windowsOut`, `windowsMove`, `workspaces`, `border` and `fade` mean
what they mean there; Hyprland's `layers*` and `borderangle` have no counterpart (Caulis does
not animate panels or turn gradients), and Caulis has its own `minimize`, `restore`,
`scratchpads` (Hyprland's `specialWorkspace`), `overview` and the `fade*` above. A compositor
built on Caulis can name its own animations the same way (`AnimationTree`, `AnimationSettings`).

The looks can also be code — building blocks or your own (they run when their animation is
on, with its time and curve unless they say `lasting(…)`/`curved(…)`):

```kotlin
animations = Animations(
    enabled = true,
    windowOpen = WindowAnimation.fade() + WindowAnimation.slide(dy = 80),   // fades in rising
    windowClose = WindowAnimation.fade(),
    minimize = WindowAnimation.pop(),                                      // no genie
    workspaceSwitch = WorkspaceAnimation.SLIDE_VERTICAL.lasting(300),
    curve = Curve.OVERSHOOT,                                               // a little past, and back
)
animationMillis = 220   // the global time (0 = nothing moves, whatever else says)
```

| Setting | When (name) | Ready ones |
| --- | --- | --- |
| `windowOpen` / `windowClose` | a window appears / goes away (`windowsIn` / `windowsOut`) | `fade()`, `scale(0.9)`, `slide(dx, dy)`, `pop()` (= fade + scale) |
| `windowMove` | a window glides to a new place or size (`windowsMove`, `scratchpads`) | `move()` |
| `minimize` / `restore` | into the dock icon / back | `genie()`, `flyTo()`, `pop()` |
| `workspaceSwitch` | another workspace (`workspaces`) | `SLIDE`, `SLIDE_VERTICAL`, `FADE`, `NONE` |

Building blocks go together with `+` (`fade() + scale(0.5)`), and each can have its own
time and curve: `pop().lasting(400).curved(Curve.EASE_IN_OUT)`. A `style` set by name wins
over the code's look.

Your own animation is one function: it gets where the window is going and how far along it
is (0 to 1), and says where to draw it and how strongly:

```kotlin
val spin = WindowAnimation { m ->
    // m.from, m.to: start and end; m.progress: 0 → 1; m.window: the window
    WindowFrame(WindowAnimation.scaled(m.to, m.progress), alpha = m.progress.toFloat())
}
animations = Animations(enabled = true, windowOpen = spin.lasting(250))
```

For effects beyond moving and fading — bending, colouring, dissolving — give a shader:
GLSL with one function that gets a point of the drawn rectangle (0 to 1) and returns its
colour. `window(uv)` reads the window's picture; `progress`, `from` and `to` (the picture's
start and end within the rectangle, as x, y, width, height) and `size` (pixels) are there:

```kotlin
val dissolve = WindowShader("""
    float noise(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
    vec4 effect(vec2 at) {
        return noise(floor(at * size / 4.0)) < progress ? window(at) : vec4(0.0);
    }
""")
animations = Animations(enabled = true, windowOpen = WindowAnimation { m -> WindowFrame(m.to, shader = dissolve, progress = m.progress) })
```

The genie itself is such a shader (`WindowShader.GENIE`) — read it as an example. A shader
that does not compile is reported once in the log, and the window is drawn without it.

More to tune, each one line: `overviewFill = 0.9` (how much of the monitor the overview's
windows take), `resizeBorder = 6` (the edge to grab floating windows by), `doubleClickMillis
= 400` (a double click on a title bar maximizes), `swipeDistance = 80.0` (how far fingers
move for a swipe), `lockColor` (behind the screen locker), `Animations(scaleChangeMillis =
250)` (the picture melting when the scale changes, `fadeScale`), `NoticeStyle(fadeMillis = 180)`
(`fadeNotices`).

## 12. Title bars

Who draws them — the programs (the default), the compositor, or nobody:

```kotlin
titleBars = TitleBars.COMPOSITOR                                       // the same for every window
titleBarsFor { if (it.appId == "mpv") TitleBars.NONE else null }       // some programs differently
```

The compositor's bars have the title, minimize / maximize / close buttons, drag to move,
double click to maximize, and floating windows can be resized at their edges. Their look:

```kotlin
titleBarStyle = TitleBarStyle(
    height = 32, font = "Inter", fontSize = 13, centerTitle = false,
    buttons = listOf(TitleButton.CLOSE, TitleButton.MINIMIZE, TitleButton.MAXIMIZE), buttonsLeft = true, // like macOS
    focusedBackground = Color.hex("#1e1e2e"), focusedText = Color.hex("#cdd6f4"),
)
```

or entirely your own drawing (Java2D; sizes are pixels, `bar.scale` is the screen's scale):

```kotlin
titleBarStyle = TitleBarStyle(painter = { g, bar ->
    g.color = java.awt.Color(if (bar.focused) 0x3355ff else 0x444444)
    g.fillRect(0, 0, bar.width, bar.height)
    g.color = java.awt.Color.WHITE
    g.drawString(bar.title, (8 * bar.scale).toInt(), bar.height * 2 / 3)
})
```

Programs are asked to leave their own bar out (Qt, Chromium and Electron apps such as
Spotify, mpv, foot, Alacritty, GTK 3 apps do); the compositor draws one only when they
agreed, so there are never two. GTK 4 / libadwaita apps keep theirs — it is part of their
window. Fullscreen windows never have a title bar.

## 13. Bigger or smaller: the scale

```kotlin
scale = 1.25 // or 1.5, 2.0 ... — fractions are fine
```

Everything gets bigger by that much, and stays sharp: programs are told the scale and draw
at exactly that size (foot, GTK 4, Qt 6, Firefox, Chromium do; older ones draw at the next
whole scale and are shrunk a little). The cursor is loaded in its scaled size, windows tile
without gaps or overlaps at any fraction, and screenshots have the screen's full
resolution. The scale can be changed while running: the old picture melts into the new
one in a quarter of a second while programs draw themselves anew — nothing jumps, and no
program is restarted or loses anything.

## 14. Several monitors

Plug them in: each monitor shows a workspace of its own, the pointer and windows move
across, panels belong to the monitor they ask for, and each monitor can have its own
scale. `Super+N` shows workspace N on the monitor with the focus — or, when another monitor
already shows it, goes there. A window opens on the monitor with the focus; `move`
(Super+Shift+arrows) takes it to the monitor beside when the layout ends.

```kotlin
monitor("eDP-1") { scale = 1.5 }                       // the laptop's own screen
monitor("DP-2") { scale = 1.0; position(1280, 0); mode(2560, 1440, 144.0) }
monitor("HDMI-A-1") { enabled = false }                // not used at all
primaryMonitor = "DP-2"                                // the main one (else the first)
```

**How workspaces and monitors go together** — `workspaceMode`, one of five (it changes
after a restart):

| `WorkspaceMode.` | | Like |
|---|---|---|
| `PER_MONITOR` (default) | Numbers are shared; `Super+N` shows N on the monitor with the focus, or goes to where it is shown. | sway, Hyprland |
| `PINNED` | Workspaces are screens: each stays on the monitor that showed it first (or its rule's), and `Super+2` may simply be "the second monitor". One bar shows them all. | i3 with assigned outputs |
| `SEPARATE` | Every monitor has its own 1, 2, 3, ...; `Super+N` switches the one with the focus. Windows move between monitors freely. | dwm, awesome, qtile |
| `SPANNING` | One workspace over all monitors; `Super+N` switches them all. | GNOME |
| `PRIMARY_ONLY` | Only the primary monitor has workspaces (and, usually, the bar); every other monitor is one screen of its own — move windows there, nothing switches there. If the primary monitor is unplugged, the next one takes its workspaces. | GNOME's "workspaces on primary display only" |

**Workspaces as you like them** — how many, and each one on its own:

```kotlin
workspaceCount = 5                                     // 1: no workspaces at all, one desktop
workspace(1) { name = "web"; monitor = "DP-2" }        // always on DP-2
workspace(3) { name = "chat"; persistent = true }      // always there, even empty (bars show it)
workspace(4) { layout = ScrollingColumns() }           // its own layout
```

Names go to bars and programs (the IPC socket, step 26). Actions for keys (step 24) reach
them by number or relatively: `workspace back` (the one before — "back and forth"),
`workspace next`, `workspace prev`, `workspace empty` (the first one with no windows), and
the same for `move-to-workspace`. Monitors: `focus-monitor` and `move-to-monitor` with
`left`, `right`, `up`, `down`, `next`, `primary` or a connector.

The names are the connectors; the log lists them (level 2) when the compositor starts.
Without `position`, monitors stand side by side in the order they were found. Scale and
position can be changed while running. When a monitor goes away, nothing is closed: its
workspace waits on another monitor (Super+N shows it there), and comes back by itself when
the monitor is plugged in again — unless you showed it somewhere else meanwhile.

Monitors of different sizes: by default the mouse crosses only where they touch, as on
Windows (next to a taller monitor, a shorter one is reached along the part they share).
Or every edge leads to the whole edge beside it — top corner to top corner, bottom to
bottom, whatever the sizes:

```kotlin
monitorEdges = MonitorEdges.PROPORTIONAL   // MonitorEdges.TOUCHING is the default
```

**A laptop with a projector or a monitor.** One never seen before is used beside the
laptop's screen, and the choice is shown at once: *Extend*, *Mirror*, *Laptop screen only*,
*Other screen only*. The laptop's display key (F7 on many, `XF86Display`) shows it any time:
each press moves to the next one, which is used a moment after the last press. What you chose
is kept for that set of monitors (in `~/.local/state/<configName>/displays`): the same
projector next week comes back the same way. A mirror shows the laptop's picture as large as
the other screen allows (its own size when the projector has it, black bars when the shapes
differ). With the lid closed, the laptop's screen is off while another one is in use.

```kotlin
keys.displays = "Super+P"          // another key for the choice (null: none)
displayChooserOnPlug = false       // a new monitor is just used beside the laptop's screen
lidTurnsScreenOff = false
useDisplays(DisplayMode.MIRROR)    // from your own code; the action: "displays mirror"
```

To try it without a second monitor, run nested in several windows — each one a monitor
(`NESTED-1`, `NESTED-2`, ...):

```kotlin
nestedMonitors = 2
monitor("NESTED-2") { scale = 1.0 }
```

## 15. Notices

When something in your configuration is wrong — a key used twice, a rule that throws, a
terminal that is not installed — the compositor says so on screen: a yellow strip at the
top for warnings, a red one for errors; windows and panels make way for it. Click it to
close it; warnings go by themselves after 12 seconds, errors stay until clicked.

Where they are and how they look is yours to choose:

```kotlin
noticeStyle = NoticeStyle(placement = NoticePlacement.Toast())   // cards at the bottom right, like notifications
noticeStyle = NoticeStyle(placement = NoticePlacement.Toast(NoticePlacement.Toast.Corner.TOP_RIGHT, width = 320))
noticeStyle = NoticeStyle(placement = NoticePlacement.Banner(bottom = true, room = Room.COVER))  // over everything, nothing moves
noticeStyle = NoticeStyle(
    warning = NoticeColors(background = Color.hex("#ff9f1c"), text = Color.hex("#000000")),
    fontSize = 15, warningSeconds = 30.0, everyMonitor = true,
)
```

`Banner` rooms: `PUSH_ALL` (windows and panels move aside, the default), `PUSH_WINDOWS`
(over the panels, windows move aside), `COVER` (over everything). For a look entirely your
own, give a `painter` (a `NoticePainter`, like the title bar one: you get a `Graphics2D`
and draw). Or don't draw them at all and show them your own way — in your panel, through
a notification program:

```kotlin
noticeStyle = NoticeStyle(placement = NoticePlacement.Hidden)
onNotice { notice -> ProcessBuilder("notify-send", notice.level.name, notice.text).start() }
```

Your code can show its own: `notify("Recording started")`,
`notify("No battery found", NoticeLevel.WARNING)`. Which lines of the log become notices:
`noticesFromLog = { (it.area == "config" && it.isWarning) || it.isError }` (the default),
`{ false }` for none.

## 16. X11 programs

Older programs that only speak X11 (xterm, many games and Java programs, some Electron
apps) run as they are: `DISPLAY` is set for everything the compositor starts, and Xwayland
starts by itself when the first X11 program does — until then it costs nothing. If it is
not installed, the log says which package to install (`xorg-xwayland` on Arch).

Their windows are windows like any other: they tile and float, follow your rules, get the
compositor's title bars (or none, if they draw their own), effects, focus, minimize and
fullscreen, show in docks and can be shared. Menus and tooltips appear where the program
puts them. Copy and paste works both ways between X11 and Wayland programs, small text or
large.

On a scaled screen X11 programs are not shrunk or stretched: X works in the screen's real
pixels, and programs are told the real DPI (`Xft.dpi`) and cursor size, so programs that
scale their text and drawing (GTK, Qt, Electron, xterm with `-fa`) are as big and as sharp
as Wayland ones, and the cursor changes over them as it should. Very old programs with
fixed bitmap fonts stay at their pixel size — they cannot be told otherwise.

**Java programs** (Swing, AWT — most Java desktop programs) can only draw at whole scales.
On a fractional scale they get an Xwayland of their own: they draw at the next whole scale
(2 for 1.5) and are shown at exactly yours — made smaller, so sharp, the same size as
everything else, every part clickable and their menus where they belong. Nothing to set up:
the compositor knows a Java program when it connects. (IntelliJ and other JetBrains IDEs can
also run on Wayland directly: `-Dawt.toolkit.name=WLToolkit` in their VM options.)
`sharpJavaScaling = false` puts them with the other X11 programs instead, at the nearest whole
scale.

**Changing the scale while X11 programs run:** X11 programs learn the scale only when they
start, so the ones running keep theirs and are shown at the right size — sharp when the
scale goes down, a little soft when it goes up, until you start them again. X11 programs
started after the change draw at the new scale exactly.

**Wayland first.** Programs the compositor starts (`launch`, keys, `onStart`, and what you
start from its terminals) are asked to use Wayland when they can do both — GTK, Qt,
Electron and Firefox then choose it themselves and fall back to X11 if they must. A program
that still fails — it connected to the compositor, then quit with an error right away, before
showing a window — is started again through X11, and from then on that way at once; the log
says so (a command-line tool that never connected is never started twice; `x11Fallback = false`
turns this off, `x11FallbackMillis` says how soon it must fail):

```
I clients  "oldapp" quit at once on Wayland (status 1): starting it through X11. To start it that way right away: x11Programs += "oldapp"
```

To always start some programs through X11, or to leave the choice to the programs:

```kotlin
x11Programs += "steam"                  // by program name
bind("Super+G") { launch("lutris", x11 = true) }
waylandFirst = false                    // no hints; programs choose for themselves
```

What you set yourself before starting the compositor (e.g. `QT_QPA_PLATFORM`) is kept.
Some programs need a flag instead: Chromium and apps on Electron older than 28 take
`--ozone-platform-hint=auto`, Spotify `--enable-features=UseOzonePlatform --ozone-platform=wayland`. SDL 2 games run through X11, which works well;
`SDL_VIDEODRIVER=wayland` before the command makes those that can use Wayland.

Don't want X11 at all:

```kotlin
xwayland = false
```

Programs started inside read nothing (their input is empty) and, when the compositor quits,
are asked to end (`stopProgramsOnQuit = false` leaves them running). Code of your own that
must stop then — threads, programs from `spawn` — goes in `onQuit { }`.

Every program is started through one way, `dev.caulis.ffi.Processes.start(ProcessBuilder)`
(use it for programs of your own too): by default straight with the C library's posix_spawn
(`processStart = Processes.Method.NATIVE`), so starting programs keeps working when the system
updates Java while the compositor runs (Java's own way, `Processes.Method.JAVA`, then fails with
"Failed to exec spawn helper" until a restart). When Java was updated under the running
compositor, `javaUpdatedNotice` is shown once (null: only logged): restart when convenient.
A program that cannot start is logged with the real reason — "is not installed" only when it
is not there.

## 17. The cursor

Caulis has its own cursors: `"Caulis"` (sharp white arrows with a black outline, as on
Windows, the default) and `"Caulis-Black"`. Any theme installed on the system works too
(in `/usr/share/icons` or `~/.icons`). Choose the theme and the size:

```kotlin
cursorTheme = "Caulis-Black"      // or an installed theme: "Adwaita", "Breeze", "Bibata-Modern-Ice", ...
cursorSize = 32                   // 24 is usual; bigger for big screens or tired eyes
hideCursorWhileTyping = true      // it comes back when the mouse moves
hideCursorAfterSeconds = 5        // hide it when the mouse is still (0 = never)
```

Without `cursorTheme`, `XCURSOR_THEME` is used if it is set, else Caulis's own. Caulis's own
cursors also stand in for any cursor a theme lacks.

Programs show the same cursor: modern ones ask the compositor for it by name, older ones
are told the theme and size. If the theme you name is not installed, the log says so and
lists the ones that are.

## 18. Light and dark

Programs can look light or dark as the desktop says: GTK 4 and libadwaita programs, Qt 6,
Firefox, Chromium, Electron programs (VS Code, Discord, Obsidian) and others follow it.

```kotlin
colorScheme = ColorScheme.DARK          // or LIGHT; DEFAULT (the default): each program its own
```

Or let it change by itself, at fixed times or with the sun:

```kotlin
colorSchedule = ColorSchedule.Times(dark = LocalTime.of(20, 0), light = LocalTime.of(7, 0))
colorSchedule = ColorSchedule.Sun(latitude = 55.75, longitude = 37.62)   // dark from sunset to sunrise there
```

A key can switch it by hand. That lasts until the schedule's next change (or as long as the
compositor runs, without a schedule). `auto` goes back to the schedule or `colorScheme`:

```kotlin
bind("Super+Shift+D") { run("color-scheme toggle") }  // also: dark, light, default, auto
```

From outside — a script, a bar's button, a cron job — the same through the IPC socket
(step 26), and the scheme of now is in the state:

```sh
my-compositor --do color-scheme dark
my-compositor --state | jq -r .colorScheme              # dark, light or default
my-compositor --events appearance                       # {"event":"color-scheme-changed","colorScheme":"light"}
```

Your own parts (a bar, a Compose widget) can follow it as well: `colorSchemeNow` is the
scheme of now, and `onColorSchemeChanged { scheme -> ... }` runs on every change (once at
the start too, if the configuration sets one).

More settings:

```kotlin
accentColor = Color(0.21f, 0.52f, 0.89f)       // buttons and selections, where programs use it
gtkThemes = "Adwaita" to "Adwaita-dark"        // GTK 3 programs follow a theme, not the scheme
```

The same in `config.json` (step 24) and so in Lua (step 27):

```json
"appearance": {
  "colorScheme": "dark",
  "darkFrom": "20:00", "lightFrom": "07:00",
  "gtkLightTheme": "Adwaita", "gtkDarkTheme": "Adwaita-dark"
}
```

`"latitude"` and `"longitude"` instead of the two times follow the sun. In Lua,
`caulis.colorScheme()` says the scheme of now, and `caulis.on("color-scheme", function(scheme) ... end)`
hears every change.

**How programs are told.** Caulis writes the scheme where the GTK and GNOME portals read it
(`gsettings set org.gnome.desktop.interface color-scheme prefer-dark`). With
`xdg-desktop-portal-gtk` or `xdg-desktop-portal-gnome` installed, as on most systems, that is
all. Nothing is written while the scheme is `DEFAULT`, so your session's own setting stays as
it is until you choose one.

Caulis is also a portal backend of its own (the bus name
`org.freedesktop.impl.portal.desktop.caulis`, with the accent colour too). The portal uses
it once it knows it, as root, once:

```sh
printf '[portal]\nDBusName=org.freedesktop.impl.portal.desktop.caulis\nInterfaces=org.freedesktop.impl.portal.Settings;\n' \
  | sudo tee /usr/share/xdg-desktop-portal/portals/caulis.portal
```

and in `~/.config/xdg-desktop-portal/<desktopName>-portals.conf` (`caulis-portals.conf` by
default):

```ini
[preferred]
default=gtk
org.freedesktop.impl.portal.Settings=caulis;gtk
```

A nested or headless compositor (a test while you work) does not tell programs anything, so
it never changes your real desktop's look; `appearanceToPrograms = true` makes it do so
anyway. The log area `appearance` says what is told (`log("appearance", 2)`).

## 19. Locking the screen

Super+L starts the screen locker — `swaylock` unless you choose another:

```kotlin
lockCommand = "swaylock -c 1e1e2e" // or "gtklock", "hyprlock", ...
```

While the screen is locked nothing of your desktop is visible and only the locker gets the
keyboard and the mouse; it unlocks when you type your password. If the locker crashes, the
screen turns dark red and *stays locked*: switch to a text console (Ctrl+Alt+F2), log in and
start a new locker for that session — e.g. `WAYLAND_DISPLAY=wayland-1 swaylock`, with the
socket name the compositor printed when it started — then come back (Ctrl+Alt+F1 or
whichever console the session is on). Ctrl+Alt+Backspace does not quit on a locked screen
(unless `keys { quitWhileLocked = true }`): it would get round the lock. After unlocking, the
keyboard goes back to the window that had it (`unlockFocus = UnlockFocus.FOCUSED_BEFORE`, the
default; `NEWEST_BEFORE`: the newest window; JSON `"unlockFocus": "newest-before"`) — never to
a window that opened while the screen was locked.

If the locker does not show within `lockShowMillis` (3 s) — it is not installed, or it fails
at once — a red notice says the screen is *not* locked (the start already warns when
`lockCommand` is not installed), and by default the compositor locks with its own plain lock
screen instead (`lockColor`, nothing of the desktop). It checks no password: press Enter to
start the locker again; when that locker unlocks, the plain lock screen goes too. Only the
locker the compositor started ends it — any other program could lock and unlock without a
password — so if the locker cannot start at all, fix `lockCommand` from a text console
(save the configuration: it is read again at once), come back and press Enter. The same happens before sleep when the lock did not show in
`sleepLockWaitMillis`, so the computer never wakes up unlocked. `lockFailure =
LockFailure.NOTICE` only says so (and sleeps unlocked).

A lock screen of your own drawn by the compositor (no locker program):
`locker { desktop.lockInternally(myLockWidget) }` — your widget checks the password and calls
`desktop.unlockInternally()`.

Idle inhibitors (a video playing) count for nothing while the screen is locked, so a locked
screen still goes dark; `idleInhibitorsWhileLocked = true` lets them keep it on.

Something that keeps the computer awake another way than Wayland — a browser asking over D-Bus
(org.freedesktop.ScreenSaver), logind's idle inhibitors — is held with
`val held = inhibitIdle("Firefox: video-playing")` (and `held.close()` when it ends; with a `pid`
it counts only while a window of that process shows). `idleInhibitedBy` says in words what keeps
the computer awake now, for a bar. `idleInhibitors = false` ignores programs' idle-inhibit. If
something starts keeping the computer awake while it is idle already (a video that started by
itself), the idle watches hear that someone is back.

### A few more settings

```kotlin
backgroundColor = Color(0.1f, 0.1f, 0.12f)    // under everything, where no window or wallpaper is
focusStealing = FocusStealing.SAME_PROGRAM    // or ALWAYS, NEVER: who may take the keyboard by itself
focusStealingRule = { window, why -> window.appId == "mpv" }   // or decide yourself
bindingModifiers = BindingModifiers.XKB       // caps:super and the like count in bindings; PHYSICAL: the printed keys
windowListPrograms = mutableSetOf("waybar")   // only these see the window list (default: privileged programs)
permissions {                                 // as Hyprland's permissions: by the program's path or name (regular expressions)
    rule("/usr/bin/grim", "screencopy", "allow")  // features: screencopy, window-list, clipboard, panels, lock, monitors, gamma
    rule("wf-recorder", "screencopy", "ask")       // ask (screencopy only): its pictures wait for askPermission's answer
    default("clipboard", "deny")                   // no rule for a program: this (none: privilegedClients decides)
}
askPermission = { question -> question.answer(PermissionAnswer.ALLOW_WHILE_RUNNING) }   // show a card; default: no one to ask, so no
primarySelection = true                       // middle-click paste (false: not offered; at the start)
adaptiveSync = false                          // variable refresh rate on every monitor that has it (monitor("DP-1") { adaptiveSync } wins)
maxForwardedStringBytes = 1024                // titles and app ids passed on to other programs are cut here
maxRegionParts = 256                          // rectangles a program's input/opaque region keeps exactly
xwaylandRestartMillis = 1000                  // after a failed Xwayland start (doubled each time) ...
xwaylandRestartMaxMillis = 60_000             // ... up to this ...
xwaylandMaxFailedStarts = 5                   // ... and given up after this many (the action "xwayland-retry")
monitor("DP-2") { maxBpc = 10; adaptiveSync = true }   // the link: bits per colour, FreeSync
```

In config.json the same names (`"focusStealing": "never"`, `"bindingModifiers": "physical"`,
`"xwaylandRestart": { "millis": 1000, "maxMillis": 60000, "maxFailedStarts": 5 }`,
`"monitors": { "DP-2": { "maxBpc": 10, "adaptiveSync": true } }`).

## 20. Screenshots and screen sharing

**Caulis's own screenshots** (the module `io.github.aristarkhkenebas:caulis-screenshot`, if you want it in
your compositor):

```kotlin
screenshots()                                            // Print, Shift+Print, Alt+Print
screenshots { folder = Path.of("/tmp/shots"); copy = false; keys(choose = "Super+Shift+S") }
```

Print freezes the screen and shows a toolbar: *Area* (drag a part; drag it or its edges to
change it, a click without dragging takes the window there as the part), *Window* (click
one: it is taken whole, even where others cover it, with its round corners see-through),
*Screen* (click a monitor), *Pointer* (in the picture or not), *Timer* (3, 5 or 10 seconds;
the countdown is never in the picture). Over an overview (windows drawn smaller) there is
one kind more, *Windows, whole*: drag a frame over windows (Shift adds to them), click one
to add or drop it, Ctrl+A takes all of them — each is put in the picture whole and at its own
size, sharp, in the place it has on screen. Enter, Space, a double click or *Capture* takes
it; Ctrl+C only copies it; Tab, A (or R), W, S and G choose the kind, M how a part is
chosen, P the pointer, T the timer; the arrow keys move the part (Shift: by ten, Ctrl:
resize it); Escape or the right button cancels. The letters are where your keyboard layout
has them (AZERTY, Dvorak), and the tips name them. Shift+Print takes the monitor with the
pointer at once, Alt+Print the focused window; a laptop's own screenshot key opens the
chooser (`keys(selective = null)` leaves it alone). Each one is saved in
`~/Pictures/Screenshots` (your `XDG_PICTURES_DIR`) and put on the clipboard; the part chosen
last is offered again. Windows with `hiddenFromCapture = true` in a rule are black boxes in
every picture and are not taken alone.

The settings: `folder`, `fileName` (`{date}`, `{time}`, `{kind}`; a name already there gets
" (2)"), `format` (`ShotFormat.PNG` or `JPEG` with `jpegQuality`), `save`, `copy`, `notify`,
`pointer`, `delaySeconds`, `then` (a command run with each file: an editor), `onTaken`,
`onCountdown` (your own countdown), `kind` and `overviewKind` (the kind it starts with),
`selection` (`ADJUST`: a part is changed by dragging it, `NEW`: each drag a new one),
`sharpWindows`, `sharpCornerRadius`, `sharpBackground`, `sharpWallpaper` and `maxPixels` (for
pictures put together from windows). The look — colours, font, sizes, how dark the screen
gets — is `style = ScreenshotStyle(...)`, its words in your language
`ScreenshotStyle(words = ScreenshotWords(capture = "Aufnehmen", ...))`; the buttons and
their order, the timer's steps, the keys and the timings are `chooser = ChooserBehaviour(...)`
(`keys = ChooserKeys(capture = listOf("Return"), ...)`). The chooser starts choosing a part
each time; a window is chosen with its key (W) or button. `ChooserBehaviour(rememberKind = true)`
starts with the kind used last instead, `clickPicksWindow = true` makes a click (not a drag)
take the window under the pointer as the part.

With the module in the compositor, config.json and Lua have the action too: `"Print":
"screenshot"` (the chooser), `screenshot region`, `screenshot window`, `screenshot screen`,
`screenshot windows` (the chooser starting with that kind), `screenshot focused` (the
focused window at once), `screenshot monitor` (the monitor with the pointer at once).

Your own tools inside the compositor work the same way: `desktop.openOverlay(overlay)`
shows something of yours above everything, with the keyboard and the mouse, until
`desktop.closeOverlay()` (see `Overlay`).

Programs made for wlroots-style compositors work as they are too:

```kotlin
bind("Print") { launch("grim ~/Pictures/screenshot.png") }               // the whole screen
bind("Shift+Print") { launch("grim -g \"$(slurp)\" ~/Pictures/part.png") } // a part you choose
bind("Super+R") { launch("wf-recorder -f ~/Videos/recording.mp4") }      // a video
```

(`pacman -S grim slurp wf-recorder` or your distribution's packages.) OBS records with the
*wlrobs* plugin or through the portal below.

Recordings get a new picture only when something on screen changed — and only what changed
— so recording a still screen costs nothing. A recorder's own GPU buffers are filled
directly, without a copy through the processor.

**Sharing in browsers, Discord, Zoom, ...** goes through the desktop portal: install
`xdg-desktop-portal-wlr`, and tell the portal it handles Caulis
(`~/.config/xdg-desktop-portal/caulis-portals.conf`):

```ini
[preferred]
default=gtk
org.freedesktop.impl.portal.Screenshot=wlr
org.freedesktop.impl.portal.ScreenCast=wlr
```

The portals must know the compositor's `WAYLAND_DISPLAY`: on the real screen it hands its
environment to systemd and D-Bus activation by itself at the start, and restarts portals that
were started before with another one (`exportEnvironment`, `portalsToRestart`); nothing to do.

(`desktopName = "caulis"` is the name the portal looks for: `caulis-portals.conf`.) Single
windows can be shared too; a shared window keeps drawing at full speed even while it is
hidden or on another workspace. While the screen is locked, pictures show the lock and no
window is given out.

## 21. Touchpad gestures

| Gesture | What it does |
| --- | --- |
| three fingers, any direction | drag the view (a canvas, a strip of columns) right under your fingers; it settles on the nearest place when they lift |
| four fingers left / right | next / previous workspace (`workspaceSwipeFingers`) |
| four fingers pinching in / out | open / close the overview, following your fingers |

Everything else (two-finger scrolling, pinch-to-zoom in a browser or photo viewer, ...) goes
to the program under the pointer. Change it:

```kotlin
dragViewFingers = 3           // 0 = off
overviewPinchFingers = 4      // 0 = off
workspaceSwipeFingers = 4     // 0 = off
swipe(fingers = 3, Direction.UP) { desktop.toggleOverview() }
```

Gestures of your own, as they come (with their fingers, movement, scale and rotation): a
handler is asked at each gesture's start, and `true` makes the whole gesture its own — its
updates and its end go only to it, not to the built-in gestures or programs.

```kotlin
onGesture { g ->
    when (g) {
        is Gesture.SwipeBegin -> g.fingers == 5                  // five fingers: mine
        is Gesture.SwipeUpdate -> { println("moved ${g.dx}, ${g.dy}"); true }
        else -> false
    }
}
```

All input can be seen first, wlroots style — `onKey` (the key's code, its symbol's name, the
modifiers before and after), `onPointerMotion`, `onPointerButton`, `onScroll`: `true` takes
it from the compositor's keys and the programs. Take a key's release when you took its
press. On a locked screen only the laptop's special keys (XF86…) are told to `onKey`, never
what is typed into the locker.

```kotlin
onKey { key -> key.pressed && key.keysym == "F12" && key.modifiers == 0 && run { launch("foot"); true } }
onPointerMotion { dx, dy -> false }   // (true: the pointer stays where it is)
```

## 22. On the real screen

Switch to a text console (Ctrl+Alt+F3), log in, go to your project and run:

```sh
./gradlew run
```

Now your compositor is the whole desktop. On the real screen it hands its environment
(`WAYLAND_DISPLAY`, `DISPLAY`, `XDG_CURRENT_DESKTOP`, `XDG_SESSION_TYPE`) to systemd's user
services and to D-Bus by itself, so portals (screen sharing, file pickers) and notification
daemons started by them find it — no `systemctl --user import-environment` of your own
(`exportEnvironment = false` turns it off). To be safe while trying things out, let it quit
by itself after a while:

```kotlin
exitAfterSeconds = 60
```

Ctrl+Alt+Backspace quits (not on a locked screen, see `keys.quitWhileLocked`), Ctrl+Alt+F1…F12
switch consoles (unless you changed `keys.quit` and `keys.consoles`).

## 23. Your own layout

Describe it with building blocks — no arithmetic:

```kotlin
val MyLayout = tiling("Mine") {
    row {                          // parts side by side
        window(weight = 60)        // the first window, 60% of the width
        column(weight = 40) {      // parts on top of each other
            window()               // the second window
            rest()                 // all the others
        }
    }
}
```

Then `layout = MyLayout`. The blocks:

| Block | Takes |
| --- | --- |
| `row { }` / `column { }` | its parts side by side / on top of each other |
| `window()` | one window (the first `window()` gets the oldest window) |
| `rest()` | all windows not placed elsewhere, evenly |
| `share(1, 2)`, `share(2, 2)` | half of the remaining windows each (e.g. both sides of a centred master) |
| `grid()`, `dwindle()`, `spiral()` | the remaining windows in a grid / halving / spiral |
| `accordion(thin = 40)` | the remaining windows stacked, the focused one open, the others thin strips |
| `monocle()` | the remaining windows all in the same place |

`weight` is a part's share (60 and 40 = 60% and 40%). Parts without windows disappear, so
there are never empty holes; space is divided without losing a pixel, and gaps are added for
you. Inside a description, `count` (how many windows) and `focused` are known, so plain
Kotlin works: `if (count > 4) grid() else rest()`.

Need something the blocks cannot express? Implement `Layout` yourself: it gets the windows
and the free area, and returns one rectangle per window.

**Placing windows yourself, every frame** (as a wlroots compositor with its own scene does):
a `WindowDirector` says, for each window it manages, where it is drawn right now — fractions
allowed, stretched when that is not the window's own size (zoomed out, half way through your
own animation) — how see-through, how round, clipped to what, in which order, and whether it
takes the pointer. The layouts leave those windows alone; focus, menus, panels, the lock
screen, X11 programs, effects and frame timing stay the compositor's.

```kotlin
director = object : WindowDirector {
    var zoom = 1.0
    override fun initialSize(window: Window) = 800 to 600
    override fun place(window: Window, now: Long): WindowPlace {
        val r = window.visibleRect
        return WindowPlace(40.0, 60.0, r.width * zoom, r.height * zoom, cornerRadius = 12f)
    }
    override fun animating(now: Long) = false // true while your own animation runs
}
```

The size a program draws at is yours to ask for (`window.configure(width, height)`); after a
change of your own (a key, a gesture), `desktop.requestRedraw()`. `requested`,
`moveRequested` and `resizeRequested` let you answer a program's fullscreen, maximize and move
requests yourself. Where the pointer is over a stretched window, the program is told the
point in its own size.

**Your own interface in Compose** (the module `io.github.aristarkhkenebas:caulis-compose`,
with `google()` in your repositories): a bar, a dock, a launcher, a volume pill — any Compose
you like, drawn by the compositor itself (no extra program, no window), getting the pointer
where it is, the wheel, and the keyboard when it asks (text fields work). About 45 MB more for
the first one.

```kotlin
compose(place = { m -> Rect(m.area.x, m.area.y, m.area.width, 32) }, reserve = { Reserve(Edge.TOP, 32) }) { monitor ->
    var clicks by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize().background(Color(0xE01E1E2E)), verticalAlignment = Alignment.CenterVertically) {
        BasicText("  ${monitor.name}", style = TextStyle(color = Color.White))
        Spacer(Modifier.weight(1f))
        BasicText("clicked $clicks  ", Modifier.clickable { clicks++ }, style = TextStyle(color = Color.White))
    }
}
```

`reserve` keeps room at an edge (windows and panels make way); `takesKeyboard = true` gives
it the keyboard when clicked (until a click elsewhere); `level` puts it below or above the
windows. Its text fields copy and paste with the compositor's clipboard, and the pointer
takes the shape Compose asks for (a text cursor over a field, a hand over a link).

- `sceneSize = { m -> SceneSize(400, 600) }`: the scene's own size, when it is not its area's
  — a popup whose area grows and shrinks shows more or less of one scene, nothing made anew
  (a new size in pixels otherwise starts its scene over, and `remember`ed state with it).
- `appearance = { m -> WidgetLook(alpha = 0.5f, scale = 0.95f, dy = -8f, reveal = 0.6f, revealFrom = Edge.TOP) }`:
  how its picture is shown — see-through, smaller, moved, unfolding from an edge — done by
  the compositor without drawing it again; `moving = { true }` while that changes by itself
  (frames keep coming). Clicks land where it is seen.
- `onPressOutside = { x, y, onAnother -> ... }`: a press elsewhere; true takes it (a menu that
  closes, and the window below gets nothing).
- `recovery = ComposeRecovery(delayMillis = 500, notice = null, onError = { e -> ... })`: when
  its content throws (a composable, a `LaunchedEffect`, a click's handler), the error is
  logged (area `compose`), a notice says so the first time, and its scene starts over a
  moment later — sooner or later each time it fails again, never on every frame.
- `showsWhileLocked = true`: shown and used while the screen is locked too (an on-screen
  keyboard). A lock screen of your own in Compose is a widget like any other:
  `desktop.lockInternally(handle.widget!!)` (see Locking the screen).

While its `appearance` is fully see-through (`alpha = 0f`) it is laid out but not drawn, and
keeps no frames coming: a hidden menu with a spinner in it costs nothing. State that must
outlive a new size or scale (the `clicks` above, if the bar's width changes) belongs outside
the content, in your own `mutableStateOf`. Typing in its text fields uses your keyboard
layout; dead keys, the Compose key and input methods (fcitx5, ibus) do not reach them yet.

`compose(...)` gives back a handle: `close()` takes it away, `focus()` gives it the keyboard,
`prewarm(monitor, width, height)` draws it once ahead (the first drawing is slow: fonts,
classes), `restart()` starts its scenes over (content that is something else now), `widget`
is the `ComposeWidget` itself. Everything Compose runs — recomposition, animations,
`LaunchedEffect` — runs on the compositor's thread; call these there too (from a key binding
or `post { }`). On a plain `Desktop`, `ComposeWidget(desktop, post, place, ...)` with
`desktop.addWidget(...)` does the same. caulis-compose needs exactly the Compose version it
was built with (it uses Compose's own scene parts): a build that would take another fails
at once. Without Compose, a `Widget` of your own does the same with any pictures.

**Pictures of your own that take no input** — a bar, rounded screen corners, a glow — at one
of three levels (below the windows, above them, on top of everything); clicks go through to
what is below. A director can also draw with each of its windows (`decorate`: below it — a
blur of what is behind, a shadow — or above it — a border), so windows above stay above.

```kotlin
addDrawing(DrawingLevel.ON_TOP, object : Drawing {
    override fun draw(monitor: Monitor, canvas: OverlayCanvas) {
        val a = monitor.area
        canvas.border(a, 2f, Paint.solid(Color(0.4f, 0.6f, 1f)), radius = 18f)   // the screen's edge
        canvas.roundedFill(Rect(a.x + 20, a.bottom - 60, 200, 40), 12f, Color(0f, 0f, 0f, 0.5f))
    }
})
```

The canvas draws pictures (`image`), colours (`fill`, `roundedFill`, `frame`), borders and
shadows in any paint (a gradient, a shader: moving ones keep frames coming) and `blur` of what
is below. `animating(now)` true keeps frames coming for a drawing that changes by itself; after
a change of your own, `desktop.requestRedraw()`.

## 24. Settings as data: config.json

Everything in steps 4–21 can also be said in `config.json` in the compositor's folder
(`~/.config/<configName>/config.json`) — handy for settings you change often, for people
who use your compositor without writing Kotlin, and for Nix (step 25). The file goes on top
of what `Main.kt` says; what it does not mention stays as the code says.

```jsonc
{
  // Comments and trailing commas are fine.
  "layout": { "name": "master-stack", "ratio": 0.6, "gaps": { "inner": 8, "outer": 12, "top": 40 } },
  "scale": 1.25,
  "keys": { "close": "Super+W", "quit": false },          // changed, and turned off
  "bind": {
    "Super+B": "exec firefox --private-window",
    "Super+Shift+3": "move-to-workspace 3"
  },
  "rules": [ { "appId": "mpv", "floating": true, "center": true, "margin": 20 } ],
  "effects": { "preset": "modern", "cornerRadius": 12, "border": { "width": 2, "focused": { "conic": ["#89b4fa", "#f5c2e7"], "turnsPerSecond": 0.2 } } },
  "background": { "shader": "aurora.glsl" },
  "startup": [ "waybar" ]
}
```

Saved while running, it is taken on at once, like `Main.kt`. Every value is checked with the
same limits as in Kotlin, and a mistake is reported with its line — and a guess:

```
config.json, line 3: layout.gaps.inner: 0 to 500, not 1000
config.json, line 7: terminl: there is no such setting (did you mean "terminal"?)
config.json, line 9: bind."Super+B": "exce firefox" is not an action (did you mean "exec"?)
```

The other settings still apply. Check a file without starting:

```sh
my-compositor --check-config                 # its config.json
my-compositor --check-config other.json
```

**Actions** — what keys in `bind` do, written as text:

| Action | Does |
|---|---|
| `close` · `quit` · `lock` · `terminal` | the focused window, the compositor, the screen, a terminal |
| `exec <command>` | starts a program (the rest of the line) |
| `workspace <n>` · `move-to-workspace <n>` | shows a workspace / moves the focused window there |
| `focus left\|right` · `move <dir>` · `resize <dir>` · `preselect <dir>` | as the usual keys do |
| `maximize` · `fullscreen` · `minimize` · `float` | the focused window, or back |
| `overview` · `next-window` · `dump-state` | |
| `rotate` · `balance` · `column-width` · `split-beside` · `split-below` | layout commands |
| `layout <name>` · `scale <x>` · `notify <text>` | |

**Every setting**, with its limits and description, is in
[`config.schema.json`](config.schema.json). Editors (VS Code, IntelliJ, Neovim with a JSON
language server) complete and check the file as you type when it starts with:

```json
{ "$schema": "file:///path/to/caulis/docs/config.schema.json" }
```

Turn the file off with `useConfigFile = false` in `Main.kt`.

## 25. Nix

The Caulis folder and new projects have a `flake.nix`.

**Developing** — a shell with JDK 27, Xwayland and all the libraries Caulis looks up:

```sh
nix develop          # with flakes
nix-shell            # without (in the Caulis folder)
./gradlew run
```

**Building** your compositor as a package (`result/bin/my-compositor`, with a login session):

```sh
nix build .#default.mitmCache.updateScript && ./result   # once, and after changing dependencies
nix build
```

Nix builds without the network, so what Gradle downloads is listed in `deps.json` first.

**Settings in Nix.** The settings of step 24 are also Nix options — the same ones, made
from the same description, so the limits match — for Home Manager:

```nix
{
  imports = [ my-compositor.homeModules.default ];

  programs.caulis = {
    enable = true;
    settings = {
      layout = { name = "scrolling-columns"; gaps.inner = 8; };
      keys.quit = false;
      bind."Super+B" = "exec firefox";
      rules = [ { appId = "mpv"; floating = true; } ];
      background.shader = ./aurora.glsl;       # a file next to your Nix configuration
    };
    files."walls/sea.jpg" = ./sea.jpg;         # more files for the compositor's folder
  };
}
```

A mistake fails `home-manager switch` with the option's name and its limits
(`settings.layout.gaps.inner' is not of type 'integer between 0 and 500'`, or *Did you
mean `settings.effects.cornerRadius'?*); then the compositor itself checks the written file
(`--check-config`) while building. After a switch the running compositor takes the new
settings on, without restarting. For NixOS there is `nixosModules.default`: it installs the
compositor, offers its session at login, and puts the settings in
`/etc/xdg/<configName>/config.json` for everyone. The folder counts as a whole: a user with
any configuration file of their own in `~/.config/<configName>/` (`config.json`, `config.lua`,
`config.kts`) gets only theirs, and nothing of `/etc/xdg` is read for them.

Not on NixOS? GPU drivers come from the system there; to run a compositor built with Nix
on another distribution, start it through [nixGL](https://github.com/nix-community/nixGL).

## 26. Other programs: the IPC socket

Bars, docks, scripts and tools — in any language — talk to your running compositor through
its IPC socket: run the actions of step 24, ask what is on screen, hear what changes. Every
program it starts finds the socket in `$CAULIS_SOCKET`.

From a shell, your compositor's own program does it, with JSON:

```sh
my-compositor --do workspace 2
my-compositor --do exec firefox --private-window
my-compositor --do close --window 7               # a window by its id (see --state)
my-compositor --state | jq -r '.windows[].title'
my-compositor --events focus workspaces           # a line of JSON per event, until stopped
```

```json
{"event":"window-opened","window":{"id":2,"appId":"foot","title":"~","workspace":1,"monitor":"DP-2","focused":true,...}}
{"event":"focus-changed","id":2}
{"event":"workspace-shown","number":2,"monitor":"DP-2"}
```

A mistake says what is wrong (and exits with 1): `"worksapce" is not an action (did you mean
"workspace"?)`. Kinds of events: `windows`, `focus`, `workspaces`, `monitors`, `notices`,
`config`, `lock`, `keys` (key modes), `appearance` (light or dark).

Programs that run all the time (a bar) talk to the socket directly instead of starting a
program for each question. What goes over it is described in
[`caulis.proto`](../caulis-ipc/src/main/proto/caulis/ipc/v1/caulis.proto) — Protocol
Buffers, so Python, Rust, Go, C++, JavaScript, ... make their code from it with `protoc`.
[`examples/ipc`](../examples/ipc) has a Python program that follows the focused window's
title; on the JVM, `caulis-ipc` has a client:

```kotlin
CaulisIpc.connect().use { ipc ->
    ipc.run("workspace 2")
    println(ipc.state().windowsList.map { it.title })
    ipc.subscribe(EventKind.EVENT_KIND_FOCUS)
    while (true) println(ipc.nextEvent() ?: break)
}
```

- Only programs of your own user can connect.
- While the screen is locked, programs cannot move or close windows (only `exec`, `lock`,
  `notify` and `dump-state` work then).
- A program that subscribes to events but does not read them is disconnected rather than
  making the compositor keep everything for it.
- Turn the socket off with `ipc = false`.

## 27. Lua: config.lua

For the people who use your compositor, `config.json` (step 24) says *what*; `config.lua`
can also say *when* and *how* — functions on keys, on events, on timers. Add the module to
your compositor:

```kotlin
implementation("io.github.aristarkhkenebas:caulis-lua:0.1.5")
```

Then it reads `~/.config/<configName>/config.lua` by itself (after `config.json`, on top of
it). Everything goes through one table, `caulis`:

```lua
-- Everything config.json can say, with the same names and limits.
caulis.settings {
  layout = { name = "master-stack", gaps = { inner = 8, outer = 12 } },
  keys = { quit = false },
  effects = { preset = "modern", cornerRadius = 12 },
}

-- Keys: an action as text, or a function.
caulis.bind("Super+B", "exec firefox")
caulis.bind("Super+M", function()
  for _, w in ipairs(caulis.state().windows) do
    if w.appId == "mpv" then caulis.run("minimize", w.id) end
  end
end)

-- Rules, as in config.json.
caulis.rule { appId = "pavucontrol", floating = true, center = true }

-- Events: start, reload, unload, window-opened, window-closed, window-changed, focus-changed,
-- workspace-changed, monitor-added, monitor-removed, notice, lid, color-scheme, quit.
caulis.on("window-opened", function(w)
  if w.appId == "music" then caulis.run("move-to-workspace 9", w.id) end
end)
caulis.on("start", function()
  caulis.after(2000, function() caulis.notify("Welcome back") end)
end)

-- Programs: started without waiting for them. At the top of the file only once, when the
-- compositor starts (not again on each save, not for --check-config).
caulis.launch("waybar")

-- Your own files next to it: require("theme") is theme.lua there.
```

| `caulis.` | |
|---|---|
| `settings { ... }` | settings, as config.json |
| `rule { ... }` | a window rule, as in config.json's `rules` |
| `bind(keys, action [, options])` | `action`: text (`"workspace 2"`) or a function; returns a key with `unbind()` and `setEnabled(on)` |
| `on(event, fn)` | `fn(window)` for window events (`nil` when no window has the focus), `fn(number)` for workspace-changed, `fn(name)` for monitors, `fn(text, level)` for notice; returns a handle with `remove()` |
| `run(action [, id])` | runs an action, on the window with that id (else the focused one) |
| `state()` | `{ windows = {...}, monitors = {...}, focused = id, workspace = n, locked = bool }` |
| `windows()` · `window(id)` · `focusedWindow()` · `monitors()` · `workspace()` | the same, one part at a time |
| `after(ms, fn)` · `every(ms, fn)` | runs `fn` once later, or again and again; returns a handle with `cancel()` (a reload ends them all) |
| `layout(name, fn)` | a layout of your own: `fn(windows, area, focused)` returns a rectangle `{ x, y, width, height }` per window |
| `mode(name)` | a key mode on (`nil` or `"reset"`: the usual keys) |
| `colorScheme()` | what programs are told now: `"dark"`, `"light"` or `"default"` |
| `launch(command)` | starts a program (run by `/bin/sh`) without waiting for it; `os.execute` does the same |
| `notify(text [, level])` · `log(text)` · `file(name)` | a notice, a line in the log, a path in the folder |

A window is a table: `id`, `appId`, `title`, `pid`, `workspace`, `monitor`, `focused`,
`floating`, `maximized`, `fullscreen`, `minimized`, `x11`, `urgent`, `x`, `y`, `width`, `height`.

Mistakes are reported with their line, and the rest still applies:
`config.lua:12: terminl: there is no such setting (did you mean "terminal"?)`. A mistake in a
function (a key, an event) shows a notice when it happens; the desktop goes on. Code that runs
too long — a loop that never ends, or a string pattern that backtracks without end — is
stopped (half a second for a key or an event: `scriptCallLimitMillis`). Code that needs more
memory than there is (`string.rep("x", 2^30) .. ...`) is stopped too, with a notice, and
then nothing of config.lua runs until the file is read again. A Java call that blocks is
not stopped, so what surely waits is taken out of `os` and `io`: `os.execute` starts the
program and goes on, `io.popen` and `os.exit` are not there (quit with
`caulis.run("quit")`), the standard input is empty. (Reading a pipe or a terminal you open
yourself, `io.open("/some/fifo"):read()`, still waits: don't.) `require` finds only Lua
files next to config.lua. Saving
the file takes it on at once; `my-compositor --check-config config.lua` checks it without
starting. `unload` comes just before a saved file replaces the running one, to stop what the
old one started.

Try things out on the running desktop, in the same Lua state as your config.lua (its
variables are there):

```
my-compositor --repl                         # a prompt: caulis.focusedWindow().title
my-compositor --lua 'return #caulis.windows()'
```

Your editor can know the whole `caulis` table — every setting with its values, every
function: `my-compositor --print-lua-stubs > caulis.lua` (or `docs/lua/caulis.lua` of Caulis)
in a folder the Lua language server reads (`"workspace.library"` in `.luarc.json`).

If a configuration ends up with no keys at all — every usual key off, none of its own —
`Super+Shift+Escape` still quits and `Super+Return` opens a terminal, so a mistake never locks
you in.

## 28. Kotlin without a project: config.kts

Everything `Main.kt` can say — but in one file in the compositor's folder, with no Gradle and
no project: the compositor compiles it itself. Add the module (it contains the Kotlin
compiler, so it makes your compositor bigger):

```kotlin
implementation("io.github.aristarkhkenebas:caulis-script:0.1.5")
```

`~/.config/<configName>/config.kts` is then read after `config.json` and `config.lua`, as if
inside `caulis { }`, with Caulis's packages already imported:

```kotlin
layout = MasterStack(Gaps(inner = 8), ratio = 0.6)
effects = Effects.MODERN.copy(cornerRadius = 14)
bind("Super+B") { launch("firefox") }
rule { appId("mpv"); floating = true }
onWindowOpened { window ->
    if (window.appId == "music") run("move-to-workspace 9", window)
}
```

The first start after a change compiles it (a few seconds, in a Java of its own that ends
right after: the Kotlin compiler never stays in the compositor's memory); the compiled script is
kept in `~/.cache/<configName>/config-kts`, so the next start only loads it. That Java's options
and time limit: `KotlinScriptConfig.compilerJvmOptions` and `compilerTimeoutSeconds` (set them in
your `Main.kt` before `caulis { }`). Saving it while running takes
it on, like the others. A mistake is reported with its line
(`config.kts:2: Unresolved reference 'lyaout'`), and the compositor starts anyway.

Which to choose? `Main.kt` is *your* compositor — what it is. The files are what *its users*
change: `config.json` for settings, `config.lua` for a bit of behaviour, `config.kts` for
everything. With Nix (step 25) all of them can be written in the same place:
`programs.caulis.settings`, `programs.caulis.lua`, `programs.caulis.kotlinScript` — each
checked by the compositor itself while building.

## 29. Mice, touchpads, tablets, and keys that do more

**Pointers and touchpads** — every setting is optional (the device's own default otherwise):

```kotlin
input.pointers.speed = -0.3                          // -1 slowest .. 1 fastest
input.pointers.accelProfile = AccelProfile.FLAT      // as fast as the hand (games); ADAPTIVE: faster when quick
input.touchpads.naturalScroll = true                 // content follows the fingers
input.touchpads.disableWhileTyping = true
input.touchpads.clickMethod = ClickMethod.FINGERS    // two fingers pressed = right click
input.touchpads.tapButtons = TapButtons.LEFT_RIGHT_MIDDLE
input.devices["Logitech G Pro*"] = DeviceSettings().apply { speed = -0.6; scrollFactor = 2.0 }
input.devices["*Touchscreen*"] = DeviceSettings().apply { enabled = false }
focusFollowsMouse = true                             // the keyboard goes to the window under the pointer
repeatRate = 40; repeatDelay = 250                   // held keys: 40 a second, after 250 ms
```

Also: `tap`, `tapDrag`, `dragLock`, `leftHanded`, `middleEmulation`, `scrollMethod`
(`TWO_FINGER`, `EDGE`, `BUTTON` with `scrollButton`), `offWithMouse` (a touchpad that is off
while a mouse is plugged in). Device names — for `input.devices` — are listed by
`my-compositor --devices`; `*` matches anything. Changes are taken on at once. The keyboard
itself: `keyboardLayouts`, `keyboardVariants`, `keyboardOptions`, `keyboardModel`.

**Graphics tablets** (Wacom, Huion, XP-Pen, a laptop's pen screen) work out of the box. The pen
is the pointer everywhere — its tip the left button, its lower button the right one, its upper
button the middle one — so every program and the compositor's own bars and menus work with it.
Programs that understand pens (Krita, GIMP, Inkscape, Xournal++, and X11 programs through
Xwayland) get it as a pen instead: pressure, tilt, distance, rotation, the eraser end, its
buttons. A tablet covers all monitors as one; a pen screen covers the laptop's own screen. The
pad's buttons go to the program with the keyboard, or to a binding of yours:
`bind("Pad1") { run("workspace next") }` (Pad1 is the first button). The settings — for
`input.tablets`, or one tablet by name in `input.devices` — all optional:

```kotlin
input.tablets.monitor = "DP-1"                        // the monitor the tablet covers
input.tablets.keepAspect = false                      // stretch to the monitor (default: keep its shape)
input.tablets.area = TabletArea(0.0, 0.0, 0.5, 0.5)   // only the top left quarter of the tablet
input.tablets.leftHanded = true                       // the tablet turned around, buttons on the right
input.tablets.pointerOnly = true                      // the pen is only ever the pointer (no pressure for programs)
input.tablets.tipPressure = 0.2                       // press this hard (of the full pressure) to click or draw
input.tablets.lowerButton = 0x112                     // the pen's lower button is the middle one (0: none)
input.tablets.upperButton = 0x111                     // and its upper one the right one
input.devices["Wacom One*"] = DeviceSettings().apply { monitor = "HDMI-A-1" }
```

`thirdButton` is a third button on the pen (the side button by default). With `keepAspect` a
circle drawn is a circle: a strip at the tablet's right or bottom edge goes unused. In
config.json the buttons are names: `"tablets": { "monitor": "DP-1", "lowerButton": "MouseMiddle",
"area": { "width": 0.5, "height": 0.5 } }`. Pad rings and strips go to programs as they are.

**Keys that do more:**

```kotlin
bind("XF86AudioRaiseVolume", BindOptions(repeat = true, whileLocked = true)) { launch("wpctl set-volume @DEFAULT_SINK@ 5%+") }
bind("Super", BindOptions(description = "overview")) { desktop.toggleOverview() }   // Super tapped alone
bind("Super+P", BindOptions(release = true)) { launch("grim") }                        // when let go
bind("Super+WheelDown") { run("workspace next") }                                       // the wheel
bind("MouseSide") { run("workspace back") }                                             // mouse buttons: MouseLeft/Right/Middle/Side/Extra/Forward/Back, mouse:276
```

`repeat` repeats while held (at the keyboard's repeat rate), `whileLocked` works on the lock
screen too (volume, brightness), `release` runs when the key is let go. A modifier alone
(`"Super"`) is a tap: pressed and let go with nothing in between — Super+Q never counts as one.

**Key modes** ("submaps"): keys of their own, on until you leave — e.g. a resize mode:

```kotlin
bind("Super+R") { enterMode("resize") }
mode("resize") {
    bind("Left", BindOptions(repeat = true)) { run("resize left") }
    bind("Right", BindOptions(repeat = true)) { run("resize right") }
    bind("Escape") { enterMode(null) }
}
```

While a mode is on, the usual keys are off (keys not named in it go to programs); quitting and
the text consoles always work. A mode with no keys at all (`mode("quiet") {}`) turns every key of
yours off while it is on. Bars show the mode: the IPC socket tells `key-mode-changed`.

The laptop's lid: `onLid { closed -> if (closed) lock() }`.

**A laptop's own keys** work without any `bind`: volume (`XF86AudioRaiseVolume`, ...), the
microphone (`XF86AudioMicMute`), brightness (`XF86MonBrightnessUp/Down`), the touchpad
(`XF86TouchpadToggle`), music (`XF86AudioPlay/Next/Prev`) — each shown in a small pill near the
bottom of the screen. Volume uses PipeWire's `wpctl`, music `playerctl`, brightness goes
through the login session (no root, no extra program). A key of your own replaces them; a
key that does nothing yet is named in the log (`The key XF86Favorites is not bound`), so you
know what to `bind`. The same as actions: `volume +5`, `volume mute`, `mic-mute`,
`brightness -5`, `touchpad toggle`, `media play-pause`, `touchpad typing toggle` (the touchpad off
while typing — a palm on it moves nothing — or not; `input.touchpads.disableWhileTyping` sets it
from the start). Many laptops send the Windows shortcuts for some of their keys: the display key
is often Super+P, the screenshot key Super+Shift+S — `bind("Super+P") { run("displays next") }`.

**Sleep and hibernation.** Before the computer sleeps the screen is locked (so it wakes up
locked) — `lockBeforeSleep = false` to leave it. Your own steps:

```kotlin
onSleep { run("media stop") }          // before sleeping (or hibernating)
onWake { launch("notify-send 'Good morning'") }
```

On waking the monitors come back by themselves, and one plugged in or out while asleep is
found. `loginctl lock-session` locks it too.

In config.json (and Nix, and Lua) the same:

```jsonc
{
  "keyboard": { "layouts": "us,ru", "repeatRate": 40, "repeatDelay": 250 },
  "input": {
    "focusFollowsMouse": true,
    "pointers": { "speed": -0.3, "accelProfile": "flat" },
    "touchpads": { "naturalScroll": true, "clickMethod": "fingers" },
    "devices": { "Logitech*": { "scrollFactor": 2 } }
  },
  "bind": {
    "XF86AudioRaiseVolume": { "action": "exec wpctl set-volume @DEFAULT_SINK@ 5%+", "repeat": true, "locked": true },
    "Super+R": "mode resize"
  },
  "modes": { "resize": { "Left": { "action": "resize left", "repeat": true }, "Escape": "mode reset" } },
  "lid": { "close": "lock" }
}
```

```lua
caulis.bind("XF86AudioRaiseVolume", "exec wpctl set-volume @DEFAULT_SINK@ 5%+", { repeating = true, locked = true })
caulis.bind("Left", "resize left", { mode = "resize", repeating = true })
caulis.on("lid", function(closed) if closed then caulis.run("lock") end end)
```

(In Lua it is `repeating`: `repeat` is a word of the language.)

## 30. Everyday things: idle, screens, clipboard managers, games

These work by themselves — the programs that use them just do:

| | What | Programs |
|---|---|---|
| Idle | told after so long without input, and when someone is back | swayidle, hypridle |
| Keep awake | a window on screen keeps the computer from counting as idle | mpv, Firefox, VLC playing |
| Screens off and on | DPMS | swayidle, wlopm |
| Monitors arranged | see every monitor (on or off), change mode, place, scale, turn | kanshi, wlr-randr, nwg-displays, way-displays |
| Night light | warmer colours | wlsunset, gammastep |
| Clipboard managers | read and set the clipboard without a window | cliphist, clipman, wl-copy / wl-paste |
| Middle-click paste | what is selected is pasted with the middle button | GTK, Qt, terminals |
| Focus requests | a link opens the browser and brings it forward; anything else asking only marks its window urgent | xdg-activation: Firefox, Telegram, ... |
| Games, 3D | the pointer held in place or inside the window, and the mouse's own movement | pointer-constraints, relative-pointer: games, Blender, Wine |
| Drawing tablets | the pen as a pen: pressure, tilt, distance, the eraser, the pad (else as the pointer; see step 29) | tablet-v2: Krita, GIMP, Inkscape, Xwayland |

Your own say:

```kotlin
onIdle(300, resume = { run("dpms on") }) { run("dpms off") }   // screens off after 5 minutes
onIdle(600) { lock() }                                           // locked after 10
fullscreenKeepsAwake = true        // a fullscreen window keeps it awake too (videos, games)
focusOnActivate = false            // focus requests only mark windows urgent
wakeScreensOnInput = true          // a key or the mouse switches dark screens on (the default)
monitor("DP-2") { transform = Transform.ROTATE_90 }   // a screen standing on its side
```

Actions for keys and scripts: `dpms off` / `dpms on` / `dpms toggle` (all screens, or one:
`dpms off HDMI-A-1`), `keep-awake on|off|toggle` (no idle timers while on), `focus-urgent`,
`pin` (and `pin on|off`), `tag +name|-name|name`.
Windows that want attention are `urgent` on the IPC socket (bars show them). In config.json:

```json
{
  "idle": [ { "after": 300, "action": "dpms off", "resume": "dpms on" }, { "after": 600, "action": "lock" } ],
  "fullscreenKeepsAwake": true,
  "monitors": { "DP-2": { "transform": "90" } }
}
```

## 31. The start: autostart, a smooth reveal, a sound

**Programs with the session.** `autostart` starts them one after another (`stagger` apart, so
the start does not stutter), once the desktop is shown — once per session: a reload starts
only the entries it adds, and stops watching the ones it takes away (they keep running). An
entry is the same as before when it runs the same command: changing its `name`, `dir`, `env`
or `after` does not start a second copy (the change applies when it next starts), and an
entry taken away and put back later takes over its program if that still runs.

```kotlin
autostart {
    stagger = 400.milliseconds        // the default
    afterReveal = true                // after the desktop faded in (the default)
    app("waybar")
    app("nm-applet", "--indicator") {
        delay = 2.seconds                                      // waits this long more
        ifNotRunning = true                                    // not if one runs already (/proc)
        restart = OnCrash(maxTimes = 3, within = 1.minutes)    // or Always(...), Never
        after("waybar")                                        // once waybar has started (10 s at most)
        env("X", "y"); dir = "~"
    }
    script("~/.config/foo/start.sh")
    shell("sleep 1; notify-send hi")
    desktopEntries = false            // true: also the .desktop files of ~/.config/autostart
}
```

They start as `launch` starts programs (the session's environment, Wayland first, no input,
ended with the compositor while `stopProgramsOnQuit`). The action `autostart` (or
`autostartStatus()`) lists each and how it is; the log area `autostart` tells when each
started. `startLater(listOf("firefox"), dir)` puts a program of yours in the same queue
(session restore). In config.json:

```json
"autostart": { "staggerMillis": 400, "entries": [ { "app": ["waybar"] }, { "shell": "sleep 1; notify-send hi", "restart": "on-crash" } ] }
```

**A smooth start.** With `reveal` on, the screens stay black from the very first frame (no
text console, no half-drawn bar) until the compositor is warm — a few frames drawn, and what
you asked it to wait for done — then the desktop fades in. Off by default.

```kotlin
reveal {
    enabled = true
    fadeMillis = 600; maxWaitMillis = 4000; color = Color(0f, 0f, 0f)
}
whenWarm("my bar") { done -> drawMyBarOnce(); done() }   // dark until done() (or maxWait)
onReveal { notify("Welcome") }
```

**A startup sound** (off by default): presets Caulis synthesizes itself — "airy", "classic",
"glass", "soft" — or a file of yours, played by `pw-play`, `paplay` or `aplay` (or your
`player`), never on the compositor's thread — not in the headless backend (tests) unless
`whenHeadless = true`. A player still playing after `playerTimeoutMillis` (30 s) is stopped
(the next player does not start the sound over). The action `play-startup-sound` (or
`play-startup-sound glass`) plays it now, to try.

```kotlin
startupSound {
    enabled = true
    preset = "airy"                  // or file = "~/sounds/hello.ogg"
    volume = 0.8; delayMillis = 0; at = SoundMoment.REVEAL
}
```

**Faster to the first frame: a JDK AOT cache** (opt-in; JDK 25 or newer). The JVM can keep the
classes it loaded and linked in a cache file and start from it next time. A small wrapper
around your start script makes the cache on the first run (written when the compositor quits)
and uses it from then on, one per build; a cache that does not fit is only warned about:

```sh
#!/bin/sh
app=build/install/mywm
key=$({ java -version 2>&1; ls -lL --time-style=+%s "$app"/lib/*.jar; } | cksum | cut -d' ' -f1)
cache="${XDG_CACHE_HOME:-$HOME/.cache}/mywm/aot/mywm-$key.aot"
mkdir -p "${cache%/*}"
if [ -s "$cache" ]; then aot="-XX:AOTCache=$cache"; else aot="-XX:AOTCacheOutput=$cache"; fi
JAVA_OPTS="$aot $JAVA_OPTS" exec "$app/bin/mywm" "$@"
```

The log area `reveal` says when the first frame was drawn (and how long after Java started),
to compare.

## When something goes wrong

### Look in the log

The log tells what happened, in four levels: **0** nearly nothing, **1** problems (the
default), **2** what happens (windows opening and closing, the focus, layouts, monitors,
panels, the lock), **3** everything step by step. Turn it up without changing code:

```sh
CAULIS_LOG=2 ./gradlew run                       # what happens
CAULIS_LOG="1,focus=3" ./gradlew run             # only the focus in detail
CAULIS_LOG="2,wayland=3" ./gradlew run           # every Wayland message (like WAYLAND_DEBUG)
CAULIS_LOG=3 CAULIS_LOG_FILE=/tmp/caulis.log ./gradlew run   # everything, into a file
```

or in code: `logLevel = 2`, `log("windows", 3)`, `logFile = Path.of("/tmp/caulis.log")`.
The areas: `wayland`, `clients`, `windows`, `focus`, `input`, `gestures`, `layout`,
`layers`, `lock`, `docks`, `cursor`, `capture`, `clipboard`, `render`, `drm`, `session`,
`nested`, `xwayland`, `notices` (messages on screen), `reload` (changes taken on while
running), `config` (your own code) and `native`. A line looks like this:

```
22:02:17.646     1,524 I focus    Focus: Window("foot", foot)
22:02:17.647     1,524 I windows  Opened Window("foot", foot) (workspace 1, tiled)
```

(time, seconds since the start, **E**rror / **W**arning / **I**nfo / **T**race, area, what).

**Super+F12** writes the whole state to the log: every workspace and window with its place,
the focus, panels, the lock, the cursor — the first thing to look at when windows behave
oddly. (`dumpState()` does the same from your code.) And if the compositor stops with an
error, its report ends with the last events before it, even when the log level was low.

### Stutters and memory

Everything the compositor does runs on one loop: while one thing takes long, no input is
handled and no frame drawn — the pointer stands still. `CAULIS_LOG=native=2` names each thing
that took 50 ms or more (`stallReportMillis`; 0: not watched), with the code it came from,
and 30 s after the start the longest of the start:

```
20:39:40.520    13,031 I native   The main loop was busy for 268 ms: a timer (newm.Warmup)
20:40:04.663    37,174 I native   The first 30 s: the main loop was busy at most 371 ms: a timer (newm.Warmup)
```

The cursor on a monitor's cursor plane moves the moment the pointer does, before anything
else the move causes (`quickCursorMoves`, on). Memory the C library freed (Skia, the GPU
driver, fonts) is given back after the start and every `trimMemoryEveryMinutes` (10;
`trimMemory`), and the compositor uses at most `mallocPools` (2) of glibc's malloc pools.
Compose widgets (bars, menus) are composed on the loop but drawn into pixels on
`widgetThreads` (2) threads of their own (0: on the loop). In config.json they are in
`"performance": { "stallReportMillis", "trimMemory", "trimMemoryEveryMinutes", "mallocPools",
"quickCursor", "widgetThreads" }`.

Keys are written at level 3 of `input` (as key numbers), never while the screen is locked;
the Wayland log never shows which key was pressed. Still, a level 3 log of `input` records
what you type — do not share one in which you typed a password.

### Messages

| Message | What it means |
| --- | --- |
| `... compiled by a more recent version of the Java Runtime` | Your `java` is older than 26 (Caulis is Java 26 bytecode). Run with `JAVA_HOME` pointing to a JDK 26 or newer (27 builds it). |
| `Could not open the seat` | Running on the screen needs a text console (Ctrl+Alt+F3) of a logged-in user. |
| `"xyz" could not be started: the program was not found` | `launch("xyz")`: that program is not installed. |
| `Do not run ... as root` | Start it as your normal user — a compositor never needs root. |
| `The GPU driver lacks ...` | The graphics driver is too old or missing. |
| `Xwayland could not be started` | X11 programs need Xwayland: install it (`xorg-xwayland` on Arch, `xwayland` elsewhere). |

## Where next

- `examples/` in the Caulis repository: `minimal`, `minimal-java`, `custom-layout`,
  `window-rules`
- `caulis-tiling/src/main/kotlin/dev/caulis/tiling/Layouts.kt`: every built-in layout is a
  few lines of the same building blocks — good examples for your own
- The API documentation: run `./gradlew dokkaGenerate` in the Caulis folder and open
  `build/dokka/html/index.html`
