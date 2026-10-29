# Changelog

All notable changes to Caulis are written down here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/) (while the version starts with 0, a minor or even a
patch release may change the API; such changes are marked **Breaking** below).

Caulis is published as `io.github.aristarkhkenebas:caulis-*`. 0.1.0 to 0.1.2 are on Maven
Central; from 0.1.3 on every release is also in Caulis' own Maven repository on GitHub Pages,
`https://aristarkhkenebas.github.io/caulis/maven` (the same coordinates and signatures), because
Central's free monthly limit can be used up and a new version may reach it later.

The entries up to 0.1.4 were written afterwards from the Git history and the release notes;
0.1.0 and 0.1.1 have no Git tags, so their exact contents are a best reconstruction.

## [Unreleased]

## [0.1.5] - 2026-10-29

Work in progress, not described yet. Every change people would notice gets a line here
in the same change.

### Added
- `inhibitIdle(reason, pid)`: keeps the computer from counting as idle (no `onIdle`, no
  ext-idle-notify "idled") for inhibitors that come another way than Wayland — D-Bus, logind;
  with a pid only while a window of that process shows. `idleInhibitedBy` says in words what
  keeps the computer awake now (for a bar). `idleInhibitors = false` (config.json too) ignores
  programs' idle-inhibit. Underneath: `Idle.hold`, `Idle.inhibitedBy`, `Idle.protocolInhibitors`.
