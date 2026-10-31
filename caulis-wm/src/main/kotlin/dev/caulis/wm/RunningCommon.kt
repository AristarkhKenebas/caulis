package dev.caulis.wm

// What the parts of a running compositor share.


// What programs must get from the compositor itself (Caulis.environment does not change them).
internal val OWN_VARIABLES = setOf("WAYLAND_DISPLAY", "DISPLAY", "XDG_ACTIVATION_TOKEN", dev.caulis.ipc.CaulisIpc.SOCKET_VARIABLE)
// The start's report, and when the memory the start freed is given back (Caulis.trimMemory).
internal const val START_REPORT_MILLIS = 30_000L
internal const val TRIM_FIRST_MILLIS = 20_000L
internal const val TRIM_SECOND_MILLIS = 60_000L
internal const val BTN_LEFT = 0x110
internal const val BTN_RIGHT = 0x111

// From here on, keys are no typing keys (media, laptop, F13..F24); 0x100..0x15f are buttons.
internal const val FIRST_SPECIAL_KEY = 113

// Keys that always exist when a configuration has none at all.
internal const val EMERGENCY_QUIT = "Super+Shift+Escape"
internal const val EMERGENCY_TERMINAL = "Super+Return"
