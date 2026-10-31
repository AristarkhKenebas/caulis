# My kiosk

A Wayland compositor made with Caulis that shows one program, on the whole screen, and nothing else.

## Run

```sh
KIOSK_PROGRAM="firefox --kiosk https://example.org" ./gradlew run
```

Or set the program in `src/main/kotlin/Main.kt` (`theProgram`). Without either it shows a
terminal. Inside your desktop it opens as a window; from a text console (Ctrl+Alt+F3) it takes
the whole screen. If the program ends, it starts again.

## Before it stands in public

While you try it, Ctrl+Alt+Backspace quits and Ctrl+Alt+F1..F12 go to text consoles. For the
real thing, remove the `//` before `quit = null` and `consoles = null` in `Main.kt`: then nothing
on the keyboard leaves the kiosk. Start it from your system (a service or auto-login), and give
it a user who may do nothing else.

## Build a program to share

```sh
./gradlew installDist
build/install/my-compositor/bin/my-compositor
```
