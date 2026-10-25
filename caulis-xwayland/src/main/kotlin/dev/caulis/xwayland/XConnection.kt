package dev.caulis.xwayland

import dev.caulis.ffi.c.xcb.Xcb
import dev.caulis.ffi.c.xcb.xcb_get_geometry_reply_t
import dev.caulis.ffi.c.xcb.xcb_get_property_reply_t
import dev.caulis.ffi.c.xcb.xcb_intern_atom_reply_t
import dev.caulis.ffi.c.xcb.xcb_query_extension_reply_t
import dev.caulis.ffi.c.xcb.xcb_render_directformat_t
import dev.caulis.ffi.c.xcb.xcb_render_pictforminfo_t
import dev.caulis.ffi.c.xcb.xcb_screen_iterator_t
import dev.caulis.ffi.c.xcb.xcb_screen_t
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT

/**
 * The window manager's XCB connection to Xwayland: small, safe calls for what a window
 * manager does (atoms, properties, configuring, mapping, focusing, events). Replies XCB
 * allocates are freed here. Used on the event loop thread only.
 */
internal class XConnection(fd: Int) : AutoCloseable {

    private val connection: MemorySegment = Xcb.xcb_connect_to_fd(fd, MemorySegment.NULL)
    private var open = true

    /** XCB's connection; using it once closed is an error here, never a use of freed memory. */
    val c: MemorySegment get() = if (open) connection else throw IllegalStateException("the X connection is closed")

    /** The root window and what new windows of ours need. */
    val root: Int
    val rootVisual: Int
    val rootDepth: Byte

    /** The bits of a resource id that tell its client apart: windows of one program share the rest. */
    val resourceIdMask: Int

    init {
        val error = Xcb.xcb_connection_has_error(c)
        if (error != 0) {
            Xcb.xcb_disconnect(c) // (a failed connection is still an object of its own to free)
            error("could not connect to Xwayland (error $error)")
        }
        Arena.ofConfined().use { arena ->
            val iterator = Xcb.xcb_setup_roots_iterator(arena, Xcb.xcb_get_setup(c))
            val screen = xcb_screen_iterator_t.data(iterator).reinterpret(xcb_screen_t.layout().byteSize())
            root = xcb_screen_t.root(screen)
            rootVisual = xcb_screen_t.root_visual(screen)
            rootDepth = xcb_screen_t.root_depth(screen)
            resourceIdMask = dev.caulis.ffi.c.xcb.xcb_setup_t.resource_id_mask(Xcb.xcb_get_setup(c).reinterpret(dev.caulis.ffi.c.xcb.xcb_setup_t.layout().byteSize()))
        }
    }

    /** The descriptor to watch for events. */
    val fd: Int get() = Xcb.xcb_get_file_descriptor(c)

    /** True if the connection broke (Xwayland is gone). */
    val broken: Boolean get() = Xcb.xcb_connection_has_error(c) != 0

    fun flush() {
        Xcb.xcb_flush(c)
    }

    fun newId(): Int = Xcb.xcb_generate_id(c)

    // --- atoms ---------------------------------------------------------------------------

    private val atoms = HashMap<String, Int>()
    private val names = HashMap<Int, String>()

    /** Interns all [wanted] atoms in one round trip. */
    fun intern(wanted: Collection<String>) = Arena.ofConfined().use { arena ->
        val cookies = wanted.filter { it !in atoms }.map { name ->
            val bytes = name.toByteArray()
            name to Xcb.xcb_intern_atom(arena, c, 0, bytes.size.toShort(), arena.allocateFrom(JAVA_BYTE, *bytes))
        }
        for ((name, cookie) in cookies) {
            val reply = Xcb.xcb_intern_atom_reply(c, cookie, MemorySegment.NULL)
            if (reply == MemorySegment.NULL) continue
            val atom = xcb_intern_atom_reply_t.atom(reply.reinterpret(xcb_intern_atom_reply_t.layout().byteSize()))
            free(reply)
            atoms[name] = atom
            names[atom] = name
        }
    }

    /** An atom by name (interned on first use). */
    fun atom(name: String): Int {
        atoms[name]?.let { return it }
        intern(listOf(name))
        return atoms[name] ?: 0
    }

    /** The name of an atom we interned, for logs. */
    fun atomName(atom: Int): String = names[atom] ?: "atom $atom"

