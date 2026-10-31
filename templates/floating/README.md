# My floating compositor

A Wayland compositor made with Caulis, in the manner of Openbox: windows stay where you put them.

## Run

```sh
./gradlew run
```

Inside your desktop it opens as a window; from a text console (Ctrl+Alt+F3) it takes the
whole screen. Ctrl+Alt+Backspace quits (not while the screen is locked).

## Keys and mouse

| | What it does |
|---|---|
| The title bar | drag: move; its buttons: minimize, maximize, close |
| Alt + left drag, Alt + right drag | move, resize a window from anywhere in it |
| Alt+Tab | the next window |
| Alt+F4 | close the window |
| Super+Up, Super+Down | maximize, minimize |
| Super+Left, Super+Right | the window that way |
| Super+Return, Super+D | a terminal, the launcher (`fuzzel`; change it in `Main.kt`) |
| Super+1..9, Super+Shift+1..9 | go to a workspace, move the window there |

## Change it

Everything is in `src/main/kotlin/Main.kt`, with notes. Keys, rules and events are explained in
the Caulis guide (`docs/getting-started.md` in the Caulis folder).

## Build a program to share

```sh
./gradlew installDist
build/install/my-compositor/bin/my-compositor
```
