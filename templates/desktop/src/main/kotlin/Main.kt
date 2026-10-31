import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.caulis.compose.compose
import dev.caulis.core.Edge
import dev.caulis.core.Effects
import dev.caulis.core.Reserve
import dev.caulis.core.TitleBars
import dev.caulis.geometry.Rect
import dev.caulis.tiling.Dwindle
import dev.caulis.tiling.Gaps
import dev.caulis.wm.Caulis
import dev.caulis.wm.caulis
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// A small whole desktop: tiled windows and a bar of your own, written in Compose. The bar is drawn
// by the compositor itself: no extra program, no window (about 45 MB more for the first one).
// Start it with ./gradlew run, then change anything and save: the change is taken on at once.
// The guide: docs/getting-started.md in the Caulis folder ("Your own interface in Compose").

// The bar's height and colours: yours to change.
private const val BAR_HEIGHT = 32
private val barColor = Color(0xE6161A24)
private val textColor = Color(0xFFE6E9F0)
private val accentColor = Color(0xFF7AA2F7)

// What the bar shows; Compose draws it anew when these change.
private var workspaceNow by mutableIntStateOf(1)
private var windowTitle by mutableStateOf("")

fun main() = caulis {
    title = "My desktop"
    configName = "my-compositor"            // your folder of files: ~/.config/my-compositor

    layout = Dwindle(Gaps(inner = 8, outer = 8))
    effects = Effects.MODERN                // round corners, a border, soft shadows
    titleBars = TitleBars.NONE
    terminal = "foot"                       // what Super+Return opens
    keyboardLayouts = "us"                  // as for setxkbmap: "us,de", "us,ru" ...

    bind("Super+D") { launch("fuzzel") }    // a launcher, any you have installed

    // The bar, on each monitor; windows make way for it (reserve).
    compose(
        place = { monitor -> Rect(monitor.area.x, monitor.area.y, monitor.area.width, BAR_HEIGHT) },
        reserve = { Reserve(Edge.TOP, BAR_HEIGHT) },
    ) {
        Bar(this@caulis)
    }

    // Keep what the bar shows up to date.
    onWorkspaceChanged { number -> workspaceNow = number }
    onFocusChanged { window -> windowTitle = window?.title.orEmpty() }
    onWindowChanged { window -> if (window === desktop.focusedWindow) windowTitle = window.title.orEmpty() }

    onStart { openTerminal() }
}

// Workspaces at the left, the window's title after them, the clock at the right.
@Composable
private fun Bar(caulis: Caulis) {
    val text = TextStyle(color = textColor, fontSize = 13.sp)
    Row(
        Modifier.fillMaxSize().background(barColor).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (number in 1..5) {
            val here = number == workspaceNow
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (here) accentColor else Color.Transparent)
                    .clickable { caulis.run("workspace $number") },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("$number", style = text.copy(color = if (here) barColor else textColor))
            }
        }
        BasicText("  $windowTitle", Modifier.weight(1f), style = text, maxLines = 1)
        Spacer(Modifier.size(8.dp))
        Clock(text)
    }
}

// The time, looked at twice a minute.
@Composable
private fun Clock(style: TextStyle) {
    val format = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var now by remember { mutableStateOf(LocalTime.now().format(format)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now().format(format)
            delay(30_000)
        }
    }
    BasicText(now, style = style)
}