    /** The names of [wanted] atoms, asking X for those we do not know (one round trip); null where X has none. */
    fun namesOf(wanted: IntArray): List<String?> = Arena.ofConfined().use { arena ->
        val cookies = wanted.filter { it !in names && it != NONE }.distinct().map { it to Xcb.xcb_get_atom_name(arena, c, it) }
        for ((atom, cookie) in cookies) {
            val reply = Xcb.xcb_get_atom_name_reply(c, cookie, MemorySegment.NULL)
            if (reply == MemorySegment.NULL) continue
            val length = Xcb.xcb_get_atom_name_name_length(reply)
            val name = Xcb.xcb_get_atom_name_name(reply).reinterpret(length.toLong()).toArray(JAVA_BYTE).decodeToString()
            free(reply)
            atoms[name] = atom
            names[atom] = name
        }
        wanted.map { names[it] }
    }

    // --- properties ----------------------------------------------------------------------

    /** A property's value: its type, format (8, 16, 32 bits) and bytes. */
    class Property(val type: Int, val format: Int, val bytes: ByteArray) {
        /** The value as 32-bit numbers (format 32). */
        fun ints(): IntArray {
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
            return IntArray(bytes.size / 4) { buffer.getInt(it * 4) }
        }

        fun text(): String = bytes.decodeToString().trimEnd('\u0000')
    }

    /** Reads [property] of [window] (null if it has none); [delete] removes it after reading. */
    fun property(window: Int, property: Int, type: Int = Xcb.XCB_GET_PROPERTY_TYPE_ANY(), maxBytes: Int = 1 shl 20, delete: Boolean = false): Property? =
        Arena.ofConfined().use { arena ->
            val cookie = Xcb.xcb_get_property(arena, c, if (delete) 1 else 0, window, property, type, 0, maxBytes / 4)
            reply(Xcb.xcb_get_property_reply(c, cookie, MemorySegment.NULL))
        }

    /**
     * Reads several properties of [window] at once: all requests go out together, then the
     * answers are collected — one round trip instead of one per property. Absent ones: null.
     */
    fun properties(window: Int, wanted: Collection<Int>, maxBytes: Int = 1 shl 20): Map<Int, Property?> = Arena.ofConfined().use { arena ->
        val cookies = wanted.distinct().map { it to Xcb.xcb_get_property(arena, c, 0, window, it, Xcb.XCB_GET_PROPERTY_TYPE_ANY(), 0, maxBytes / 4) }
        cookies.associate { (atom, cookie) -> atom to reply(Xcb.xcb_get_property_reply(c, cookie, MemorySegment.NULL)) }
    }

    // A get-property reply as a Property (and freed), or null.
    private fun reply(reply: MemorySegment): Property? {
        if (reply == MemorySegment.NULL) return null
        try {
            val header = reply.reinterpret(xcb_get_property_reply_t.layout().byteSize())
            val actualType = xcb_get_property_reply_t.type(header)
            if (actualType == NONE) return null
            val format = xcb_get_property_reply_t.format(header).toInt() and 0xff
            val length = Xcb.xcb_get_property_value_length(reply)
            val value = Xcb.xcb_get_property_value(reply).reinterpret(length.toLong())
            return Property(actualType, format, value.toArray(JAVA_BYTE))
        } finally {
            free(reply)
        }
    }

    /** Sets [property] of [window] to 32-bit [values] of [type]. */
    fun setProperty(window: Int, property: Int, type: Int, values: IntArray) = Arena.ofConfined().use { arena ->
        val data = if (values.isEmpty()) arena.allocate(4) else arena.allocateFrom(JAVA_INT, *values)
        Xcb.xcb_change_property(arena, c, Xcb.XCB_PROP_MODE_REPLACE().toByte(), window, property, type, 32, values.size, data)
    }

    /** Sets [property] of [window] to text (UTF8_STRING or STRING [type]). */
    fun setText(window: Int, property: Int, type: Int, text: String) = setBytes(window, property, type, text.toByteArray())

    /** Sets [property] of [window] to [bytes] of [type] (format 8: e.g. clipboard data). */
    fun setBytes(window: Int, property: Int, type: Int, bytes: ByteArray, from: Int = 0, length: Int = bytes.size) =
        Arena.ofConfined().use { arena ->
            val data = if (length == 0) arena.allocate(1) else arena.allocate(length.toLong()).also {
                MemorySegment.copy(bytes, from, it, JAVA_BYTE, 0, length)
            }
            Xcb.xcb_change_property(arena, c, Xcb.XCB_PROP_MODE_REPLACE().toByte(), window, property, type, 8, length, data)
        }

