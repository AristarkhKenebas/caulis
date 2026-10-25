# Module caulis-xwayland

X11 programs in a Caulis compositor: Xwayland is started when the first one connects, and
Caulis is the window manager of their windows (through XCB) — they tile, float, get title
bars and effects, show in docks and screen sharing like Wayland windows, at the right scale.
Copy and paste works between X11 and Wayland programs (CLIPBOARD, large data in pieces).

On scaled screens X works in the screen's real pixels; programs are told the DPI
(`Xft.dpi`) and cursor size, so they draw themselves at the right size instead of being
stretched. Programs that only know whole scales (Java's AWT) get an Xwayland of their own
in whole pixels and are shown smaller — sharp and the right size; the display is then a
door inside the compositor that passes each program's bytes to its Xwayland. Each
Xwayland keeps its scale while it runs: after a scale change, running programs keep theirs
and new ones get an Xwayland at the new one.

# Package dev.caulis.xwayland

[dev.caulis.xwayland.Xwayland]: starting Xwayland, claiming the X display, managing its
windows ([dev.caulis.xwayland.XWindow]) and sharing the clipboard.
