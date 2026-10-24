# Module caulis-native

Safe Kotlin around the C pieces Caulis needs: file descriptors, sockets, shared memory,
epoll — and the event loop ([dev.caulis.event.EventLoop]) everything runs on.

# Package dev.caulis.ffi

File descriptors, Unix sockets, shared memory and other operating-system basics.

# Package dev.caulis.event

The event loop, timers, and the watchdog that ends a stuck compositor cleanly.
