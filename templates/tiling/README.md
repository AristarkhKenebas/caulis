# My tiling compositor

A Wayland compositor made with Caulis, in the manner of i3: you choose where the next window goes.

## Run

```sh
./gradlew run
```

Inside your desktop it opens as a window; from a text console (Ctrl+Alt+F3) it takes the
whole screen. Ctrl+Alt+Backspace quits (not while the screen is locked).

## Keys

| Keys | What they do |
|---|---|
| Super+Return | a terminal |
| Super+D | the launcher (`fuzzel`; change it in `Main.kt`) |
| Super+arrows, Super+H/J/K/L | go to the window that way |
| Super+Shift+arrows, Super+Shift+H/J/K/L | move the window that way |
| Super+B, Super+V | the next window opens beside, below |
| Super+R | resize mode: arrows; Escape leaves |
| Super+Ctrl+arrows | resize, without the mode |
| Super+1..9, Super+Shift+1..9 | go to a workspace, move the window there |
| Super+F, Super+Shift+Space | fullscreen, floating |
| Super+Shift+Q | close the window |

## Change it

Everything is in `src/main/kotlin/Main.kt`, with notes. Keys, layouts, rules and events are
explained in the Caulis guide (`docs/getting-started.md` in the Caulis folder).

## Build a program to share

```sh
./gradlew installDist
build/install/my-compositor/bin/my-compositor
```
