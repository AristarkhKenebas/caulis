// The documentation website of all library modules together (applied at the top level).
//   ./gradlew dokkaGenerate   ->   build/dokka/html/index.html

plugins {
    id("org.jetbrains.dokka")
}

dokka {
    moduleName = "Caulis"
}

dependencies {
    for (module in listOf("caulis-native", "caulis-geometry", "caulis-tiling", "caulis-protocol", "caulis-render", "caulis-core", "caulis-wm")) {
        dokka(project(":$module"))
    }
}
