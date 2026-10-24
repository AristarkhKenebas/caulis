package dev.caulis.core.session

import dev.caulis.event.EventLoop
import dev.caulis.ffi.CStrings
import dev.caulis.ffi.Fd
import dev.caulis.ffi.Upcalls
import dev.caulis.ffi.c.seat.Seat
import dev.caulis.ffi.c.seat.libseat_seat_listener
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Path

/**
 * The login session on this seat (through libseat: seatd or systemd-logind).
 *
 * It opens the GPU and input devices for us (no root needed) and tells us when the user
 * switches to another virtual terminal ([active] becomes false: stop drawing, let go of
 * the devices) and back (true: take over again, redraw everything).
 */
class Session private constructor(private val loop: EventLoop) : AutoCloseable {

    // Callbacks C calls into must stay alive as long as the seat: they live in this arena.
    private val arena = Arena.ofShared()
    private val seat: MemorySegment
    private val fd: Fd

    /** True while this session is in front (its virtual terminal is shown). */
    var active = false
        private set

    private val activeListeners = ArrayList<(Boolean) -> Unit>()

    /** Runs [listener] whenever the session becomes active or inactive. */
    fun onActiveChanged(listener: (Boolean) -> Unit) {
        activeListeners += listener
    }

    /** The seat's name, e.g. "seat0". */
    val seatName: String

    /** A device opened through the session; close it with [closeDevice]. */
    class Device internal constructor(val path: Path, val fd: Fd, internal val id: Int)

    private val devices = ArrayList<Device>()

    init {
        Seat.libseat_set_log_level(Seat.LIBSEAT_LOG_LEVEL_ERROR())
        val listener = libseat_seat_listener.allocate(arena)
        libseat_seat_listener.enable_seat(listener, libseat_seat_listener.enable_seat.allocate({ _, _ ->
            Upcalls.guard("enable_seat") { setActive(true) }
        }, arena))
        libseat_seat_listener.disable_seat(listener, libseat_seat_listener.disable_seat.allocate({ seat, _ ->
            Upcalls.guard("disable_seat") {
                setActive(false)
                // Tell libseat we let go; only then may the other session take over.
                Seat.libseat_disable_seat(seat)
            }
        }, arena))
        seat = Seat.libseat_open_seat(listener, MemorySegment.NULL)
        if (seat == MemorySegment.NULL) {
            arena.close()
            throw SessionException(
                "Could not open the seat. Start irisWM from a text console (e.g. Ctrl+Alt+F3) " +
                    "of a logged-in user, with systemd-logind or seatd running.",
            )
        }
        seatName = CStrings.borrowed(Seat.libseat_seat_name(seat)) ?: "seat0"
        fd = Fd.borrow(Seat.libseat_get_fd(seat))
        loop.add(fd) { Seat.libseat_dispatch(seat, 0) }
        // The first "enable" may arrive a little later; wait for it here.
        repeat(50) {
            if (!active) Seat.libseat_dispatch(seat, 100)
        }
        if (!active) {
            close()
            throw SessionException("The seat was opened but never became active (is another compositor running?)")
        }
    }

    private fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        activeListeners.forEach { it(value) }
    }

    /** Opens a GPU or input device (e.g. /dev/dri/card0, /dev/input/event3). */
    fun openDevice(path: Path): Device = Arena.ofConfined().use { temp ->
        val fdOut = temp.allocate(JAVA_INT)
        val id = Seat.libseat_open_device(seat, temp.allocateFrom(path.toString()), fdOut)
        if (id < 0) throw SessionException("Could not open $path through the session")
        Device(path, Fd.adopt(fdOut.get(JAVA_INT, 0)), id).also { devices += it }
    }

    fun closeDevice(device: Device) {
        if (!devices.remove(device)) return
        Seat.libseat_close_device(seat, device.id)
        device.fd.close()
    }

    /** Switches to virtual terminal [number] (Ctrl+Alt+F[number]). */
    fun switchTo(number: Int) {
        Seat.libseat_switch_session(seat, number)
    }

    override fun close() {
        for (device in devices.toList()) closeDevice(device)
        loop.remove(fd)
        Seat.libseat_close_seat(seat)
        arena.close()
    }

    companion object {
        /** Opens the session; throws [SessionException] with a readable message if that is not possible. */
        fun open(loop: EventLoop): Session = Session(loop)
    }
}

class SessionException(message: String) : RuntimeException(message)
