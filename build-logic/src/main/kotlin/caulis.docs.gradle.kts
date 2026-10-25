// The documentation website of all library modules together (applied at the top level).
//   ./gradlew dokkaGenerate   ->   build/dokka/html/index.html

plugins {
    id("org.jetbrains.dokka")
}

dokka {
    moduleName = "Caulis"
}

dependencies {
    // Every published module (see caulis.published).
    for (module in listOf(
        "caulis-log", "caulis-native", "caulis-geometry", "caulis-tiling", "caulis-protocol", "caulis-render", "caulis-core",
        "caulis-xwayland", "caulis-ipc", "caulis-wm", "caulis-screenshot", "caulis-compose", "caulis-reload", "caulis-lua", "caulis-script", "caulis-cursor-bibata",
    )) {
        dokka(project(":$module"))
    }
}
