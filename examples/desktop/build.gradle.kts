// A whole desktop for every day, to try Caulis on the real screen: terminals, a browser,
// chat and music, a bar with Wi-Fi, Bluetooth and sound, a launcher — and a laptop's keys.
// Start it from a text console with examples/desktop/caulis-desktop.sh.

plugins {
    id("caulis.application")
}

dependencies {
    implementation(project(":caulis-wm"))
    implementation(project(":caulis-screenshot"))
    implementation(project(":caulis-lua")) // (a config.lua of your own works too)
}

application {
    mainClass = "dev.caulis.examples.desktop.MainKt"
    applicationName = "caulis-desktop"
}
