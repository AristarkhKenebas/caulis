package dev.caulis.core

import dev.caulis.protocol.Display
import dev.caulis.protocol.serverdecoration.OrgKdeKwinServerDecoration
import dev.caulis.protocol.serverdecoration.OrgKdeKwinServerDecorationManager
import dev.caulis.protocol.wayland.WlSurface
import dev.caulis.protocol.xdgdecorationunstablev1.ZxdgDecorationManagerV1
import dev.caulis.protocol.xdgdecorationunstablev1.ZxdgToplevelDecorationV1
import dev.caulis.protocol.xdgshell.XdgToplevel
import dev.caulis.render.Color
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D

/** Who draws a window's title bar. */
enum class TitleBars {
    /** The program, as it likes (most draw their own: GTK, Firefox, ...). */
    PROGRAM,

    /** The compositor, the same for all windows ([TitleBarStyle]); programs that agree leave theirs out. */
    COMPOSITOR,

    /** Nobody: programs that agree show no title bar at all (move with Super+drag). */
    NONE,
}

/** A button of the compositor's title bars. */
enum class TitleButton { MINIMIZE, MAXIMIZE, CLOSE }

/**
 * How the compositor's title bars look. Sizes are in desktop units (scaled like everything);
 * for a look of your own, give a [painter].
 */
data class TitleBarStyle @JvmOverloads constructor(
    val height: Int = 30,
    val font: String = java.awt.Font.SANS_SERIF,
    val fontSize: Int = 13,
    val bold: Boolean = false,
    /** Title in the middle (true) or next to the side without buttons. */
    val centerTitle: Boolean = true,
    /** The buttons, in order, on the right (or left with [buttonsLeft]). */
    val buttons: List<TitleButton> = listOf(TitleButton.MINIMIZE, TitleButton.MAXIMIZE, TitleButton.CLOSE),
    val buttonsLeft: Boolean = false,
    val focusedBackground: Color = Color.hex("#2b2f38"),
    val unfocusedBackground: Color = Color.hex("#1f2228"),
    val focusedText: Color = Color.hex("#e8eaee"),
    val unfocusedText: Color = Color.hex("#858b96"),
    /** Behind a button under the pointer, and behind the close button (red, as on Windows). */
    val buttonHover: Color = Color(1f, 1f, 1f, 0.12f),
    val closeHover: Color = Color.hex("#e81123"),
    /** Draws the bar; replace it to draw title bars entirely your own way. */
    val painter: TitleBarPainter = DefaultTitleBarPainter,
) {
    init {
        require(height in 12..120) { "a title bar is 12 to 120 units high" }
        require(fontSize in 6..72) { "fontSize must be 6 to 72" }
    }

    /** Where each button is in a bar [width] units wide (x, width), in desktop units. */
    fun buttonPlaces(width: Int): List<Pair<TitleButton, IntRange>> {
        val size = height * 3 / 2 // wide buttons, as on Windows
        return buttons.mapIndexed { i, button ->
            val from = if (buttonsLeft) i * size else width - (buttons.size - i) * size
            button to (from until from + size)
        }
    }
}

/** What a title bar shows right now (for a [TitleBarPainter]). Sizes in pixels. */
class TitleBarLook(
    val width: Int,
    val height: Int,
    /** Pixels per desktop unit (to scale fonts and lines). */
    val scale: Double,
    val title: String,
    val appId: String,
    val focused: Boolean,
    val maximized: Boolean,
    /** The button under the pointer, and the one held down. */
    val hovered: TitleButton?,
    val pressed: TitleButton?,
    /** Each button's place (x, width) in pixels. */
    val buttons: List<Pair<TitleButton, IntRange>>,
    val style: TitleBarStyle,
)

/**
 * Draws a title bar into `g` (a [TitleBarLook.width] x [TitleBarLook.height] picture, see-through
 * where nothing is drawn). The window's rounded corners and border are added around it.
 */
fun interface TitleBarPainter {
    fun paint(g: Graphics2D, bar: TitleBarLook)
}

