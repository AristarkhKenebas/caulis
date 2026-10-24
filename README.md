# Caulis

A toolkit for writing Wayland compositors in Kotlin and Java.

irisWM is the compositor built with it (in `apps/iriswm`).

Status: early development.

## Requirements

- JDK 27
- Linux

## Build

```sh
./gradlew build
```

## Try it

irisWM can run as a window inside your current Wayland session:

```sh
./gradlew :apps:iriswm:installDist
apps/iriswm/build/install/iriswm/bin/iriswm
```

It prints the socket name to use, for example `wayland-1`; start programs inside it with:

```sh
WAYLAND_DISPLAY=wayland-1 foot
```

If your default `java` is older than 27, run it with
`JAVA_HOME=/usr/lib/jvm/java-27-openjdk apps/iriswm/build/install/iriswm/bin/iriswm`.

## License

[MPL-2.0](LICENSE)
