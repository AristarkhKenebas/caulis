// Drawing on the GPU with OpenGL ES 3 (through EGL on a GBM device, no window system).

plugins {
    id("caulis.library")
}

dependencies {
    api(project(":caulis-native"))
}
