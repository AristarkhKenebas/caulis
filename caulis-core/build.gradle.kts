// The compositor's core: the objects behind the basic Wayland interfaces
// (surfaces, shared-memory buffers, outputs) and, later, the backends.

plugins {
    id("caulis.library")
    // Test helpers (a test server, a small client) other modules' tests can use too.
    `java-test-fixtures`
}

dependencies {
    api(project(":caulis-protocol"))
    api(project(":caulis-render"))
    api(project(":caulis-tiling"))
}
