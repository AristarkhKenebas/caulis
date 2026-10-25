package dev.caulis.wm

import dev.caulis.core.NoticeLevel
import dev.caulis.ffi.Fd
import dev.caulis.ffi.NativeException
import dev.caulis.ffi.UnixListener
import dev.caulis.ffi.UnixSocket
import dev.caulis.ipc.v1.ConfigReloaded
import dev.caulis.ipc.v1.Done
import dev.caulis.ipc.v1.Event
import dev.caulis.ipc.v1.EventKind
import dev.caulis.ipc.v1.Message
import dev.caulis.ipc.v1.Monitors
import dev.caulis.ipc.v1.Notice
import dev.caulis.ipc.v1.Problems
import dev.caulis.ipc.v1.Reply
import dev.caulis.ipc.v1.Request
import dev.caulis.ipc.v1.ServerInfo
import dev.caulis.ipc.v1.State
import dev.caulis.ipc.v1.WorkspaceShown
import dev.caulis.log.Logger
import dev.caulis.wm.config.Actions
import dev.caulis.wm.config.ConfigFile
import java.io.ByteArrayOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import dev.caulis.core.Monitor as DesktopMonitor
import dev.caulis.core.Window as DesktopWindow
import dev.caulis.geometry.Rect as DesktopRect
import dev.caulis.ipc.v1.Monitor as MonitorMessage
import dev.caulis.ipc.v1.Rect as RectMessage
import dev.caulis.ipc.v1.Window as WindowMessage
import dev.caulis.ipc.v1.Workspace as WorkspaceMessage

/**
 * The IPC socket (caulis.proto): other programs run actions, ask what is on screen, and hear
 * what changes. Lives on the compositor's loop, like the Wayland socket.
 */
internal class IpcServer(private val running: Running, val path: Path) : AutoCloseable {

    private val log = Logger("ipc")
    private val loop get() = running.loop
    private val desktop get() = running.desktop
    private val listener: UnixListener
    private val connections = ArrayList<Connection>()
    private val ownUid = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int


    init {
        Files.deleteIfExists(path)
        listener = UnixListener.listen(path)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        loop.add(listener.fd) { accept() }
        // Anything that may change what subscribers see: compare once, at the end of the batch.
        desktop.onWindowOpened { changed() }
        desktop.onWindowClosed { changed() }
        desktop.onWindowInfoChanged { changed() }
        desktop.onWindowListChanged { changed() }
        desktop.onFocusChanged { changed() }
        desktop.onWorkspaceChanged { changed() }
        desktop.onLockChanged { changed() }
        desktop.onNotice { notice ->
            send(EventKind.EVENT_KIND_NOTICES) {
                setNotice(
                    Notice.newBuilder().setLevel(
                        when (notice.level) {
                            NoticeLevel.INFO -> Notice.Level.LEVEL_INFO
                            NoticeLevel.WARNING -> Notice.Level.LEVEL_WARNING
                            NoticeLevel.ERROR -> Notice.Level.LEVEL_ERROR
                        },
                    ).setTitle(notice.title.orEmpty()).setText(notice.text),
                )
            }
        }
        log.info("Listening on $path")
    }

    // --- connections ---------------------------------------------------------------------------

    private inner class Connection(val socket: UnixSocket, val pid: Int) {
        val arena: Arena = Arena.ofShared()
        val buffer: MemorySegment = arena.allocate(READ_SIZE)
        var received = ByteArray(READ_SIZE.toInt())
        var receivedLength = 0
        val pending = ArrayDeque<ByteArray>()
        var pendingOffset = 0
        var pendingBytes = 0L
        // The kinds of events it wants (null: none; empty: all).
        var kinds: Set<EventKind>? = null
        var open = true

        fun wants(kind: EventKind) = kinds?.let { it.isEmpty() || kind in it } ?: false
    }

    private fun accept() {
        while (true) {
            val socket = try {
                listener.accept() ?: return
            } catch (e: NativeException) {
                log.warn("Cannot accept a program: ${e.message}")
                return
            }
            val peer = try {
                socket.peerCredentials()
            } catch (e: NativeException) {
                socket.close()
                continue
            }
            if (peer.uid != ownUid) {
                // (The socket is the user's alone already; this also holds where it is not.)
                log.warn("Refused a program of another user (uid ${peer.uid}, pid ${peer.pid})")
                socket.close()
                continue
            }
            val connection = Connection(socket, peer.pid)
            connections += connection
            log.trace { "Program ${peer.pid} connected" }
            loop.add(socket.fd) { ready ->
                if (ready.readable || ready.hangUp || ready.error) read(connection)
                if (connection.open && ready.writable) write(connection)
            }
        }
    }

