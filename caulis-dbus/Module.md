# Module caulis-dbus

D-Bus, the message bus of the desktop, spoken directly over its socket on Caulis's event
loop (no libdbus): method calls and their replies, signals, objects of your own on the bus,
file descriptors passed along. `caulis-core` brings it; it needs only `caulis-native`.

# Package dev.caulis.core.dbus

[dev.caulis.core.dbus.DBusConnection] (the session or system bus) and
[dev.caulis.core.dbus.DBusScope] (what one part of a program exported and listens to, taken
away together).