    fun deleteProperty(window: Int, property: Int) = Arena.ofConfined().use { arena ->
        Xcb.xcb_delete_property(arena, c, window, property)
    }

    // --- windows -------------------------------------------------------------------------

    fun configure(window: Int, mask: Int, values: IntArray) = Arena.ofConfined().use { arena ->
        Xcb.xcb_configure_window(arena, c, window, mask.toShort(), arena.allocateFrom(JAVA_INT, *values))
    }

    fun changeAttributes(window: Int, mask: Int, values: IntArray) = Arena.ofConfined().use { arena ->
        Xcb.xcb_change_window_attributes(arena, c, window, mask, arena.allocateFrom(JAVA_INT, *values))
    }

    /** Changes attributes and waits for the answer: false if X refused (e.g. another window manager runs). */
    fun changeAttributesChecked(window: Int, mask: Int, values: IntArray): Boolean = Arena.ofConfined().use { arena ->
        val cookie = Xcb.xcb_change_window_attributes_checked(arena, c, window, mask, arena.allocateFrom(JAVA_INT, *values))
        val error = Xcb.xcb_request_check(c, cookie)
        if (error == MemorySegment.NULL) return true
        free(error)
        false
    }

    fun map(window: Int) = Arena.ofConfined().use { Xcb.xcb_map_window(it, c, window) }
    fun unmap(window: Int) = Arena.ofConfined().use { Xcb.xcb_unmap_window(it, c, window) }
    fun kill(window: Int) = Arena.ofConfined().use { Xcb.xcb_kill_client(it, c, window) }

    /** Gives X's keyboard to [window] ([POINTER_ROOT]: to no program, keyboard grabs keep working); the request's number. */
    fun focus(window: Int): Int = Arena.ofConfined().use {
        val cookie = Xcb.xcb_set_input_focus(it, c, Xcb.XCB_INPUT_FOCUS_POINTER_ROOT().toByte(), window, Xcb.XCB_CURRENT_TIME().toInt())
        cookie.get(JAVA_INT, 0) // xcb_void_cookie_t.sequence
    }

    /** A small window of ours (e.g. the window-manager check window, a clipboard helper). */
    fun createHelperWindow(): Int = Arena.ofConfined().use { arena ->
        val id = newId()
        Xcb.xcb_create_window(arena, c, 0, id, root, (-1).toShort(), (-1).toShort(), 1, 1, 0, Xcb.XCB_WINDOW_CLASS_INPUT_OUTPUT().toShort(),
            Xcb.XCB_COPY_FROM_PARENT().toInt(), 0, MemorySegment.NULL)
        id
    }

    /** The geometry X has for [window] (x, y, width, height in X pixels), or null. */
    fun geometry(window: Int): IntArray? = Arena.ofConfined().use { arena ->
        val reply = Xcb.xcb_get_geometry_reply(c, Xcb.xcb_get_geometry(arena, c, window), MemorySegment.NULL)
        if (reply == MemorySegment.NULL) return null
        val g = reply.reinterpret(xcb_get_geometry_reply_t.layout().byteSize())
        val result = intArrayOf(
            xcb_get_geometry_reply_t.x(g).toInt(), xcb_get_geometry_reply_t.y(g).toInt(),
            xcb_get_geometry_reply_t.width(g).toInt() and 0xffff, xcb_get_geometry_reply_t.height(g).toInt() and 0xffff,
        )
        free(reply)
        result
    }

    /** Sends a 32-bit client message [type] with [data] to [window] (e.g. WM_DELETE_WINDOW). */
    fun sendClientMessage(window: Int, type: Int, data: IntArray, destination: Int = window, mask: Int = Xcb.XCB_EVENT_MASK_NO_EVENT()) =
        Arena.ofConfined().use { arena ->
            val event = arena.allocate(32)
            event.set(JAVA_BYTE, 0, Xcb.XCB_CLIENT_MESSAGE().toByte()) // response_type
            event.set(JAVA_BYTE, 1, 32) // format
            event.set(JAVA_INT, 4, window)
            event.set(JAVA_INT, 8, type)
            for ((i, value) in data.take(5).withIndex()) event.set(JAVA_INT, 12L + i * 4, value)
            Xcb.xcb_send_event(arena, c, 0, destination, mask, event)
        }

