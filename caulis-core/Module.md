# Module caulis-core

The compositor itself: surfaces and windows, the window manager ([dev.caulis.core.Desktop]),
input, monitors, and the two backends (a window in another session, or the real
screen). Use it directly when `caulis-wm` is not flexible enough.

# Package dev.caulis.core

Windows ([dev.caulis.core.Window]), workspaces, the window manager [dev.caulis.core.Desktop]
and the Wayland features programs use (shared memory and GPU buffers, presentation time).

# Package dev.caulis.core.input

Keyboards and pointers as programs see them, keymaps, and the compositor's own key
combinations ([dev.caulis.core.input.KeyBindings]).

# Package dev.caulis.core.drm

Running directly on the screen: monitors (kernel mode setting), hotplug, direct scanout.

# Package dev.caulis.core.libinput

Keyboards, mice and touchpads on the real hardware.

# Package dev.caulis.core.session

The login session: access to devices without root, switching text consoles.

# Package dev.caulis.core.nested

Running as a window inside another Wayland session.