    private fun read(connection: Connection) {
        while (connection.open) {
            val count = try {
                connection.socket.receive(connection.buffer, NO_FDS)
            } catch (e: NativeException) {
                return disconnect(connection, "reading failed: ${e.message}")
            }
            if (count < 0) return // nothing more now
            if (count == 0L) return disconnect(connection, null)
            if (NO_FDS.isNotEmpty()) NO_FDS.onEach(Fd::close).clear()
            if (connection.receivedLength + count > connection.received.size) {
                connection.received = connection.received.copyOf(maxOf(connection.received.size * 2, connection.receivedLength + count.toInt()))
            }
            MemorySegment.copy(connection.buffer, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, connection.received, connection.receivedLength, count.toInt())
            connection.receivedLength += count.toInt()
            takeRequests(connection)
        }
    }

    // Every whole message received so far: answered in order.
    private fun takeRequests(connection: Connection) {
        var at = 0
        while (connection.open) {
            // The size, as a varint.
            var size = 0L
            var shift = 0
            var p = at
            while (true) {
                if (p >= connection.receivedLength) { size = -1; break }
                val b = connection.received[p++].toInt()
                size = size or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 28) return disconnect(connection, "a message size that makes no sense")
            }
            if (size < 0) break
            if (size > MAX_MESSAGE) return disconnect(connection, "a message of $size bytes (at most $MAX_MESSAGE)")
            if (p + size > connection.receivedLength) break
            val request = try {
                Request.parseFrom(java.nio.ByteBuffer.wrap(connection.received, p, size.toInt()))
            } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
                return disconnect(connection, "not a Request message: ${e.message}")
            }
            at = p + size.toInt()
            reply(connection, answer(connection, request))
        }
        if (at > 0) {
            connection.received.copyInto(connection.received, 0, at, connection.receivedLength)
            connection.receivedLength -= at
        }
    }

    private fun answer(connection: Connection, request: Request): Reply {
        val reply = Reply.newBuilder().setId(request.id)
        try {
            when (request.whatCase) {
                Request.WhatCase.HELLO -> reply.setHello(
                    ServerInfo.newBuilder()
                        .setName(running.config.configName)
                        .setCaulisVersion(CAULIS_VERSION)
                        .setProtocol(1)
                        .setWaylandDisplay(running.socket)
                        .setX11Display(running.x11Display.orEmpty()),
                )
                Request.WhatCase.RUN -> {
                    run(request.run.action, request.run.window)?.let { reply.setError(it) } ?: reply.setDone(Done.getDefaultInstance())
                }
                Request.WhatCase.GET_STATE -> reply.setState(state())
                Request.WhatCase.SUBSCRIBE -> {
                    if (connections.none { it.kinds != null }) takeSnapshot()
                    connection.kinds = request.subscribe.kindsList.filter { it != EventKind.EVENT_KIND_UNSPECIFIED && it != EventKind.UNRECOGNIZED }.toSet()
                    reply.setDone(Done.getDefaultInstance())
                }
                Request.WhatCase.GET_DEVICES -> reply.setDevices(
                    dev.caulis.ipc.v1.Devices.newBuilder().addAllDevices(
                        running.inputDevices?.list.orEmpty().map { d ->
                            dev.caulis.ipc.v1.Device.newBuilder().setName(d.name).addAllKinds(d.kinds.map { it.name.lowercase() }).build()
                        },
                    ),
                )
                Request.WhatCase.CHECK_CONFIG -> reply.setProblems(Problems.newBuilder().addAllProblems(ConfigFile.read(request.checkConfig.text).problems))
                else -> reply.setError("an empty request (or one of a newer protocol)")
            }
        } catch (e: Exception) {
            log.warn("A request failed: $e")
            reply.clearWhat().setError("it failed: ${e.message ?: e}")
        }
        return reply.build()
    }

    // Runs [text] on the window with [windowId] (0: the focused one); what went wrong, or null.
    private fun run(text: String, windowId: Long): String? {
        val action = try {
            Actions.parseFor(text)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "not an action"
        }
        if (desktop.locked && text.trim().substringBefore(' ').lowercase() !in WHILE_LOCKED) {
            return "the screen is locked (only ${WHILE_LOCKED.joinToString()} then)"
        }
        val window = if (windowId == 0L) null else running.windowIds.window(windowId) ?: return "there is no window $windowId"
        log.info { "Run \"$text\"${if (window != null) " on window $windowId" else ""}" }
        running.config.action(window)
        changed()
        return null
    }

    private fun reply(connection: Connection, reply: Reply) = enqueue(connection, Message.newBuilder().setReply(reply).build())

    private fun enqueue(connection: Connection, message: Message) {
        if (!connection.open) return
        val bytes = ByteArrayOutputStream(message.serializedSize + 5).also { message.writeDelimitedTo(it) }.toByteArray()
        if (connection.pendingBytes + bytes.size > MAX_PENDING) {
            return disconnect(connection, "it does not read what it is sent (${connection.pendingBytes / 1024} KiB waiting)")
        }
        val wasEmpty = connection.pending.isEmpty()
        connection.pending.addLast(bytes)
        connection.pendingBytes += bytes.size
        if (wasEmpty) write(connection)
    }

    private fun write(connection: Connection) {
        while (connection.open && connection.pending.isNotEmpty()) {
            val bytes = connection.pending.first()
            val left = bytes.size - connection.pendingOffset
            val sent = try {
                Arena.ofConfined().use { temp ->
                    val data = temp.allocate(left.toLong())
                    MemorySegment.copy(bytes, connection.pendingOffset, data, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, left)
                    connection.socket.send(data)
                }
            } catch (e: NativeException) {
                return disconnect(connection, null)
            }
            if (sent < 0) {
                // Full: go on when it can take more.
                loop.update(connection.socket.fd, read = true, write = true)
                return
            }
            connection.pendingOffset += sent.toInt()
            connection.pendingBytes -= sent
            if (connection.pendingOffset == bytes.size) {
                connection.pending.removeFirst()
                connection.pendingOffset = 0
            }
        }
        if (connection.open) loop.update(connection.socket.fd, read = true, write = false)
    }

    private fun disconnect(connection: Connection, why: String?) {
        if (!connection.open) return
        connection.open = false
        if (why != null) log.warn("Disconnected program ${connection.pid}: $why") else log.trace { "Program ${connection.pid} went away" }
        loop.remove(connection.socket.fd)
        connection.socket.close()
        connection.arena.close()
        connections -= connection
    }

    override fun close() {
        for (connection in connections.toList()) disconnect(connection, null)
        loop.remove(listener.fd)
        listener.close()
    }

    // --- what is on screen ---------------------------------------------------------------------

    private fun idOf(window: DesktopWindow): Long = running.windowIds.of(window)

    private fun rect(r: DesktopRect) = RectMessage.newBuilder().setX(r.x).setY(r.y).setWidth(r.width).setHeight(r.height).build()

    private fun window(window: DesktopWindow, withPlace: Boolean): WindowMessage {
        val builder = WindowMessage.newBuilder()
            .setId(idOf(window))
            .setAppId(window.appId)
            .setTitle(window.title)
            .setPid(window.pid)
            .setWorkspace(desktop.workspaceNumberOf(window) ?: 0)
            .setMonitor(desktop.monitorOf(window).name)
            .setFocused(window === desktop.focusedWindow)
            .setFloating(desktop.isFloating(window))
            .setMaximized(window.maximized)
            .setFullscreen(window.fullscreen)
            .setMinimized(desktop.isMinimized(window))
            .setX11(window is dev.caulis.xwayland.XWindow)
            .setUrgent(window.urgent)
        if (withPlace) builder.setRect(rect(DesktopRect(window.x, window.y, window.visibleRect.width, window.visibleRect.height)))
        return builder.build()
    }

    private fun monitor(monitor: DesktopMonitor): MonitorMessage = MonitorMessage.newBuilder()
        .setName(monitor.name)
        .setArea(rect(monitor.area))
        .setUsableArea(rect(monitor.usableArea))
        .setScale(monitor.scale)
        .setPixelWidth(monitor.pixelWidth)
        .setPixelHeight(monitor.pixelHeight)
        .setWorkspace(monitor.workspace.number)
        .setFocused(monitor === desktop.focusedMonitor)
        .setPrimary(monitor === desktop.primaryMonitor)
        .build()

    private fun windows() = desktop.allWindows.filter { it.mapped }

    fun state(): State = State.newBuilder()
        .addAllWindows(windows().map { window(it, withPlace = true) })
        .addAllWorkspaces(
            desktop.allWorkspaces.map { workspace ->
                WorkspaceMessage.newBuilder()
                    .setNumber(workspace.number)
                    .setMonitor(desktop.monitors.firstOrNull { it.workspace === workspace }?.name.orEmpty())
                    .setActive(workspace === desktop.activeWorkspace)
                    .setWindows(workspace.windows.size + workspace.minimized.size)
                    .setLayout(workspace.layout.name)
                    .setName(workspace.name.orEmpty())
                    .build()
            },
        )
        .addAllMonitors(desktop.monitors.map(::monitor))
        .setFocusedWindow(desktop.focusedWindow?.let(::idOf) ?: 0)
        .setFocusedMonitor(desktop.focusedMonitor.name)
        .setScale(desktop.scale)
        .setLocked(desktop.locked)
        .setPrimaryMonitor(desktop.primaryMonitor.name)
        .setKeyMode(running.keyMode.orEmpty())
        .build()

    // --- what changes ----------------------------------------------------------------------------

    // What subscribers last heard (compared with the desktop to find what changed).
    private var shownWindows = LinkedHashMap<Long, WindowMessage>()
    private var shownFocus = 0L
    private var shownWorkspaces = HashMap<String, Int>()
    private var shownMonitors = emptyList<MonitorMessage>()
    private var shownLocked = false
    private var comparing = false

    private fun takeSnapshot() {
        shownWindows = LinkedHashMap(windows().associate { idOf(it) to window(it, withPlace = false) })
        shownFocus = desktop.focusedWindow?.let(::idOf) ?: 0
        shownWorkspaces = HashMap(desktop.monitors.associate { it.name to it.workspace.number })
        shownMonitors = desktop.monitors.map(::monitor)
        shownLocked = desktop.locked
    }

    /** Something may have changed (monitors, scale, ...): subscribers hear what did, once the batch is done. */
    fun changed() {
        if (comparing || connections.none { it.kinds != null }) return
        comparing = true
        loop.post {
            comparing = false
            compare()
        }
    }

    private fun compare() {
        if (connections.none { it.kinds != null }) return
        val now = LinkedHashMap(windows().associate { idOf(it) to window(it, withPlace = false) })
        for (id in shownWindows.keys - now.keys) send(EventKind.EVENT_KIND_WINDOWS) { setWindowClosed(id) }
        for ((id, window) in now) {
            val before = shownWindows[id]
            when {
                before == null -> send(EventKind.EVENT_KIND_WINDOWS) { setWindowOpened(window) }
                // (Focus has its own event.)
                before != window && before.toBuilder().setFocused(window.focused).build() != window -> send(EventKind.EVENT_KIND_WINDOWS) { setWindowChanged(window) }
            }
        }
        shownWindows = now
        val focus = desktop.focusedWindow?.let(::idOf) ?: 0
        if (focus != shownFocus) send(EventKind.EVENT_KIND_FOCUS) { setFocusChanged(focus) }
        shownFocus = focus
        val workspaces = HashMap(desktop.monitors.associate { it.name to it.workspace.number })
        for ((monitor, number) in workspaces) {
            if (shownWorkspaces[monitor] != number) send(EventKind.EVENT_KIND_WORKSPACES) { setWorkspaceShown(WorkspaceShown.newBuilder().setNumber(number).setMonitor(monitor)) }
        }
        shownWorkspaces = workspaces
        val monitors = desktop.monitors.map(::monitor)
        // (Focus and workspaces have their own events.)
        fun place(list: List<MonitorMessage>) = list.map { it.toBuilder().clearFocused().clearWorkspace().build() }
        if (place(monitors) != place(shownMonitors)) {
            send(EventKind.EVENT_KIND_MONITORS) { setMonitorsChanged(Monitors.newBuilder().addAllMonitors(monitors)) }
        }
        shownMonitors = monitors
        if (desktop.locked != shownLocked) send(EventKind.EVENT_KIND_LOCK) { setLockChanged(desktop.locked) }
        shownLocked = desktop.locked
    }

    /** Another key mode is on (null: the usual keys). */
    fun keyModeChanged(mode: String?) = send(EventKind.EVENT_KIND_KEYS) { setKeyModeChanged(mode.orEmpty()) }

    /** The configuration was loaded again, with these [problems]. */
    fun configReloaded(problems: List<String>) {
        send(EventKind.EVENT_KIND_CONFIG) { setConfigReloaded(ConfigReloaded.newBuilder().addAllProblems(problems)) }
        changed()
    }

    private fun send(kind: EventKind, fill: Event.Builder.() -> Unit) {
        val wanting = connections.filter { it.wants(kind) }
        if (wanting.isEmpty()) return
        val message = Message.newBuilder().setEvent(Event.newBuilder().apply(fill)).build()
        for (connection in wanting) enqueue(connection, message)
    }

    companion object {
        private const val READ_SIZE = 64 * 1024L
        private const val MAX_MESSAGE = 1024 * 1024L
        private const val MAX_PENDING = 8 * 1024 * 1024L

        // Actions a program may run while the screen is locked.
        private val WHILE_LOCKED = setOf("exec", "lock", "notify", "dump-state")

        // (Descriptors are not part of this protocol: any that come are closed.)
        private val NO_FDS = ArrayList<Fd>()

        /** The Caulis version (from the jar; "development" when running from compiled classes). */
        val CAULIS_VERSION: String = IpcServer::class.java.`package`?.implementationVersion ?: "development"

        /** Where the socket of the compositor [name] with Wayland socket [display] is. */
        fun socketPath(runtimeDir: Path, name: String, display: String): Path = runtimeDir.resolve("$name-$display.sock")
    }
}
