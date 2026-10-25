// config.lua: a compositor's configuration in Lua (LuaJ, Lua 5.2 on the JVM) — the settings of
// config.json, plus keys, rules and events as Lua functions. Add it to a compositor that should
// read config.lua; it is found by itself (ServiceLoader).

plugins {
    id("caulis.published")
}

description = "config.lua for Caulis compositors: the configuration in Lua (LuaJ)."

dependencies {
    api(project(":caulis-wm"))
    implementation(libs.luaj)
}
