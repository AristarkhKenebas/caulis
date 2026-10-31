import dev.caulis.core.Effects
import dev.caulis.core.TitleBars
import dev.caulis.tiling.Monocle
import dev.caulis.wm.caulis

// A kiosk: one program, filling the screen, started again if it ends. Nobody at the screen can do
// anything else. For an information screen, a ticket machine, a shop's terminal.
// Start it with ./gradlew run, then change anything and save: the change is taken on at once.
// The guide: docs/getting-started.md in the Caulis folder.

// The one program. Started with its arguments; a browser in its kiosk mode is a good choice:
// "firefox --kiosk https://example.org"
val theProgram = System.getenv("KIOSK_PROGRAM") ?: "foot"

fun main() = caulis {
    title = "My kiosk"
    configName = "my-compositor"            // your folder of files: ~/.config/my-compositor

    layout = Monocle()                      // every window fills the screen
    titleBars = TitleBars.NONE
    effects = Effects.NONE

    keys {
        off()                               // none of the usual keys ...
        // ... except these two, which you want while trying it out. For the real thing, in front
        // of strangers, turn them off too: then nothing on the keyboard leaves the kiosk.
        // quit = null                      // Ctrl+Alt+Backspace: quits
        // consoles = null                  // Ctrl+Alt+F1..F12: text consoles
    }

    // A key of your own, doing nothing. (With no key at all Caulis keeps a way out, so a broken
    // configuration never locks you in: Super+Shift+Escape quits, Super+Return opens a terminal.)
    bind("Ctrl+Alt+Shift+F12") { }

    plugins { enabled = false }             // a kiosk runs nothing but what it was given

    hideCursorAfterSeconds = 5              // the pointer out of sight when it is not used; 0: always there

    // Every window of the program full-screen, popups included.
    rule { fullscreen = true }

    onStart { launch(theProgram) }

    // It ended (or crashed): a moment later, again.
    onWindowClosed {
        if (desktop.windows.isEmpty()) after(1000) { if (desktop.windows.isEmpty()) launch(theProgram) }
    }
}
