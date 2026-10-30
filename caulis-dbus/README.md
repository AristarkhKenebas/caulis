# caulis-dbus

A small D-Bus client on Caulis's event loop: `DBusConnection.session(loop)` /
`DBusConnection.system(loop)`, method calls with replies, signals, objects of your own on the
bus, file descriptors passed along. It speaks the protocol over the bus socket itself — no
libdbus.

- **Who uses it:** `caulis-core` (logind, portal settings, idle inhibitors, notifications);
  your own code through `caulis-core` or on its own.
- **Depends on:** `caulis-native` (the event loop, sockets, file descriptors), `caulis-log`.
- **Used by:** `caulis-core` (as an `api` dependency: depending on `caulis-core` brings it).
- **Package:** `dev.caulis.core.dbus` (the same as when it was part of `caulis-core`).
- **Threads:** a connection lives on the event loop it was made with; replies and signals
  arrive there.
- **Tests:** `./gradlew :caulis-dbus:test` (on a private session bus of the tests' own;
  the system bus is never reached).
