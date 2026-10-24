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

Make a folder with three files.

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
import dev.caulis.core.layout.Dwindle
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
| Super+Up / Super+F / Super+Down | maximize / fullscreen / minimize |
| Super+V | take the window out of the layout (floating) and back |
| Alt+Tab | next window (also brings minimized ones back) |
| Super+Left / Super+Right | focus the neighbour |
| Super+1 … 9 | go to a workspace |
| Super+Shift+1 … 9 | move the window to a workspace |
| Super + drag | move (left button) or resize (right button) a window |
| Ctrl+Alt+Backspace | quit — always, you can never lock yourself in |

Add your own, or replace these, with `bind`:

```kotlin
bind("Super+B") { launch("firefox") }
bind("Super+Shift+Q") { quit() }
```

Turn the defaults off with `useDefaultBindings = false`.

## 5. Layouts

```kotlin
layout = Floating          // no tiling: windows go where you put them
layout = Dwindle()         // halving, like Hyprland
layout = MasterStack()     // one big window left, the rest stacked right
layout = Scrolling()       // an endless strip of columns, like niri or newm
layout = Dwindle(Gaps(inner = 8, outer = 12))   // with space between windows
```

You can change it while running, e.g. on a key:

```kotlin
bind("Super+T") { layout = if (layout === Floating) Dwindle() else Floating }
```

Windows move to their new places right away.

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

## 7. On the real screen

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

## 8. Your own layout

A layout gets the windows and the free area, and returns one rectangle per window. Equal
columns:

```kotlin
object Columns : Layout {
    override val name = "Columns"
    override fun arrange(windows: List<LayoutWindow>, area: Rect, focused: Int) =
        Split.evenly(area, windows.size, gap = 10, vertical = false)
}
```

`Split` divides space without losing a pixel. Then `layout = Columns`.

## When something goes wrong

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
- The API documentation: run `./gradlew dokkaGenerate` in the Caulis folder and open
  `build/dokka/html/index.html`
