// Input devices through libinput, on the compositor's thread or one of their own.

plugins {
    id("caulis.published")
}

description = "Input devices for Caulis through libinput: keys, pointer, touch, gestures, tablets, on a thread of their own if wanted."

dependencies {
    api(project(":caulis-input"))
    api(project(":caulis-session"))
    api(project(":caulis-native"))
    api(project(":caulis-log"))
}
