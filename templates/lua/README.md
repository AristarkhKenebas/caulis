# My Lua compositor

A Wayland compositor made with Caulis that you change in Lua alone: no Kotlin, no rebuilding.

## Run

```sh
./gradlew run
```

Inside your desktop it opens as a window; from a text console (Ctrl+Alt+F3) it takes the
whole screen. Ctrl+Alt+Backspace quits (not while the screen is locked).

The first start writes `~/.config/my-compositor/config.lua`. That file is your compositor:
save it, and the change is taken on at once. A mistake shows up on the screen with its line.

## Change it

The notes in `config.lua` say what each part does; the rest is in the Caulis guide
(`docs/getting-started.md` in the Caulis folder, step 27). Check a file without running it:
`build/install/my-compositor/bin/my-compositor --check-config config.lua`.

Building with Nix: this template uses more libraries than the starter, so refresh `deps.json`
once (see the top of `package.nix`).

## Build a program to share

```sh
./gradlew installDist
build/install/my-compositor/bin/my-compositor
```
