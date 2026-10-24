# irisWM

A Wayland compositor for Linux, written in Kotlin with the [Caulis](../../README.md) toolkit.

Status: early development. For now irisWM runs as a window inside your current Wayland
session (GNOME, KDE, Sway, ...); running directly on the screen comes next.

## Requirements

- Linux with a GPU driver that supports OpenGL ES 3 (Mesa: AMD, Intel; NVIDIA 545+)
- A Wayland session to run inside
- Libraries (usually installed already): `libEGL`, `libGLESv2`, `libgbm`, `libdrm`,
  `libxkbcommon`
- JDK 27. If it is not installed, Gradle downloads it automatically for building;
  to run the program itself, a JDK 27 must be installed (see below).

## Build

```sh
./gradlew installDist
```

The program ends up in `build/install/iriswm`.

While irisWM lives inside the Caulis repository, the build uses the Caulis sources from
`../..` automatically — nothing to install first.

## Run

```sh
build/install/iriswm/bin/iriswm
```

or simply `./gradlew run`. irisWM prints the socket name to use, for example `wayland-1`;
start programs inside it with:

```sh
WAYLAND_DISPLAY=wayland-1 foot
```

If your default `java` is older than 27, point `JAVA_HOME` at a JDK 27:

```sh
JAVA_HOME=/usr/lib/jvm/java-27-openjdk build/install/iriswm/bin/iriswm
```

## Project layout

| Path | What it is |
| --- | --- |
| `src/main/kotlin/dev/iriswm` | irisWM itself |
| `build.gradle.kts` | how irisWM is built (Kotlin, JDK version, program settings) |
| `settings.gradle.kts` | project name, where libraries come from |
| `gradle/libs.versions.toml` | every version used, in one place |
| `gradle/wrapper`, `gradlew` | the Gradle version this project is built with (downloaded automatically) |

## License

[MPL-2.0](LICENSE)
