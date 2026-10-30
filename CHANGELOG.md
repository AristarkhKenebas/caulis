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

### Added
- `Desktop.panelsShowWhileLocked` (default false): whether the compositor's own lock screen
  (`lockInternally` without `hideDesktop`) shows programs' panels (a bar, a notification
  daemon's pop-ups), menus and tooltips, and X11's unmanaged windows behind it. Surfaces the
  lock's `allowSurface` lets in are drawn either way.

### Changed
- Behind the compositor's own lock screen (`lockInternally` without `hideDesktop`) programs'
  panels, notification pop-ups, menus and X11's unmanaged windows are now hidden: what they show
  may be private. **To keep them drawn** as before, set `desktop.panelsShowWhileLocked = true`.
  (`hideDesktop` and locker programs showed none of them already.)

### Fixed
- CI on GitHub: the "CI" and "Release" workflows failed in "Build and test" since 0.1.3. A
  test handed descriptors numbered 10 and above to `sh`, which on Ubuntu is dash and cannot
  redirect to them ("Bad fd number"); it uses bash now. A test name with a non-ASCII character
  stopped the test compile where the locale is not UTF-8. The CI scripts install dbus-daemon
  (so the tests' own session bus runs there too) and keep going after a failure to show every
  failing module at once.
- A task that posts itself again (a Compose coroutine that yields, a configuration's
  `post { again() }`) no longer freezes the compositor: the event loop runs only the tasks posted
  before it started on them, then goes on to input, programs and frames (before, it never left them
  and the watchdog ended the session after 10 s).
- With input on its own thread, a mouse reporting faster than the compositor handles its
  movements no longer holds back what programs are sent and the frames: each turn of the loop
  handles the events that were waiting when it began. A movement whose handling failed no longer
  leaves the cursor on the cursor plane jumping back after every warp.
- `ScreenShader.file(path)` no longer adds a file watch each time it is made (each
  configuration reload): every save of the shader read it once per reload since the start.
- A Compose widget that is placed but hidden by its look (a popup waiting to be measured)
  keeps its scene: it was let go after `keepHiddenSeconds` and composed anew at once, again every
  minute, on the compositor's thread.
- D-Bus: a big message (a notification's picture sent along, megabytes) and bursts of
  messages are read without copying what came after each message again; byte arrays (`ay`) are
  kept as bytes, not as a boxed number each (a 2048x2048 picture made millions of objects on the
  compositor's thread). They are still a `List` of numbers 0 to 255 for your code.
- Pasting a big text or picture copied in an X11 program into a Wayland program no longer
  stalls the desktop: it is written into the pipe a piece at a time instead of copying all that
  is left again for every write (a 64 MB answer cost about 128 MB of copying per write).
- Screen capture refuses a buffer whose rows are far longer than the picture (more than 4 KB
  over): its rows were read back into the compositor's own memory at that length and kept, up to
  the 2 GB a pool may have.
- `log("appearance", 2)` (as the guide shows), `log("compose", …)` and the other areas
  Caulis logs in (keyboard, keys, monitors, overlay, idle, dbus, permissions, ipc, loop, lua, script,
  screenshot) are known areas now: the call stopped the compositor at its start ("there is no log
  area"), and `CAULIS_LOG=appearance=2` warned first. The guide lists them all.
- Guide and examples: the pavucontrol rule matches pavucontrol 6 (`program("pavucontrol")`;
  its app id is org.pulseaudio.pavucontrol now), `caulisctl` (which does not exist) is
  `my-compositor --do` and where that script comes from is said, the troubleshooting messages are
  quoted as the compositor prints them, `keyboard-layout` is in the actions table, the config.json
  key example no longer lands on another key (Super+W), step numbers and the starter's "quits at
  any time" are right.

### Security
- A program can no longer freeze the compositor by sending a named pipe (FIFO) as its
  shared memory (`wl_shm.create_pool`): only plain files are mapped, anything else is refused at
  once. Before, opening it waited for a writer for ever on the compositor's thread.
- The lock key (`keys.lock`, Super+L) locks the screen even while a window keeps the
  compositor's keys (keyboard-shortcuts-inhibit, which any program may ask for): before, it went to
  the program and the screen stayed open. `keys { lockWhileInhibited = false }` (config.json
  `keys.lockWhileInhibited`) gives it to the window as before.
- A window hidden from capture (`hiddenFromCapture`, a password manager) is a black box when
  shared alone too (a portal's or OBS's window source, ext-foreign-toplevel image capture);
  before, only screenshots and screen sharing blacked it out. `Desktop.captureWindow` draws it
  black for every caller.
- Clipboard formats offered to programs are capped (64, each short enough to send),
  whoever put them on the clipboard: an X11 program could offer thousands of names or one of
  kilobytes, and each program it was offered to (the focused one, clipboard managers, Xwayland
  itself) lost its connection.
- An X11 program can no longer take over a drag you started elsewhere: its move or resize
  request (`_NET_WM_MOVERESIZE`) counts only while the button is held in a window of the same
  client, and your `WindowDirector` is asked first, as for Wayland programs.
- X11 programs can no longer read the clipboard or replace it while the screen is locked
  (as Wayland programs cannot), and no X11 program can start pastes without end (each held a pipe
  and asked the copying program, a password manager, again).
- xdg-activation: a token a program asked for is good only while that program still has
  the keyboard (`activationTokensNeedFocus`, on; config.json `activationTokensNeedFocus`; `Activation.tokensNeedFocus` for caulis-core alone). A program could ask for tokens while you
  used it and take the focus back up to 30 s after you moved to another window (in the middle of a
  password typed there). Programs asking for many tokens no longer push out the ones the
  compositor gave programs it started.

## [0.1.7] - 2026-10-30

### Added
- The screenshot chooser offers the part chosen last again (as on a Mac): open it, and the same
  part is there — Enter or Capture takes it at once, or move and resize it. It is dropped when
  the monitors change, and clamped to its monitor. A new toolbar button (`ChooserButton.CLEAR`,
  its tip `ScreenshotWords.clearSelection`) clears it; it shows only while there is a part.
  Settings: `ChooserBehaviour.rememberSelection` (on), `ChooserBehaviour.rememberAcrossRestarts`
  (off; kept in `Screenshots.selectionFile`, by default
  `~/.local/state/<configName>/screenshot-selection`) and `ChooserBehaviour.escapeClearsSelection`.
- `KeyInput.layoutKeysym`: the key's symbol in the layout in use ("Cyrillic_ve"), beside
  `keysym`, which stays Latin whatever the layout ("d"); for bindings on another script's letters.
- `KeyBindings.keyOf` and `Seat.bindingKeyOf`: which key of the combinations a pressed key is
  (by default the physical key; `seat::bindingKeyOf` follows the layout, see below).
- `Desktop.moveOutOfSpecialWorkspace()` (no window): the focused window if it is in a special
  workspace, else the one focused last in the special workspace shown (or used last, even
  hidden) moves out — `Caulis.moveOutOfSpecialWorkspace(null)` and the action
  `move-out-of-special` use it, so the key works with the special workspace hidden too.
- Windows of a shown special workspace can be dragged (Super+drag, `Desktop.beginMove`): dropped
  on the dimmed workspace around it they move out onto that workspace, dropped inside they go
  back to their tile. For a window manager's own drag: `Desktop.dragInSpecialWorkspace`,
  `Desktop.dropFromSpecialWorkspace` and `Desktop.specialWorkspaceArea`.
- `EarlyPointer.caughtUp(count)`: several movements handled as one (by default `caughtUp()` that
  many times).

### Changed
- In the screenshot chooser the first Escape clears a part shown (it is not offered again) and a
  second one closes. `ChooserBehaviour(escapeClearsSelection = false)` brings back one Escape
  that closes (now forgetting the part). The close button and a right click still close at once.
- A toolbar listing its buttons itself (`ChooserBehaviour(buttons = ...)`) gets the clear button
  only when `ChooserButton.CLEAR` is added to the list; the default list has it.
- Letters and punctuation in bindings now follow the keyboard layout, as in GNOME and KDE:
  `Super+Z` is the key printed Z on a German keyboard, and on a layout of another script
  (Russian, Ukrainian, Greek, Hebrew) the key that types the letter in the first Latin layout
  of `keyboardLayouts` — Super+D, Super+Shift+M and Ctrl+Alt+T work on every layout of "us,ru"
  or "us,ua,gr". With "us,ru" and the like nothing changes. The row of digits, F-keys and arrows
  stay physical. New setting `bindingsUseFirstLatinLayout` (Kotlin, config.json, Lua, Nix;
  default true); **to keep the old physical keys** on a non-US Latin layout (Dvorak, AZERTY,
  QWERTZ), set it to false.
- Pointer movements waiting for the compositor's thread are merged: consecutive relative
  movements are summed, absolute ones keep the last place (keys and buttons between them keep
  their order). A busy moment no longer leaves thousands of movements to be handled one by one
  afterwards. Over 1000 input events waiting is logged once.
- With `focusFollowsMouse`, the pointer passing over the dimmed windows behind a shown special
  workspace no longer takes the focus away from it (when `clickOutsideHides`, the default: a
  click there hides it, as before). A key meant for the special workspace's window — moving it
  out — went to the window behind.

### Fixed
- The screenshots' settings could go back to their defaults after a while when nothing but the
  `screenshot` action used them (they were held weakly only); they now live as long as their
  configuration.
- The cursor plane's quick move (on every pointer movement) makes no objects any more.

## [0.1.6] - 2026-10-30

### Added
- `inputThread` (on; config.json `performance.inputThread`): with monitors driven directly,
  keyboards, mice and touchpads are read on a thread of their own. A busy moment of the main
  loop no longer holds input back, the events reach the loop in the same order, and the cursor
  on a cursor plane keeps moving meanwhile — moved straight from that thread with the legacy
  cursor call, which the next frame agrees with. Safe across terminal switches (nothing moves
  while another session has the screen), hot-plugged devices (opened through the loop's
  session) and the lock screen. Underneath: `InputDevices.ownThread`,
  `InputDevices.earlyPointer`, the `EarlyPointer` interface, `Desktop.earlyPointer` and
  `Desktop.earlyCursorMoves`.
- `mallocMapAboveKb` (256; `performance.mallocMapAboveKb`, `NativeMemory.mapBlocksAbove`):
  malloc blocks that big or bigger go back to the system the moment they are freed. glibc
  raised this limit by itself and kept the pictures Skia and the GPU driver make each frame:
  headless with four terminals, 30-60 MB more within minutes. 0 keeps glibc's own.
- `glVendorByDriver` (on; `performance.glVendorByDriver`, `GlesRenderer.vendorByDriver`): only
  the EGL driver made for the GPU in use is loaded — NVIDIA's for nvidia-drm, Mesa's for amdgpu,
  i915, nouveau and the others. With both installed, libglvnd loaded NVIDIA's EGL on an AMD
  laptop just to hear "no": ~35 MB of memory for nothing. A `__EGL_VENDOR_LIBRARY_FILENAMES`
  you set yourself is kept as it is; programs started later never see Caulis's choice.
- `ComposeWidget.keepHiddenSeconds` and `ComposeScenes.keepHiddenSeconds` (60): a Compose
  scene not shown for that long (a closed menu, notification cards that went) gives its memory
  back — pixels, composition, icons — and is made anew when it shows again. 0 keeps it.
- `ComposeWarmup.draw(width, height, frames, content)`: draws content ahead on the calling
  thread (a compositor's own menus, one after another on a thread of its own).
- `ComposeWidget.prepareAhead(monitor, width, height)` (and `ComposeHandle.prepareAhead`): a
  widget's first scene made — composed and drawn — on a thread of your own and handed over; the
  compositor's thread then only shows its pixels instead of holding the pointer for the first
  composition (a bar's: ~0.2-0.3 s at the start).

### Changed
- `trimMemoryEveryMinutes` is 2 by default (was 10): with programs drawing, glibc's pools held
  30-60 MB more within minutes. Giving it back takes a few milliseconds on a thread of its own.
  Set it back to 10 if you prefer.

- The starter template, the getting-started guide and caulis-reload's page declare the
  `development` configuration with `configurations.create("development")`: Gradle 9.6
  deprecated `val development by configurations.creating` (it goes in Gradle 10). Builds made
  from the old template keep working until then; change that line the same way.

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

[Unreleased]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.7...HEAD
[0.1.7]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/AristarkhKenebas/caulis/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/AristarkhKenebas/caulis/compare/414611b...v0.1.2
[0.1.1]: https://github.com/AristarkhKenebas/caulis/compare/f26cfdd...414611b
[0.1.0]: https://github.com/AristarkhKenebas/caulis/tree/f26cfdd
