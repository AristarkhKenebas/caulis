# Module caulis-tiling

How windows are arranged — pure Kotlin, independent of Wayland and graphics.

Describe a layout with building blocks ([dev.caulis.tiling.tiling]):

```kotlin
val Master = tiling("Master") {
    row {
        window(weight = 55)
        column(weight = 45) { rest() }
    }
}
```

or use a built-in one: `MasterStack`, `CenteredMaster`, `MasterStrips`, `Dwindle`, `Spiral`,
`Grid`, `Columns`, `Rows`, `Monocle`, `Floating` — and layouts that remember things:
`BinaryTree` (bspwm), `ManualTiling` (i3), `ScrollingColumns` (niri), `Canvas` (newm).

# Package dev.caulis.tiling

The layout language, the built-in layouts, and exact integer splitting ([dev.caulis.tiling.Split]).
