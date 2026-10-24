// The compositor's core: the objects behind the basic Wayland interfaces
// (surfaces, shared-memory buffers, outputs) and, later, the backends.

plugins {
    id("tessera.library")
}

dependencies {
    api(project(":tessera-protocol"))
}
