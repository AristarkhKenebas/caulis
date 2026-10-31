package dev.caulis.core

import dev.caulis.render.Color
import java.awt.Font

/** Where notices are shown (see [NoticeStyle.placement]). */
sealed class NoticePlacement {
    /**
     * A strip across the whole monitor at its top (or [bottom]), as Hyprland shows mistakes
     * in its configuration. [room] says what makes way for it; [errorRoom] the same for
     * errors alone (null: as the others) — e.g. the others over the bar, gone by themselves,
     * and errors pushing everything down until they are closed:
     *
     * ```
     * NoticeStyle(placement = NoticePlacement.Banner(room = Room.COVER, errorRoom = Room.PUSH_ALL))
     * ```
     */
    data class Banner @JvmOverloads constructor(
        val bottom: Boolean = false,
        val room: Room = Room.PUSH_ALL,
        val errorRoom: Room? = null,
    ) : NoticePlacement() {
        /** What makes way for a notice of [level]. */
        fun roomOf(level: NoticeLevel): Room = if (level == NoticeLevel.ERROR) errorRoom ?: room else room

        enum class Room {
            /** Windows and panels move aside: nothing is covered. */
            PUSH_ALL,

            /** Over the panels at the edge; windows move aside. */
            PUSH_WINDOWS,

            /** Over everything: nothing moves. */
            COVER,
        }
    }

    /**
     * Cards in a corner (or in the middle of an edge), stacked, as notifications of programs
     * usually are; [width] and [margin] in desktop units. They stay clear of panels.
     */
    data class Toast @JvmOverloads constructor(val corner: Corner = Corner.BOTTOM_RIGHT, val width: Int = 380, val margin: Int = 16) : NoticePlacement() {
        enum class Corner { TOP_LEFT, TOP, TOP_RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }

        init {
            require(width in 100..2000) { "a toast is 100 to 2000 units wide" }
            require(margin in 0..500) { "margin must be 0 to 500" }
        }
    }

    /**
     * Not drawn at all: only [Desktop.onNotice] hears them — show them your own way (your
     * panel, a notification daemon, ...).
     */
    data object Hidden : NoticePlacement()
}

/**
 * How the compositor's notices look and where they are. Sizes are in desktop units
 * (scaled like everything); for a look of your own, give a [painter].
 *
 * ```
 * NoticeStyle()                                                        // a strip at the top that pushes windows down
 * NoticeStyle(placement = NoticePlacement.Toast())                     // cards at the bottom right
 * NoticeStyle(placement = NoticePlacement.Banner(room = Room.COVER))   // a strip over everything
 * ```
 */
data class NoticeStyle @JvmOverloads constructor(
    val placement: NoticePlacement = NoticePlacement.Banner(),
    val font: String = java.awt.Font.SANS_SERIF,
    val fontSize: Int = 13,
    /** Room around the text, between toasts, and their round corners. */
    val padding: Int = 10,
    val gap: Int = 8,
    val radius: Int = 10,
    val info: NoticeColors = NoticeColors(Color.hex("#2b2f38"), Color.hex("#e8eaee")),
    val warning: NoticeColors = NoticeColors(Color.hex("#f5c211"), Color.hex("#1b1b1b")),
    val error: NoticeColors = NoticeColors(Color.hex("#e01b24"), Color.hex("#ffffff")),
    /** How long each level stays, in seconds (0 = until clicked). The pointer on it keeps it. */
    val infoSeconds: Double = 5.0,
    val warningSeconds: Double = 12.0,
    val errorSeconds: Double = 0.0,
    /** At most this many at once (the newest); the rest come when those go. */
    val maxShown: Int = 4,
    /** How long a notice takes to appear and to go (milliseconds). */
    val fadeMillis: Long = 180,
    /** On every monitor, or only on the one that had the focus when it came. */
    val everyMonitor: Boolean = false,
    /** Draws a notice; replace it to draw them entirely your own way. */
    val painter: NoticePainter = DefaultNoticePainter,
) {
    init {
        require(fontSize in 6..72) { "fontSize must be 6 to 72" }
        require(padding in 0..100 && gap in 0..100 && radius in 0..100) { "padding, gap and radius must be 0 to 100" }
        require(infoSeconds >= 0 && warningSeconds >= 0 && errorSeconds >= 0) { "seconds must not be negative" }
        require(maxShown in 1..20) { "maxShown must be 1 to 20" }
        require(fadeMillis in 0..5000) { "fadeMillis must be 0 to 5000" }
    }

    companion object {
        /**
         * Notices as calm cards at the top right of the screen: rounded, [dark] or light, a
         * round mark of the level and the title in bold ([CardNoticePainter]).
         */
        @JvmStatic
        @JvmOverloads
        fun cards(
            width: Int = 400,
            corner: NoticePlacement.Toast.Corner = NoticePlacement.Toast.Corner.TOP_RIGHT,
            dark: Boolean = true,
        ) = NoticeStyle(
            placement = NoticePlacement.Toast(corner, width = width, margin = 14),
            fontSize = 13,
            padding = 12,
            gap = 10,
            radius = 14,
            info = NoticeColors(Color.hex(if (dark) "#88c0d0" else "#5e81ac"), Color.hex("#e5e9f0")),
            warning = NoticeColors(Color.hex(if (dark) "#ebcb8b" else "#c48a2c"), Color.hex("#e5e9f0")),
            error = NoticeColors(Color.hex(if (dark) "#e06c75" else "#bf616a"), Color.hex("#e5e9f0")),
            painter = if (dark) CardNoticePainter() else CardNoticePainter(Color.hex("#f4f6f9f7"), Color.hex("#2e3440")),
        )
    }

    fun colorsOf(level: NoticeLevel): NoticeColors = when (level) {
        NoticeLevel.INFO -> info
        NoticeLevel.WARNING -> warning
        NoticeLevel.ERROR -> error
    }

    fun secondsOf(level: NoticeLevel): Double = when (level) {
        NoticeLevel.INFO -> infoSeconds
        NoticeLevel.WARNING -> warningSeconds
        NoticeLevel.ERROR -> errorSeconds
    }
}