    /** Sends a synthetic ConfigureNotify (what ICCCM asks for when a window manager keeps a window where it is). */
    fun sendConfigureNotify(window: Int, x: Int, y: Int, width: Int, height: Int) = Arena.ofConfined().use { arena ->
        val event = arena.allocate(32)
        event.set(JAVA_BYTE, 0, Xcb.XCB_CONFIGURE_NOTIFY().toByte())
        event.set(JAVA_INT, 4, window) // event
        event.set(JAVA_INT, 8, window) // window
        event.set(JAVA_INT, 12, NONE) // above sibling
        event.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 16, x.toShort())
        event.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 18, y.toShort())
        event.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 20, width.toShort())
        event.set(java.lang.foreign.ValueLayout.JAVA_SHORT, 22, height.toShort())
        Xcb.xcb_send_event(arena, c, 0, window, Xcb.XCB_EVENT_MASK_STRUCTURE_NOTIFY(), event)
    }

    // --- selections (the clipboard) ------------------------------------------------------

    fun setSelectionOwner(owner: Int, selection: Int) = Arena.ofConfined().use { arena ->
        Xcb.xcb_set_selection_owner(arena, c, owner, selection, Xcb.XCB_CURRENT_TIME().toInt())
    }

    /** Asks the owner of [selection] to put it as [target] into [property] of [requestor]. */
    fun convertSelection(requestor: Int, selection: Int, target: Int, property: Int, time: Int = Xcb.XCB_CURRENT_TIME().toInt()) =
        Arena.ofConfined().use { arena -> Xcb.xcb_convert_selection(arena, c, requestor, selection, target, property, time) }

    /** Tells [requestor] its selection request is answered in [property] (or refused: [NONE]). */
    fun sendSelectionNotify(requestor: Int, selection: Int, target: Int, property: Int, time: Int) = Arena.ofConfined().use { arena ->
        val event = arena.allocate(32)
        event.set(JAVA_BYTE, 0, Xcb.XCB_SELECTION_NOTIFY().toByte())
        event.set(JAVA_INT, 4, time)
        event.set(JAVA_INT, 8, requestor)
        event.set(JAVA_INT, 12, selection)
        event.set(JAVA_INT, 16, target)
        event.set(JAVA_INT, 20, property)
        Xcb.xcb_send_event(arena, c, 0, requestor, Xcb.XCB_EVENT_MASK_NO_EVENT(), event)
    }

    /** Starts XFixes and asks it to tell [window] when [selection] gets a new owner. */
    fun watchSelection(window: Int, selection: Int) = Arena.ofConfined().use { arena ->
        Xcb.xcb_xfixes_query_version_reply(c, Xcb.xcb_xfixes_query_version(arena, c, 5, 0), MemorySegment.NULL)
            .takeIf { it != MemorySegment.NULL }?.let(::free)
        Xcb.xcb_xfixes_select_selection_input(arena, c, window, selection,
            Xcb.XCB_XFIXES_SELECTION_EVENT_MASK_SET_SELECTION_OWNER() or Xcb.XCB_XFIXES_SELECTION_EVENT_MASK_SELECTION_WINDOW_DESTROY() or
                Xcb.XCB_XFIXES_SELECTION_EVENT_MASK_SELECTION_CLIENT_CLOSE())
    }

    /**
     * Our connection should not keep Xwayland running: when the last program leaves, it may
     * stop (XFixes 6). False if this Xwayland cannot.
     */
    fun letServerTerminate(): Boolean = Arena.ofConfined().use { arena ->
        val reply = Xcb.xcb_xfixes_query_version_reply(c, Xcb.xcb_xfixes_query_version(arena, c, 6, 0), MemorySegment.NULL)
        if (reply == MemorySegment.NULL) return false
        val major = dev.caulis.ffi.c.xcb.xcb_xfixes_query_version_reply_t.major_version(reply.reinterpret(dev.caulis.ffi.c.xcb.xcb_xfixes_query_version_reply_t.layout().byteSize()))
        free(reply)
        if (major < 6) return false
        Xcb.xcb_xfixes_set_client_disconnect_mode(arena, c, Xcb.XCB_XFIXES_CLIENT_DISCONNECT_FLAGS_TERMINATE())
        true
    }

    // --- the root window's cursor (RENDER) ------------------------------------------------

    // The 32-bit picture format with alpha, found once (0: none).
    private val argbFormat: Int by lazy {
        Arena.ofConfined().use { arena ->
            val reply = Xcb.xcb_render_query_pict_formats_reply(c, Xcb.xcb_render_query_pict_formats(arena, c), MemorySegment.NULL)
            if (reply == MemorySegment.NULL) return@use 0
            try {
                val count = Xcb.xcb_render_query_pict_formats_formats_length(reply)
                val size = xcb_render_pictforminfo_t.layout().byteSize()
                val formats = Xcb.xcb_render_query_pict_formats_formats(reply).reinterpret(size * count)
                (0 until count).map { formats.asSlice(it * size, size) }.firstOrNull {
                    xcb_render_pictforminfo_t.depth(it).toInt() == 32 && xcb_render_pictforminfo_t.type(it).toInt() == Xcb.XCB_RENDER_PICT_TYPE_DIRECT() &&
                        xcb_render_directformat_t.alpha_mask(xcb_render_pictforminfo_t.direct(it)).toInt() != 0
                }?.let(xcb_render_pictforminfo_t::id) ?: 0
            } finally {
                free(reply)
            }
        }
    }

    private var rootCursor = NONE

    /**
     * Sets the cursor X shows over windows that do not choose their own: [pixels] are
     * premultiplied 0xAARRGGBB, row by row. False if X cannot show colour cursors.
     */
    fun setRootCursor(width: Int, height: Int, hotspotX: Int, hotspotY: Int, pixels: IntArray): Boolean = Arena.ofConfined().use { arena ->
        val format = argbFormat
        if (format == 0 || width <= 0 || height <= 0) return false
        val pixmap = newId()
        Xcb.xcb_create_pixmap(arena, c, 32, pixmap, root, width.toShort(), height.toShort())
        val picture = newId()
        Xcb.xcb_render_create_picture(arena, c, picture, pixmap, format, 0, MemorySegment.NULL)
        val gc = newId()
        Xcb.xcb_create_gc(arena, c, gc, pixmap, 0, MemorySegment.NULL)
        val data = arena.allocateFrom(JAVA_INT, *pixels)
        Xcb.xcb_put_image(arena, c, Xcb.XCB_IMAGE_FORMAT_Z_PIXMAP().toByte(), pixmap, gc, width.toShort(), height.toShort(), 0, 0, 0, 32,
            pixels.size * 4, data)
        Xcb.xcb_free_gc(arena, c, gc)
        val cursor = newId()
        Xcb.xcb_render_create_cursor(arena, c, cursor, picture, hotspotX.toShort(), hotspotY.toShort())
        Xcb.xcb_free_pixmap(arena, c, pixmap)
        Xcb.xcb_render_free_picture(arena, c, picture)
        changeAttributes(root, Xcb.XCB_CW_CURSOR(), intArrayOf(cursor))
        if (rootCursor != NONE) Xcb.xcb_free_cursor(arena, c, rootCursor)
        rootCursor = cursor
        true
    }

    /** The first event number of the XFixes extension (its events are numbered from there). */
    fun xfixesFirstEvent(): Int {
        val id = SymbolLookup.libraryLookup("libxcb-xfixes.so.0", Arena.global()).find("xcb_xfixes_id").orElse(null) ?: return -1
        val reply = Xcb.xcb_get_extension_data(c, id)
        if (reply == MemorySegment.NULL) return -1
        val data = reply.reinterpret(xcb_query_extension_reply_t.layout().byteSize())
        if (xcb_query_extension_reply_t.present(data).toInt() == 0) return -1
        return xcb_query_extension_reply_t.first_event(data).toInt() and 0xff
    }

    // --- events --------------------------------------------------------------------------

    /** The next event (32 bytes; free with [free]), or null if none is waiting. */
    fun poll(): MemorySegment? {
        val event = Xcb.xcb_poll_for_event(c)
        return if (event == MemorySegment.NULL) null else event.reinterpret(36)
    }

    fun free(pointer: MemorySegment) {
        FREE.invokeExact(pointer)
    }

    override fun close() {
        if (!open) return
        open = false
        Xcb.xcb_disconnect(connection)
    }

    companion object {
        /** X's "nothing" (no window, no atom). */
        const val NONE = 0

        /** Focus "to the root window" (no program has the keyboard, but keyboard grabs work). */
        const val POINTER_ROOT = 1

        private val FREE: java.lang.invoke.MethodHandle = Linker.nativeLinker().let { linker ->
            linker.downcallHandle(linker.defaultLookup().find("free").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS))
        }
    }
}
