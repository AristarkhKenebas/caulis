// The login session: libseat for the devices and logind on D-Bus.

plugins {
    id("caulis.published")
}

description = "The login session for Caulis: seat access through libseat, logind's signals (sleep, lock) over D-Bus."

dependencies {
    api(project(":caulis-dbus"))
    api(project(":caulis-native"))
    api(project(":caulis-log"))
}
