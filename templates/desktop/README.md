# My desktop

A Wayland compositor made with Caulis, with tiled windows and its own bar in Compose: workspaces
you can click, the window's title and a clock, drawn by the compositor itself.

## Run

```sh
./gradlew run
```

Inside your desktop it opens as a window; from a text console (Ctrl+Alt+F3) it takes the
whole screen. Ctrl+Alt+Backspace quits (not while the screen is locked).

## Change it

Everything is in `src/main/kotlin/Main.kt`, with notes; the bar is the function `Bar`, plain
Compose: add a battery, a volume, a menu. More in the Caulis guide
(`docs/getting-started.md` in the Caulis folder, "Your own interface in Compose").

Building with Nix: this template uses more libraries than the starter, so refresh `deps.json`
once (see the top of `package.nix`).

## Build a program to share

```sh
./gradlew installDist
build/install/my-compositor/bin/my-compositor
```
