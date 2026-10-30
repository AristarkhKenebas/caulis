// D-Bus, the desktop's message bus: a small client of its own on the event loop (no libdbus),
// for the session and the system bus. Used by the desktop for logind, portals, notifications
// and the rest; on its own by anyone who wants to talk to the bus from a compositor.

plugins {
    id("caulis.published")
}

description = "A small D-Bus client for Caulis on its event loop: calls, signals, exported objects, file descriptors."

dependencies {
    api(project(":caulis-native"))
}
