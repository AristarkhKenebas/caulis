// Everything needed to run a compositor in a few lines: picks the backend (a window in
// the current session, or the whole screen), wires up the Wayland features, keys and
// window management. See dev.caulis.wm.Caulis.

plugins {
    id("caulis.library")
}

dependencies {
    api(project(":caulis-core"))
    api(project(":caulis-xwayland"))
    api(project(":caulis-ipc"))
    testImplementation(testFixtures(project(":caulis-core")))
    // config.lua while running (LuaLiveTest).
    testImplementation(project(":caulis-lua"))
}
