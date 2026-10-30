package dev.caulis.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.caulis.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * Compose made ready on a thread of its own, before the compositor's thread needs it.
 *
 * The first Compose scene (a bar) took ~0.7 s of the compositor's thread — Compose's classes
 * loaded and set up, Skia's fonts found, its code still slow — and meanwhile no input was
 * handled and no frame drawn: the pointer stood still. [start] draws a small scene of common
 * parts (rows, columns, text, shapes, shadows, a list) a few times on another thread, at the
 * same time as the compositor starts: that cost is then paid on another core.
 *
 * [content], if given, is drawn instead: it runs on that other thread, so it must not touch
 * the compositor or anything of yours that is not safe from two threads — layout and drawing
 * only (no effects that do something: they are not run).
 */
object ComposeWarmup {

    private val log = Logger("compose")

    /**
     * Starts the warm-up on a thread of its own ("caulis-compose-warmup"; it ends when done)
     * and returns it. [frames]: how often the scene is drawn (later ones run Compose's code
     * once it was compiled).
     */
    @JvmStatic
    @JvmOverloads
    fun start(frames: Int = 3, content: (@Composable () -> Unit)? = null): Thread =
        Thread.ofPlatform().daemon().name("caulis-compose-warmup").start {
            val started = System.nanoTime()
            try {
                run(frames, content ?: { Sample() })
                log.info { "Compose made ready on a thread of its own in ${(System.nanoTime() - started) / 1_000_000} ms" }
            } catch (e: Throwable) {
                if (e is VirtualMachineError && e !is StackOverflowError) throw e
                // (Only time lost: the compositor's thread does it when it needs it.)
                log.warn("Making Compose ready ahead failed: $e")
            }
        }

    /**
     * Draws [content] [frames] times in a scene of [width] x [height] pixels on the calling
     * thread (not the compositor's: that is the point), then forgets it — for a compositor that
     * draws its own menus ahead one after another on a thread of its own. The same rules as for
     * [start]'s content: layout and drawing only; coroutines its effects start run on this
     * thread between the frames. What [content] throws is passed on.
     */
    @JvmStatic
    @JvmOverloads
    fun draw(width: Int, height: Int, frames: Int = 2, content: @Composable () -> Unit) {
        val started = System.nanoTime()
        run(frames, content, width.coerceIn(1, 8192), height.coerceIn(1, 8192))
        log.trace { "Drawn ahead on ${Thread.currentThread().name}: ${width}x$height, $frames frames in ${(System.nanoTime() - started) / 1_000_000} ms" }
    }

    private fun run(frames: Int, content: @Composable () -> Unit, width: Int = WIDTH, height: Int = HEIGHT) {
        // Coroutines the scene starts (none that matter here) are kept and run on this thread
        // between frames — never on the compositor's.
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                tasks += block
            }
        }
        Scene(width, height, Density(1f), dispatcher, onPointerIcon = {}, content = content).use { scene ->
            repeat(frames.coerceAtLeast(1)) { frame ->
                scene.render(System.nanoTime() + frame * 16_000_000L)
                while (true) (tasks.poll() ?: break).run()
            }
        }
    }

    private const val WIDTH = 480
    private const val HEIGHT = 320

    // Common parts of a compositor's interface.
    @Composable
    private fun Sample() {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E2E)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).shadow(4.dp, RoundedCornerShape(9.dp)).background(Color(0xFF89B4FA), RoundedCornerShape(9.dp)))
                Spacer(Modifier.size(6.dp))
                BasicText("12:34 Wi-Fi 87% Ünïcødé Кириллица", style = TextStyle(color = Color.White, fontSize = 13.sp))
                BasicText(" bold", style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
            Box(Modifier.size(120.dp, 40.dp).border(1.dp, Color.Gray, RoundedCornerShape(8.dp)).clickable { })
            LazyColumn(Modifier.size(200.dp, 120.dp)) {
                items(12) { i -> BasicText("Item $i", style = TextStyle(color = Color.LightGray, fontSize = 12.sp)) }
            }
        }
    }
}
