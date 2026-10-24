# Caulis

A toolkit for writing Wayland compositors in Kotlin and Java.

irisWM is the compositor built with it (in `apps/iriswm`, a separate project).

Status: early development.

## Requirements

- JDK 27
- Linux

## Build

```sh
./gradlew build
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
