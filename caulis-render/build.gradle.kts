// Drawing on the GPU with OpenGL ES 3 (through EGL on a GBM device, no window system).

plugins {
    id("caulis.published")
    // assumeGpu() for the GPU tests of this and the other modules.
    `java-test-fixtures`
}

description = "GPU drawing for Caulis with OpenGL ES 3 on EGL and GBM."

dependencies {
    api(project(":caulis-native"))
    api(project(":caulis-geometry"))

    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
}
