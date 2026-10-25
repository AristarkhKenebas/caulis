// The Bibata cursor themes (by Abdulkaiz Khatri, https://github.com/ful1e5/Bibata_Cursor),
// ready to use: add this module and Bibata is the cursor. Unlike the rest of Caulis (MPL-2.0),
// this module is GPL-3.0, as Bibata itself — see LICENSE and README.md.

plugins {
    id("caulis.published")
}

description = "The Bibata cursor themes, ready for Caulis compositors (GPL-3.0, as Bibata)."

// Its own version: the themes rarely change, and at 13 MB a release they are not sent to
// Maven Central again with every release of Caulis (ci/publish-central.sh leaves out a
// version Central has already). They work with any Caulis since 0.1.0.
providers.gradleProperty("caulis.bibata.version").orNull?.let { version = it }

// The themes are already in the jar: its sources are the code alone (the themes' own sources,
// SVG, are Bibata's: see README.md).
tasks.named<Jar>("sourcesJar") {
    exclude("**/*.zip")
}

dependencies {
    api(project(":caulis-core"))
}
