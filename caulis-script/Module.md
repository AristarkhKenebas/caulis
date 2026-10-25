# Module caulis-script

`config.kts`: a compositor's configuration as a Kotlin script, compiled by the compositor
itself — no Gradle, no project. A compositor with this module on its class path reads
`config.kts` from its folder by itself. It contains the Kotlin compiler. See the guide, step 28.

# Package dev.caulis.script

[dev.caulis.script.KotlinScriptConfig] and the script's definition
([dev.caulis.script.CaulisConfigScript]).
