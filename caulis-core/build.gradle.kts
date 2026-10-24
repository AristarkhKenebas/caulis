// The compositor's core: the objects behind the basic Wayland interfaces
// (surfaces, shared-memory buffers, outputs) and, later, the backends.

plugins {
    id("caulis.library")
}

dependencies {
    api(project(":caulis-protocol"))
    api(project(":caulis-render"))
}