/** The usual title bar: the title, and flat buttons with simple line icons. */
object DefaultTitleBarPainter : TitleBarPainter {
    override fun paint(g: Graphics2D, bar: TitleBarLook) {
        val style = bar.style
        fun awt(c: Color) = java.awt.Color(c.red, c.green, c.blue, c.alpha)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = awt(if (bar.focused) style.focusedBackground else style.unfocusedBackground)
        g.fillRect(0, 0, bar.width, bar.height)
        val ink = awt(if (bar.focused) style.focusedText else style.unfocusedText)

        // Buttons: a light background under the pointer (red for close), an icon of lines.
        val unit = bar.scale
        for ((button, place) in bar.buttons) {
            val active = button == bar.hovered || button == bar.pressed
            if (active) {
                g.color = awt(if (button == TitleButton.CLOSE) style.closeHover else style.buttonHover)
                g.fillRect(place.first, 0, place.last - place.first + 1, bar.height)
            }
            g.color = if (active && button == TitleButton.CLOSE) java.awt.Color.WHITE else ink
            g.stroke = BasicStroke((1.1 * unit).toFloat())
            val cx = (place.first + place.last) / 2.0
            val cy = bar.height / 2.0
            val r = 5.0 * unit
            when (button) {
                TitleButton.MINIMIZE -> g.draw(Line2D.Double(cx - r, cy, cx + r, cy))
                TitleButton.MAXIMIZE -> if (bar.maximized) {
                    // Two overlapping squares: "restore".
                    g.draw(RoundRectangle2D.Double(cx - r, cy - r + 2 * unit, 2 * r - 2 * unit, 2 * r - 2 * unit, unit, unit))
                    g.draw(Line2D.Double(cx - r + 2 * unit, cy - r, cx + r, cy - r))
                    g.draw(Line2D.Double(cx + r, cy - r, cx + r, cy + r - 2 * unit))
                } else {
                    g.draw(RoundRectangle2D.Double(cx - r, cy - r, 2 * r, 2 * r, unit, unit))
                }
                TitleButton.CLOSE -> {
                    g.draw(Line2D.Double(cx - r, cy - r, cx + r, cy + r))
                    g.draw(Line2D.Double(cx - r, cy + r, cx + r, cy - r))
                }
            }
        }

        // The title, shortened with "…" to fit between the buttons.
        g.font = Font(style.font, if (style.bold) Font.BOLD else Font.PLAIN, 1).deriveFont((style.fontSize * unit).toFloat())
        val metrics = g.fontMetrics
        val buttonsWidth = bar.buttons.sumOf { it.second.last - it.second.first + 1 }
        val room = bar.width - buttonsWidth - (24 * unit).toInt()
        var text = bar.title
        if (metrics.stringWidth(text) > room) {
            while (text.isNotEmpty() && metrics.stringWidth("$text…") > room) text = text.dropLast(1)
            text = if (text.isEmpty()) "" else "$text…"
        }
        val textWidth = metrics.stringWidth(text)
        val x = when {
            // In the middle, but never under the buttons: moved aside when it is long.
            style.centerTitle -> ((bar.width - textWidth) / 2)
                .coerceAtMost(if (style.buttonsLeft) bar.width - textWidth - (12 * unit).toInt() else bar.width - buttonsWidth - textWidth - (12 * unit).toInt())
                .coerceAtLeast(if (style.buttonsLeft) buttonsWidth + (12 * unit).toInt() else (12 * unit).toInt())
            style.buttonsLeft -> buttonsWidth + (12 * unit).toInt()
            else -> (12 * unit).toInt()
        }
        g.color = ink
        g.drawString(text, x, ((bar.height - metrics.height) / 2 + metrics.ascent))
    }
}

/**
 * Agrees with programs who draws their title bars: xdg-decoration (Qt, Chromium, Electron,
 * mpv, foot, ...) and the KDE protocol (GTK 3, older Qt). [desktop] decides by its settings;
 * programs that use neither always draw their own.
 */
class ServerDecorations(display: Display, private val desktop: Desktop) {

    private val xdg = ArrayList<Pair<ZxdgToplevelDecorationV1, Window>>()
    private val kde = ArrayList<Pair<OrgKdeKwinServerDecoration, Surface>>()
    private val log = dev.caulis.log.Logger("windows")

