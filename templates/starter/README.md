# My compositor

A Wayland compositor made with Caulis.

## Run

```sh
./gradlew run
```

Inside your desktop it opens as a window; from a text console (Ctrl+Alt+F3) it takes the
whole screen. Ctrl+Alt+Backspace quits at any time.

## Change it

Everything is in `src/main/kotlin/Main.kt`. Keys, layouts, rules and events are explained
in the Caulis guide (`docs/getting-started.md` in the Caulis folder).

## Build a program to share

```sh
./gradlew installDist
build/install/my-compositor/bin/my-compositor
```
