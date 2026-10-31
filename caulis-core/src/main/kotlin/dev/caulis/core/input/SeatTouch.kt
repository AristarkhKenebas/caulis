package dev.caulis.core.input

import dev.caulis.core.Surface
import dev.caulis.protocol.Client

/**
 * Touch screens: fingers told to programs, each by its id.
 */
internal class SeatTouch(private val seat: Seat) {

    // The surface each finger went down on.
    private val touchSurfaces = HashMap<Int, Surface>()

    fun touchDown(timeMillis: Int, surface: Surface, id: Int, unitX: Double, unitY: Double) {
        if (!surface.resource.isAlive) return
        touchSurfaces[id] = surface
        val pixels = surface.nativeScale ?: 1.0
        val serial = seat.display.nextSerial()
        framePending += surface.resource.client
        touchesOf(surface.resource.client).forEach { it.sendDown(serial, timeMillis, surface.resource, id, unitX * pixels, unitY * pixels) }
        seat.inputSent(surface.resource.client, serial)
    }

    fun touchMotion(timeMillis: Int, id: Int, unitX: Double, unitY: Double) {
        val surface = touchSurfaces[id]?.takeIf { it.resource.isAlive } ?: return
        val pixels = surface.nativeScale ?: 1.0
        framePending += surface.resource.client
        touchesOf(surface.resource.client).forEach { it.sendMotion(timeMillis, id, unitX * pixels, unitY * pixels) }
    }

    fun touchUp(timeMillis: Int, id: Int) {
        val surface = touchSurfaces.remove(id)?.takeIf { it.resource.isAlive } ?: return
        val serial = seat.display.nextSerial()
        framePending += surface.resource.client
        touchesOf(surface.resource.client).forEach { it.sendUp(serial, timeMillis, id) }
        seat.inputSent(surface.resource.client, serial)
    }

    fun touchFrame() {
        framePending.forEach { client -> touchesOf(client).forEach { it.sendFrame() } }
        framePending.clear()
    }

    // Programs sent touch events since the last frame.
    private val framePending = LinkedHashSet<Client>()

    fun touchCancel() {
        touchSurfaces.values.map { it.resource.client }.distinct().forEach { client -> touchesOf(client).forEach { it.sendCancel() } }
        touchSurfaces.clear()
        framePending.clear()
    }

    private fun touchesOf(client: Client) = seat.touches.filter { it.client === client && it.isAlive }
}
