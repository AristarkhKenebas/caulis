package dev.caulis.compose

/** Settings for every [ComposeWidget]. */
object ComposeScenes {
    /**
     * A widget's scene not shown for this many seconds is let go and made anew when it shows
     * again ([ComposeWidget.keepHiddenSeconds] for one widget): menus opened once do not keep
     * their memory (pixels, composition, icons) for the rest of the session. 0: kept. 60 by default.
     */
    @JvmStatic
    @Volatile
    var keepHiddenSeconds: Int = 60
        set(value) {
            require(value in 0..86_400) { "keepHiddenSeconds is 0 to 86400, not $value" }
            field = value
        }

    /**
     * A scene not drawn for this many seconds keeps its picture on the GPU only, not also in the
     * computer's memory (width x height x 4 bytes: 33 MB for a 4K launcher). The next change
     * draws it anew, as every change does. 0: kept. 2 by default.
     */
    @JvmStatic
    @Volatile
    var keepPixelsSeconds: Int = 2
        set(value) {
            require(value in 0..86_400) { "keepPixelsSeconds is 0 to 86400, not $value" }
            field = value
        }
}
