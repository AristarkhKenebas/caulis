// Cursor themes: XCursor files (installed themes, the user's and the system's) and Caulis's own
// themes inside the jar (made by tools/cursors). Plain Kotlin: no C library, no GPU.

plugins {
    id("caulis.published")
}

description = "Cursor themes for Caulis: XCursor files, installed themes and Caulis's own built-in ones."

dependencies {
    // (Only for @InternalCaulisApi: what Caulis's own modules may use and others may not.)
    api(project(":caulis-log"))
}
