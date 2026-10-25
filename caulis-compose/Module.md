# Module caulis-compose

Parts of a compositor's own interface written in Compose — a bar, a dock, a launcher, a
volume pill, notifications. Each is a Compose scene drawn into a picture (no window, no X11)
and shown by the compositor as one of its widgets: it gets the pointer where it is, the wheel,
and — when it asks — the keyboard (text fields work, with the keyboard layout). Everything
Compose runs (recomposition, animations, `LaunchedEffect`) runs on the compositor's thread.

A Compose bar adds about 45 MB to the compositor (Skia and Compose's classes), a little more
for each further scene. Compose's androidx parts come from Google's repository: add `google()`
beside `mavenCentral()`.

# Package dev.caulis.compose

[dev.caulis.compose.compose]: a Compose widget in a Caulis configuration;
[dev.caulis.compose.ComposeWidget]: the same on a plain [dev.caulis.core.Desktop].
