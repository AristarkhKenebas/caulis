// Draws Caulis's own cursor themes and writes them into caulis-cursor's resources.
// Run after changing a cursor: ./gradlew :tools:cursors:run
// (-Ppreview=/some/file.png also writes a sheet of all cursors to look at).

plugins {
    id("caulis.application")
}

application {
    mainClass = "dev.caulis.tools.cursors.CursorsKt"
}

tasks.named<JavaExec>("run") {
    val output = rootProject.layout.projectDirectory.dir("caulis-cursor/src/main/resources/dev/caulis/core/cursor").asFile.path
    args = listOfNotNull(output, providers.gradleProperty("preview").orNull, providers.gradleProperty("previewSize").orNull)
}
