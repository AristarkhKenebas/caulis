// The compositor's core: the objects behind the basic Wayland interfaces
// (surfaces, shared-memory buffers, outputs) and, later, the backends.

plugins {
    id("caulis.published")
    // Test helpers (a test server, a small client) other modules' tests can use too.
    `java-test-fixtures`
}

description = "The core of Caulis, a Wayland compositor library: protocols, window management, effects, DRM and nested backends."

dependencies {
    api(project(":caulis-protocol"))
    api(project(":caulis-render"))
    api(project(":caulis-tiling"))
}
