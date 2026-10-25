// Screenshots inside a Caulis compositor: the screen frozen, a part, a window or a whole
// monitor chosen on it, saved and put on the clipboard. Optional: a compositor has it when
// it depends on this module (and calls screenshots(), or binds the "screenshot" action).

plugins {
    id("caulis.published")
}

description = "Screenshots for Caulis compositors: a region, a window or a screen, saved and copied."

dependencies {
    api(project(":caulis-wm"))
    testImplementation(testFixtures(project(":caulis-core")))
}
