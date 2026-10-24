# Your first compositor

This guide takes you from nothing to a working Wayland compositor of your own: windows,
tiling, workspaces, your own keys. No knowledge of Wayland or graphics is needed — if you
can write a bit of Kotlin (or Java), you can follow along.

## What you need

- Linux with a Wayland session (GNOME, KDE, Sway, ...) to try things in
- A GPU driver with OpenGL ES 3 — on almost every Linux with Mesa (AMD, Intel) or NVIDIA 545+
- JDK 27 (Gradle can also download it for you)
- A terminal program for your compositor to open, e.g. [foot](https://codeberg.org/dnkl/foot)

## 1. Get Caulis

Caulis is not on Maven Central yet, so you build against a copy of its sources:

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

Then skip to step 3. If you prefer to see every piece, make a folder with three files:

`settings.gradle.kts`:

```kotlin
rootProject.name = "my-compositor"

plugins {
    // Downloads JDK 27 if it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// Use the Caulis sources from step 1 (change the path if you put them elsewhere).
includeBuild("../caulis")
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
    implementation("dev.caulis:caulis-wm:0.1.0-SNAPSHOT")
}

application {
    mainClass = "MainKt"
    // Caulis talks to C libraries (graphics, input) and needs this permission.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC")
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

## 4. Keys

Without writing anything, you already have the usual keys:

| Keys | What they do |
| --- | --- |
| Super+Return | open the terminal (`terminal = "foot"` changes which) |
| Super+Q | close the window |
| Super+F12 | write the state (windows, focus, panels) to the log — see *When something goes wrong* |
| Super+L | lock the screen (`lockCommand = "swaylock"` changes the locker) |
| Super+Up / Super+F / Super+Down | maximize / fullscreen / minimize |
| Super+V | take the window out of the layout (floating) and back |
| Alt+Tab | next window (also brings minimized ones back) |
| Super+Tab | overview: the whole workspace zoomed out — click a window to go there, Escape to go back |
| Super+Left / Super+Right | focus the neighbour |
| Super+1 … 9 | go to a workspace |
| Super+Shift+1 … 9 | move the window to a workspace |
| Super + drag | move (left button) or resize (right button) a window |
| Ctrl+Alt+Backspace | quit — always, even on a locked screen: you can never lock yourself in |

Add your own, or replace these, with `bind`:

```kotlin
bind("Super+B") { launch("firefox") }
bind("Super+Shift+Q") { quit() }
```

Turn the defaults off with `useDefaultBindings = false`.

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

Windows glide to their new places instead of jumping (`animationMillis` on the desktop sets
how long; 0 turns it off).

## 6. Rules and events

```kotlin
// These float above the tiled windows instead of taking a tile:
floatWhen { it.appId == "pavucontrol" }

// React to what happens:
onWindowOpened { window ->
    if (window.appId == "firefox") desktop.moveToWorkspace(window, 2)
}
onWindowClosed { window -> println("Closed ${window.title}") }
onWorkspaceChanged { number -> println("Workspace $number") }
```

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

Docks and taskbars (Waybar's taskbar, nwg-dock, sfwbar, or your own) see every window and
can activate, minimize, maximize and close it; a window minimized from a dock flies into
its icon there and comes back out of it. How fast windows move, minimize and zoom is
`animationMillis = 220` (0 turns the animations off).

A bar that reserves space (as Waybar does) keeps it free: tiled and maximized windows stay
next to it, never under it. `desktop.usableArea` is what is left for windows. A launcher gets
the keyboard while it is open; fullscreen windows cover bars, but not notifications.

## 9. Round corners, borders, shadows, blur

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

Panels get no effects unless you ask, by the name they give themselves:

```kotlin
panelEffects { if (it.namespace == "waybar") Effects(blur = Blur()) else null }
```

Fullscreen windows never get effects. The names come from `dev.caulis.core` (`Effects`,
`Border`, `Shadow`, `Blur`) and `dev.caulis.render.Color`; IntelliJ adds the imports when you
press Alt+Enter on a red name.

## 10. Bigger or smaller: the scale

```kotlin
scale = 1.25 // or 1.5, 2.0 ... — fractions are fine
```

Everything gets bigger by that much, and stays sharp: programs are told the scale and draw
at exactly that size (foot, GTK 4, Qt 6, Firefox, Chromium do; older ones draw at the next
whole scale and are shrunk a little). The cursor is loaded in its scaled size, windows tile
without gaps or overlaps at any fraction, and screenshots have the screen's full
resolution. The scale can be changed while running.

## 11. The cursor

The template comes with the Bibata cursor. Choose another theme and size:

```kotlin
cursorTheme = "Bibata-Modern-Ice" // white; "Bibata-Modern-Amber"; or any installed theme: "Adwaita", ...
cursorSize = 32                   // 24 is usual; bigger for big screens or tired eyes
hideCursorWhileTyping = true      // it comes back when the mouse moves
hideCursorAfterSeconds = 5        // hide it when the mouse is still (0 = never)
```

Caulis has its own cursors too: `"Caulis"` (sharp white arrows with a black outline, as on
Windows) and `"Caulis-Black"`. They are used when no other theme is chosen — e.g. without
`caulis-cursor-bibata` in `build.gradle.kts` (Bibata is GPL-3.0; remove that line if your
compositor must not contain GPL files) — and they stand in for any cursor a theme lacks.

Programs show the same cursor: modern ones ask the compositor for it by name, older ones
are told the theme and size. If the theme you name is not installed, the log says so and
lists the ones that are.

## 12. Locking the screen

Super+L starts the screen locker — `swaylock` unless you choose another:

```kotlin
lockCommand = "swaylock -c 1e1e2e" // or "gtklock", "hyprlock", ...
```

While the screen is locked nothing of your desktop is visible and only the locker gets the
keyboard and the mouse; it unlocks when you type your password. If the locker crashes, the
screen turns dark red and *stays locked*: switch to a text console (Ctrl+Alt+F2), log in and
start a new locker for that session — e.g. `WAYLAND_DISPLAY=wayland-1 swaylock`, with the
socket name the compositor printed when it started — then come back (Ctrl+Alt+F1 or
whichever console the session is on). Ctrl+Alt+Backspace still quits the compositor, as
always.

## 13. Screenshots and screen sharing

Programs made for wlroots-style compositors work as they are:

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

and start the compositor's programs knowing about it:

```kotlin
onStart {
    launch("dbus-update-activation-environment --systemd WAYLAND_DISPLAY XDG_CURRENT_DESKTOP")
}
```

(`desktopName = "caulis"` is the name the portal looks for: `caulis-portals.conf`.) Single
windows can be shared too; a shared window keeps drawing at full speed even while it is
hidden or on another workspace. While the screen is locked, pictures show the lock and no
window is given out.

## 14. Touchpad gestures

| Gesture | What it does |
| --- | --- |
| three fingers, any direction | drag the view (a canvas, a strip of columns) right under your fingers; it settles on the nearest place when they lift |
| four fingers left / right | next / previous workspace |
| four fingers pinching in / out | open / close the overview, following your fingers |

Everything else (two-finger scrolling, pinch-to-zoom in a browser or photo viewer, ...) goes
to the program under the pointer. Change it:

```kotlin
dragViewFingers = 3           // 0 = off
overviewPinchFingers = 4      // 0 = off
swipe(fingers = 3, Direction.UP) { desktop.toggleOverview() }
```

## 15. On the real screen

Switch to a text console (Ctrl+Alt+F3), log in, go to your project and run:

```sh
./gradlew run
```

Now your compositor is the whole desktop. To be safe while trying things out, let it quit
by itself after a while:

```kotlin
exitAfterSeconds = 60
```

Ctrl+Alt+Backspace quits at any time, Ctrl+Alt+F1…F12 switch consoles.

## 16. Your own layout

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
The areas: `wayland`, `clients`, `windows`, `focus`, `input`, `gestures`, `layout`, `layers`,
`lock`, `docks`, `cursor`, `render`, `drm`, `session`, `nested`, `config` (your own code),
`native`. A line looks like this:

```
22:02:17.646     1,524 I focus    Focus: Window("foot", foot)
22:02:17.647     1,524 I windows  Opened Window("foot", foot) (workspace 1, tiled)
```

(time, seconds since the start, **E**rror / **W**arning / **I**nfo / **T**race, area, what).

**Super+F12** writes the whole state to the log: every workspace and window with its place,
the focus, panels, the lock, the cursor — the first thing to look at when windows behave
oddly. (`dumpState()` does the same from your code.) And if the compositor stops with an
error, its report ends with the last events before it, even when the log level was low.

Keys are written at level 3 of `input` (as key numbers), never while the screen is locked;
the Wayland log never shows which key was pressed. Still, a level 3 log of `input` records
what you type — do not share one in which you typed a password.

### Messages

| Message | What it means |
| --- | --- |
| `... compiled by a more recent version of the Java Runtime` | Your `java` is older than 27. Run with `JAVA_HOME` pointing to a JDK 27. |
| `Could not open the seat` | Running on the screen needs a text console (Ctrl+Alt+F3) of a logged-in user. |
| `"xyz" could not be started: the program was not found` | `launch("xyz")`: that program is not installed. |
| `Do not run ... as root` | Start it as your normal user — a compositor never needs root. |
| `The GPU driver lacks ...` | The graphics driver is too old or missing. |

## Where next

- `examples/` in the Caulis repository: `minimal`, `minimal-java`, `custom-layout`,
  `window-rules`
- `caulis-tiling/src/main/kotlin/dev/caulis/tiling/Layouts.kt`: every built-in layout is a
  few lines of the same building blocks — good examples for your own
- The API documentation: run `./gradlew dokkaGenerate` in the Caulis folder and open
  `build/dokka/html/index.html`
