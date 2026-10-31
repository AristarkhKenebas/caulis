# My scrolling compositor

A Wayland compositor made with Caulis, in the manner of niri: columns on an endless strip.

## Run

```sh
./gradlew run
```

Inside your desktop it opens as a window; from a text console (Ctrl+Alt+F3) it takes the
whole screen. Ctrl+Alt+Backspace quits (not while the screen is locked).

## Keys

| Keys | What they do |
|---|---|
| Super+Return, Super+D | a terminal, the launcher (`fuzzel`; change it in `Main.kt`) |
| Super+Left, Super+Right, Super+wheel | along the strip |
| Super+Up, Super+Down | within a column |
| Super+Shift+arrows | move the window or column |
| Super+Alt+Left, Super+Alt+Right | take the window into the column beside, or out of it |
| Super+W, Super+Ctrl+arrows | the column's next width, a size step |
| Super+C | the focused column to the middle |
| Super+1..9, Super+Shift+1..9 | go to a workspace, move the window there |

## Change it

Everything is in `src/main/kotlin/Main.kt`, with notes. The guide: `docs/getting-started.md` in
the Caulis folder.

## Build a program to share

```sh
./gradlew installDist
build/install/my-compositor/bin/my-compositor
```
