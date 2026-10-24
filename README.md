# Tessera

A toolkit for writing Wayland compositors in Kotlin and Java.

Status: early development.

## Requirements

- JDK 27
- Linux

## Build

```sh
./gradlew build
```

## Try it

Tessera can run as a window inside your current Wayland session:

```sh
./gradlew :apps:tessera:installDist
apps/tessera/build/install/tessera/bin/tessera
```

It prints the socket name to use, for example `wayland-1`; start programs inside it with:

```sh
WAYLAND_DISPLAY=wayland-1 foot
```

If your default `java` is older than 27, run it with
`JAVA_HOME=/usr/lib/jvm/java-27-openjdk apps/tessera/build/install/tessera/bin/tessera`.

## License

[MPL-2.0](LICENSE)