    init {
        display.addGlobal(ZxdgDecorationManagerV1, 1) { manager ->
            manager.requests = object : ZxdgDecorationManagerV1.Requests {
                override fun getToplevelDecoration(resource: ZxdgDecorationManagerV1, id: ZxdgToplevelDecorationV1, toplevel: XdgToplevel) {
                    val window = toplevel.dataAs<Window>() ?: return
                    if (xdg.any { it.second === window && it.first.isAlive }) {
                        resource.postError(ZxdgToplevelDecorationV1.Error.ALREADY_CONSTRUCTED, "the window already has a decoration object")
                        return
                    }
                    if (window.surface.current.buffer != null) {
                        resource.postError(ZxdgToplevelDecorationV1.Error.UNCONFIGURED_BUFFER, "the window already showed a picture")
                        return
                    }
                    val entry = id to window
                    xdg += entry
                    id.onDestroy {
                        xdg -= entry
                        window.serverDecorated = false
                    }
                    toplevel.onDestroy {
                        if (id.isAlive) id.postError(ZxdgToplevelDecorationV1.Error.ORPHANED, "the window was destroyed before its decoration object")
                    }
                    id.requests = object : ZxdgToplevelDecorationV1.Requests {
                        override fun setMode(resource: ZxdgToplevelDecorationV1, mode: Int) {
                            if (mode != ZxdgToplevelDecorationV1.Mode.CLIENT_SIDE && mode != ZxdgToplevelDecorationV1.Mode.SERVER_SIDE) {
                                resource.postError(ZxdgToplevelDecorationV1.Error.INVALID_MODE, "no mode $mode")
                                return
                            }
                            // The user's settings decide; the program hears it again.
                            tell(resource, window)
                        }

                        override fun unsetMode(resource: ZxdgToplevelDecorationV1) = tell(resource, window)
                        override fun destroy(resource: ZxdgToplevelDecorationV1) {}
                    }
                    tell(id, window)
                }

                override fun destroy(resource: ZxdgDecorationManagerV1) {}
            }
        }
        display.addGlobal(OrgKdeKwinServerDecorationManager, 1) { manager ->
            manager.requests = object : OrgKdeKwinServerDecorationManager.Requests {
                override fun create(resource: OrgKdeKwinServerDecorationManager, id: OrgKdeKwinServerDecoration, surface: WlSurface) {
                    val target = surface.dataAs<Surface>() ?: return
                    val entry = id to target
                    kde += entry
                    id.onDestroy {
                        kde -= entry
                        (target.roleObject as? Window)?.serverDecorated = false
                    }
                    id.requests = object : OrgKdeKwinServerDecoration.Requests {
                        override fun requestMode(resource: OrgKdeKwinServerDecoration, mode: Int) = tell(resource, target)
                        override fun release(resource: OrgKdeKwinServerDecoration) {}
                    }
                    tell(id, target)
                }
            }
            manager.sendDefaultMode(if (desktop.titleBars == TitleBars.PROGRAM) OrgKdeKwinServerDecorationManager.Mode.CLIENT else OrgKdeKwinServerDecorationManager.Mode.SERVER)
        }
        desktop.decorationsChanged = ::tellAll
    }

    private fun wantsServer(window: Window?): Boolean = window != null && desktop.titleBarsOf(window) != TitleBars.PROGRAM

    private fun tell(resource: ZxdgToplevelDecorationV1, window: Window) {
        val server = wantsServer(window)
        log.trace { "Title bar of $window: ${if (server) "the compositor's" else "the program's"} (xdg-decoration)" }
        window.serverDecorated = server
        resource.sendConfigure(if (server) ZxdgToplevelDecorationV1.Mode.SERVER_SIDE else ZxdgToplevelDecorationV1.Mode.CLIENT_SIDE)
        if (window.configured) window.configure() // a new decoration mode needs a configure
        desktop.decorationOfChanged(window)
    }

    private fun tell(resource: OrgKdeKwinServerDecoration, surface: Surface) {
        val window = surface.roleObject as? Window
        val server = window?.let(::wantsServer) ?: (desktop.titleBars != TitleBars.PROGRAM)
        window?.serverDecorated = server
        resource.sendMode(if (server) OrgKdeKwinServerDecoration.Mode.SERVER else OrgKdeKwinServerDecoration.Mode.CLIENT)
        window?.let(desktop::decorationOfChanged)
    }

    // The settings changed: every program hears its mode again.
    private fun tellAll() {
        for ((resource, window) in xdg.toList()) if (resource.isAlive) tell(resource, window)
        for ((resource, surface) in kde.toList()) if (resource.isAlive) tell(resource, surface)
    }
}
