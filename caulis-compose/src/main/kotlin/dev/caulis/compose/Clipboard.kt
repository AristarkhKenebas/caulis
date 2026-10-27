package dev.caulis.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import dev.caulis.core.DataDeviceManager
import dev.caulis.core.TextOnClipboard
import dev.caulis.core.readClipboard
import dev.caulis.log.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.coroutines.resume

/**
 * The compositor's clipboard for Compose's text fields (Ctrl+C, Ctrl+X, Ctrl+V): what is copied
 * in a widget can be pasted in programs and the other way round. (Compose on its own would use
 * Java's clipboard, which a compositor without a window of its own does not have.) Text only.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class CompositorClipboard(private val clipboard: DataDeviceManager, private val log: Logger) : Clipboard {

    // (Called on the compositor's thread: Compose runs there.)
    override suspend fun getClipEntry(): ClipEntry? {
        val data = clipboard.clipboard ?: return null
        val type = TEXT_TYPES.firstOrNull { it in data.mimeTypes } ?: return null
        // Read on a thread of its own (the program that copied it writes it); then on here.
        val text = suspendCancellableCoroutine { waiting ->
            readClipboard(data, type, MAX_BYTES) { bytes -> waiting.resume(bytes?.toString(Charsets.UTF_8)) }
        }
        log.trace { "Pasted into a Compose widget: ${text?.length ?: 0} characters" }
        return text?.let { ClipEntry(StringSelection(it)) }
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val transferable = clipEntry?.asAwtTransferable
        val text = if (transferable?.isDataFlavorSupported(DataFlavor.stringFlavor) == true) transferable.getTransferData(DataFlavor.stringFlavor) as? String else null
        log.trace { "Copied from a Compose widget: ${text?.length ?: 0} characters" }
        clipboard.setClipboard(text?.let(::TextOnClipboard))
    }

    private companion object {
        // Text, in the names programs offer it (UTF-8 first).
        val TEXT_TYPES = listOf("text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "STRING", "TEXT")
        const val MAX_BYTES = 4 shl 20
    }
}
