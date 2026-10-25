package dev.caulis.examples.desktop

import dev.caulis.core.Background
import dev.caulis.core.Effects
import dev.caulis.core.NoticePlacement
import dev.caulis.core.NoticeStyle
import dev.caulis.screenshot.screenshots
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import dev.caulis.wm.caulis
import java.nio.file.Files
import java.nio.file.Path

// True if [program] can be started (it is on the PATH).
private fun has(program: String) =
    System.getenv("PATH").orEmpty().split(':').any { it.isNotEmpty() && Files.isExecutable(Path.of(it, program)) }

private val home = Path.of(System.getProperty("user.home"))

fun main() = caulis {
    configName = "caulis-desktop" // ~/.config/caulis-desktop/config.json (or config.lua) on top
    keyboardLayouts = "us,ru"
    keyboardOptions = "grp:win_space_toggle,caps:none"
    input.touchpads.naturalScroll = true // the content follows the fingers, as on a phone
    focusFollowsMouse = true // the window under the pointer gets the keyboard (not while a button is held)
    terminal = "foot"
    layout = Dwindle(Gaps(inner = 8, outer = 10))
    effects = Effects.MODERN
    // Notices over the bar, gone by themselves: nothing moves for them.
    noticeStyle = NoticeStyle(placement = NoticePlacement.Banner(room = NoticePlacement.Banner.Room.COVER))
    val wallpaper = home.resolve(".local/share/backgrounds/WhiteSur/Sonoma-light.jpg").takeIf(Files::exists)
    wallpaper?.let { background = Background.image(it) }
    // The lock screen shows the wallpaper and the ring at once (not a plain white screen).
    lockCommand = "swaylock -f --indicator-idle-visible " + (wallpaper?.let { "-s fill -i '$it'" } ?: "-c 1e1e2e")

    // Programs choose Wayland where they can (Telegram and Firefox do by themselves).
    environment["QT_QPA_PLATFORM"] = "wayland;xcb"
    environment["ELECTRON_OZONE_PLATFORM_HINT"] = "auto"
    environment["MOZ_ENABLE_WAYLAND"] = "1"
    // Another session's portals may run already (they would open dialogs over there).
    environment["GTK_USE_PORTAL"] = "0"

    // Programs.
    bind("Super+Shift+Return") { launch("alacritty") }
    bind("Super+B") { launch("firefox") }
    bind("Super+T") { launch("Telegram") }
    bind("Super+M") { launch("spotify-launcher") }
    bind("Super+D") { launch(if (has("fuzzel")) "fuzzel" else "foot --app-id caulis-run sh -c 'read -p \"Run: \" c && setsid -f sh -c \"\$c\"'") }
    // Wi-Fi and Bluetooth: their own windows if installed, else in a terminal.
    bind("Super+N") { launch(if (has("nm-connection-editor")) "nm-connection-editor" else "foot --app-id caulis-wifi nmtui") }
    bind("Super+Shift+B") { launch(if (has("blueman-manager")) "blueman-manager" else "foot --app-id caulis-bluetooth bluetoothctl") }
    bind("Super+A") { launch("pavucontrol") }
    // Print (and the laptop's screenshot key): the chooser; Shift+Print the screen, Alt+Print the window.
    screenshots()

    // Many laptops send the Windows shortcuts for their keys: the display key (F7) is
    // Super+P, the screenshot key (F10) Super+Shift+S.
    bind("Super+P") { run("displays next") }
    bind("Super+Shift+S") { run("screenshot") }
    // The star key (F9): notes.
    bind("XF86Favorites") { launch(if (has("obsidian")) "obsidian" else "gnome-text-editor") }
    // The touchpad off while typing (a palm on it moves nothing); Super+F8 switches it.
    input.touchpads.disableWhileTyping = true
    bind("Super+F8") { run("touchpad typing toggle") }

    rule { appId(Regex("^caulis-(wifi|bluetooth|run)$")); floating = true; size(760, 520); center() }
    rule { appId("org.pulseaudio.pavucontrol"); floating = true; size(760, 520); center() }
    rule { appId(Regex("^(blueman-manager|nm-connection-editor)$")); floating = true; center() }
    rule { title("Picture-in-Picture"); pinned = true; takesFocus = false; size(480, 270); keepAwake = true }
    rule { appId("firefox"); title(Regex("YouTube|Twitch|Netflix")); keepAwake = true }
    rule { anyOf { appId("mpv"); appId("vlc") }; keepAwake = true }

    onStart {
        // The bar (Wi-Fi, Bluetooth, sound, battery, the clock), and the tray icons in it.
        if (has("waybar")) launch("waybar -c ${file("waybar.json")} -s ${file("waybar.css")}")
        if (has("nm-applet")) launch("nm-applet --indicator")
        if (has("blueman-applet")) launch("blueman-applet")
        if (has("mako")) launch("mako")
        notify("Super+Return: terminal · Super+B: Firefox · Super+T: Telegram · Super+M: Spotify · Super+N: Wi-Fi · Super+A: sound · Print: screenshot · Ctrl+Alt+BackSpace: quit", title = "Caulis")
    }
}
