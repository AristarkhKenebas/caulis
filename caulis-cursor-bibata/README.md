# caulis-cursor-bibata

The [Bibata](https://github.com/ful1e5/Bibata_Cursor) cursor themes by Abdulkaiz Khatri
(v2.0.7), ready to use with Caulis: add this module to a project and Bibata is the cursor —
nothing to install.

```kotlin
implementation("dev.caulis:caulis-cursor-bibata:0.1.0-SNAPSHOT")
```

Included: `Bibata-Modern-Classic` (black, the default), `Bibata-Modern-Ice` (white) and
`Bibata-Modern-Amber`. Choose one with `cursorTheme = "Bibata-Modern-Ice"`. The other Bibata
variants (Original, left-handed) can be installed on the system (e.g. the
`bibata-cursor-theme-bin` package) and are then found by name as well.

## License

**GPL-3.0**, as Bibata itself (see [LICENSE](LICENSE)) — unlike the rest of Caulis, which is
MPL-2.0. A compositor that includes this module contains GPL-licensed files; if that does
not suit you, leave the module out: Caulis then uses the cursor theme installed on the
system.

The theme files are Bibata's release files, unchanged, packed into one zip per theme; the
links between cursor names are kept as a list (`aliases`) inside the zip.
