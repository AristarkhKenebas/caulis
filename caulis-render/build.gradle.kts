// Drawing on the GPU with OpenGL ES 3 (through EGL on a GBM device, no window system).

plugins {
    id("caulis.published")
}

description = "GPU drawing for Caulis with OpenGL ES 3 on EGL and GBM."

dependencies {
    api(project(":caulis-native"))
    api(project(":caulis-geometry"))
}
