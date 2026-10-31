// Plugins for any compositor: finding them in a folder, their manifests, loading each in a class
// loader of its own, a throwing or hanging one contained, everything one registered taken away.
// Knows nothing of Caulis's desktop: what a plugin may call is the compositor's (PluginHost).

plugins {
    id("caulis.published")
}

description = "Plugins for compositors: discovery, manifests, lifecycle, isolated class loaders, hot reload, error containment and resource tracking."

dependencies {
    api(project(":caulis-log"))
}
