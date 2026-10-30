# Module caulis-cursor

Cursor themes: reading XCursor files ([dev.caulis.core.cursor.XCursor]), finding a theme the
way other programs do (the user's and the system's icon folders, `XCURSOR_PATH`), and
Caulis's own themes ("Caulis", "Caulis-Black") inside the jar. A library can bring more
themes ([dev.caulis.core.cursor.CursorThemeProvider]). `caulis-core` brings it; it needs
nothing else.

# Package dev.caulis.core.cursor

[dev.caulis.core.cursor.CursorTheme] (a theme at one size: its cursors, also animated ones)
and [dev.caulis.core.cursor.CursorThemeProvider] (themes a library brings).