- `capsLockOn` and `keyText(evdevCode, shift, layout)` (the character a key types in a layout:
  an on-screen keyboard's labels); `Seat.capsLock`, `Seat.textIn`.
- `DBusConnection.open(loop, address)`: a bus at an address of your own (a test's private bus).
- Permissions by program, as Hyprland's: `permissions { rule("/usr/bin/grim", "screencopy", "allow"); default("clipboard", "deny") }`
  (config.json `permissions { rules, defaults }`) for the features `screencopy`, `window-list`,
  `clipboard`, `panels`, `lock`, `monitors`, `gamma`; `"ask"` (screencopy) holds a program's
  pictures until `askPermission` answers. `Display.mayUse(client, feature)` and
  `addPrivilegedGlobal(type, version, feature)` underneath; `ScreenCapture.asking`.
- `primarySelection` (middle-click paste, on by default) and `adaptiveSync` (VRR on every monitor
  without a setting of its own).
- `addRule { }` / `removeRule(rule)` and `addPanelRule { }` / `removePanelRule(rule)`: rules a
  configuration read again can replace.

### Fixed
- An idle-inhibitor (a video) that began while the computer already counted as idle was not
  heard until someone touched a key: idle watches now hear "someone is back" as soon as
  something keeps the computer awake (as wlroots does).

## [0.1.4] - 2026-10-29

### Added
- Caulis' own Maven repository on GitHub Pages, `https://aristarkhkenebas.github.io/caulis/maven`,
  with every release from 0.1.3 on. Add it next to `mavenCentral()` (README and the getting-started
  guide show how, limited to the `io.github.aristarkhkenebas` group). The project template uses it.
- `dev.caulis.ffi.Processes`: one way to start programs, by default straight through the C
  library's `posix_spawn` (`processStart = Processes.Method.NATIVE`; `Method.JAVA` is Java's own way).
  Use it for programs of your own too.
- `javaUpdatedNotice`: a notice, shown once, when the system updated Java under the running
  compositor (null: only logged). Restart when convenient.
- The watchdog says when a stall was a garbage-collection or swap pause.
- The publish scripts know Central's limits (`ci/publish-central.sh`) and publish to GitHub Pages
  (`ci/publish-github-pages.sh`).

### Changed
- Programs are started without Java's spawn helper, so starting programs keeps working after a
  JDK upgrade while the compositor runs (before, every start failed with "Failed to exec spawn
  helper" until a restart).
- A program that cannot start is logged with the real reason; "is not installed" only when it
  really is not there.

### Fixed
- No errors from event handlers while the compositor quits.

## [0.1.3] - 2026-10-29

### Changed
- `config.kts` (`caulis-script`) is compiled in a short-lived Java of its own, only when the
  script changed; the Kotlin compiler never stays in the compositor's memory. Its options and time
  limit: `KotlinScriptConfig.compilerJvmOptions` and `compilerTimeoutSeconds`.
- Lower memory use and less CPU while idle.
- The screenshot chooser starts on choosing a part each time; `ChooserBehaviour(rememberKind = true)`
  brings back the last kind, `clickPicksWindow = true` lets a click take the window under the pointer.

### Fixed
- **The keymap sent to programs (`wl_keyboard` keymap fd) was broken, so some programs, terminals
  in particular, could not start.** Update if you are on 0.1.2.
- Key bindings that stayed pressed ("stuck") after their keys were released.
- Two monitors with different sizes and scales.
- In the headless backend (tests), Caulis no longer takes over the desktop's notifications.

## [0.1.2] - 2026-10-27

**Breaking:** the API changed in binary-incompatible ways (new settings and parameters), so
programs built against 0.1.1 must be rebuilt against 0.1.2. Animations are now **off by default**
(before, windows glided to new places): turn them on with `animations { enabled = true }` (see
the animations section of the getting-started guide). Version 0.2.0 was prepared in the sources
for a while but never published; this release is 0.1.2.

### Added
- Hyprland-style animations, each by name (`windowsIn`, `windowsMove`, `workspaces`, `overview`,
  `minimize`, fades, ...) with styles, Bézier and spring curves; in Kotlin, Java, config.json and Lua.
- Autostart of programs, a smooth startup reveal (the desktop fades in once ready), an optional
  startup sound (built-in presets or a file), and notes on using a JDK AOT cache for a faster start.
- Special workspaces (Hyprland's `special`): toggle, move to and out of them, rules to open there.
- Border gradients and shaders.
- Drag and drop between programs.
- A focus-stealing gate: programs cannot take the focus whenever they like.
- An internal lock screen in the compositor, with hardening of the lock (nothing visible or usable
  but the lock).
- Starting programs: `spawn`, the session bus (`sessionBus`), `programEnvironment` for programs
  started some other way, and the environment handed to systemd and D-Bus on a real screen
  (`XDG_SESSION_TYPE`, `XDG_CURRENT_DESKTOP`).
- Keyboard: layouts change while running, a layout API and action, layout switches on keys the
  compositor takes, shortcuts by Latin keys on any layout, Num Lock, `applySettings`.
- Touch screens (`wl_touch`) and modal dialogs (`xdg-dialog-v1`, X11 `_NET_WM_STATE_MODAL`).
- Clipboard: data in several formats, reading the clipboard as bytes.
- Screenshots (`caulis-screenshot`): a redesigned chooser with a toolbar and tips, selection mode,
  whole windows over an overview, the wallpaper behind windows, sharp pictures put together from
  windows, file names, JPEG, a command to run afterwards, a countdown, its own words and keys.
- Notices: cards, a copy button, buttons of your own, calmer look; they take clicks before widgets.
- Widgets hear presses outside them (menus can close); `ComposeWidget` scenes bigger than their area,
  appearance animated by the compositor, prewarming.
- A wallpaper drawing level, prompt frames for windows waiting for a size, `Window.caughtUp`.
- For tests: simulated pointer and key input (`simulatePointer`, `simulateMotion`, simulated keys).
- A colour palette, log throttling, a hook for your own locker, a `fullRedraw` switch.
- Monitors are named in `xdg-output` (Qt picks the right cursors).

### Changed
- The desktop is usable from the first monitor on.
- Focus follows the mouse only while no widget has the keyboard.

### Fixed
- Many fixes from code review: clipboard writers, focus with modal dialogs, shortcuts on any
  layout, the lock screen and touches, screenshots, overlay keys, blur on partial redraws.

### Security
- Lua configuration runs in a sandbox with limits.
- D-Bus calls check who sent them.
- Lock screen hardening (see Added).

## [0.1.1] - 2026-10-26

### Added
- Light and dark for programs, with a schedule (fixed times or sunrise and sunset): through the
  desktop portal's settings and GSettings; action `color-scheme dark|light|default|toggle|auto`.
- Raw input hooks.

### Changed
- Smaller releases: javadoc jars are a stub page.

### Fixed
- Widgets, Compose (`caulis-compose`) and D-Bus fixes after review (hover, keyboard, key repeat,
  focus, file descriptors in messages).

### Removed
- **Breaking:** the optional `caulis-cursor-bibata` module (0.1.0 stays on Central). Caulis' own
  cursors are the default.

## [0.1.0] - 2026-10-25

The first release on Maven Central.

### Added
- A Wayland compositor library for the JVM in Kotlin (JDK 27, Foreign Function & Memory API, no JNI):
  its own Wayland protocol code generator and server, GPU rendering (EGL, GBM, GLES), linux-dmabuf,
  damage tracking, direct scanout, a hardware cursor plane.
- Three ways to run: nested in another session, on a real screen (libseat, atomic DRM, libinput,
  hotplug, watchdog) and headless.
- Window management: maximize, fullscreen, minimize, move, resize, key bindings, workspaces,
  several monitors, scale (fractional too), monitor modes, transforms and mirroring.
- `caulis-wm` with a Kotlin and Java DSL; `caulis-tiling` with a layout language and many built-in
  layouts (binary tree, i3-like containers, scrolling columns, newm-like grid canvas), scratchpads,
  overview, touchpad gestures.
- Layer shell (panels, docks, wallpapers, launchers), window effects (rounded corners, borders,
  shadows, blur, opacity), title bars, screen locking (`ext-session-lock`), cursor themes,
  screenshots and screen sharing, XWayland, everyday protocols (data-control, primary selection,
  activation, idle, pointer constraints, output management, gamma, ...).
- Configuration as `config.json` (with a JSON Schema and Nix options), `config.lua` (`caulis-lua`)
  and `config.kts` (`caulis-script`); hot reload of the configuration and of your code
  (`caulis-reload`); window rules, animations and backgrounds of your own, shaders from files.
- An IPC socket described in `caulis.proto` (`caulis-ipc`, `--do`, `--state`, `--events`).
- The window director, drawings, widgets, background views, D-Bus export, Compose widgets
  (`caulis-compose`), `caulis-screenshot`.
- A project template (`templates/new-compositor.sh`), examples, a getting-started guide, API docs,
  a Nix flake with NixOS and Home Manager modules.

[Unreleased]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.5...HEAD
[0.1.5]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/AristarkhKenebas/caulis/compare/414611b...v0.1.2
[0.1.1]: https://github.com/AristarkhKenebas/caulis/compare/f26cfdd...414611b
[0.1.0]: https://github.com/AristarkhKenebas/caulis/tree/f26cfdd
