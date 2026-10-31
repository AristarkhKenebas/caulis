// The plain types of input (settings, gestures, scrolling, tablets), used by everything that
// handles input; nothing here talks to programs.

plugins {
    id("caulis.published")
}

description = "Plain input types for Caulis: input settings, gestures, scrolling, tablets; what libinput, the server half and the backends share."

dependencies {
    api(project(":caulis-log"))
    api(project(":caulis-geometry"))
}
