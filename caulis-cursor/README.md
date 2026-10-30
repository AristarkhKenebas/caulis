# caulis-cursor

Cursor themes: XCursor files, installed themes found the way other programs find them, and
Caulis's own themes ("Caulis", "Caulis-Black") inside the jar. Plain Kotlin — no C library,
no GPU.

- **Who uses it:** `caulis-core` (the cursor the compositor draws, and the theme it tells
  programs and Xwayland about); your own code through `caulis-core` or on its own.
- **Depends on:** nothing (the Kotlin standard library).
- **Used by:** `caulis-core` (as an `api` dependency: depending on `caulis-core` brings it).
- **Package:** `dev.caulis.core.cursor` (the same as when it was part of `caulis-core`).
- **Entry points:** `CursorTheme`, `XCursor`, `CursorThemeProvider` (themes of your own in a
  library: list the provider in `META-INF/services/dev.caulis.core.cursor.CursorThemeProvider`).
- **Built-in themes:** drawn by `./gradlew :tools:cursors:run`, which writes them into this
  module's resources.
- **Threads:** none of its own; themes are loaded where they are asked for.
- **Tests:** `./gradlew :caulis-cursor:test`.
