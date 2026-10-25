package dev.caulis.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import dev.caulis.core.Edge
import dev.caulis.core.Reserve
import dev.caulis.geometry.Rect
import dev.caulis.wm.Caulis

// The guide's example (step 22), compiled with the tests so it stays right.
@Suppress("unused")
internal fun Caulis.guideExample() {
    compose(place = { m -> Rect(m.area.x, m.area.y, m.area.width, 32) }, reserve = { Reserve(Edge.TOP, 32) }) { monitor ->
        var clicks by remember { mutableIntStateOf(0) }
        Row(Modifier.fillMaxSize().background(Color(0xE01E1E2E)), verticalAlignment = Alignment.CenterVertically) {
            BasicText("  ${monitor.name}", style = TextStyle(color = Color.White))
            Spacer(Modifier.weight(1f))
            BasicText("clicked $clicks  ", Modifier.clickable { clicks++ }, style = TextStyle(color = Color.White))
        }
    }
}
