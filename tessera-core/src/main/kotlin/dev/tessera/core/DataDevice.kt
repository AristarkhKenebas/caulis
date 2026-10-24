package dev.tessera.core

import dev.tessera.core.input.Seat
import dev.tessera.ffi.Fd
import dev.tessera.protocol.Client
import dev.tessera.protocol.Display
import dev.tessera.protocol.wayland.WlDataDevice
import dev.tessera.protocol.wayland.WlDataDeviceManager
import dev.tessera.protocol.wayland.WlDataOffer
import dev.tessera.protocol.wayland.WlDataSource
import dev.tessera.protocol.wayland.WlSeat
import dev.tessera.protocol.wayland.WlSurface

/**
 * wl_data_device_manager: the clipboard (copy and paste between programs).
 *
 * The program that copies offers a list of formats ("text/plain;charset=utf-8", ...).
 * The focused program is told what is on the clipboard and, when pasting, asks for one
 * format; the data then flows directly between the two programs through a pipe.
 * Drag and drop is refused for now (stage 8).
 */
class DataDeviceManager(display: Display, private val seat: Seat) {

    private val devices = ArrayList<WlDataDevice>()

    /** What is on the clipboard: the source and its formats. */
    private var selection: Source? = null

    private class Source(val resource: WlDataSource) {
        val mimeTypes = ArrayList<String>()
    }

    init {
        display.addGlobal(WlDataDeviceManager, VERSION) { manager -> manager.requests = managerRequests }
        // The newly focused program learns what is on the clipboard.
        seat.onKeyboardFocus { surface -> surface?.let { offerSelectionTo(it.resource.client) } }
    }

    private val managerRequests = object : WlDataDeviceManager.Requests {
        override fun createDataSource(resource: WlDataDeviceManager, id: WlDataSource) {
            val source = Source(id)
            id.data = source
            id.requests = object : WlDataSource.Requests {
                override fun offer(resource: WlDataSource, mimeType: String) {
                    if (source.mimeTypes.size < MAX_MIME_TYPES) source.mimeTypes += mimeType
                }

                override fun destroy(resource: WlDataSource) {}

                override fun setActions(resource: WlDataSource, dndActions: Int) {}
            }
            id.onDestroy {
                if (selection === source) setSelection(null)
            }
        }

        override fun getDataDevice(resource: WlDataDeviceManager, id: WlDataDevice, seat: WlSeat) {
            devices += id
            id.onDestroy { devices -= id }
            id.requests = object : WlDataDevice.Requests {
                override fun startDrag(resource: WlDataDevice, source: WlDataSource?, origin: WlSurface, icon: WlSurface?, serial: Int) {
                    source?.sendCancelled() // drag and drop comes later
                }

                override fun setSelection(resource: WlDataDevice, source: WlDataSource?, serial: Int) {
                    this@DataDeviceManager.setSelection(source?.dataAs<Source>())
                }

                override fun release(resource: WlDataDevice) {}
            }
            // A device created by the focused program gets the current clipboard at once.
            if (this@DataDeviceManager.seat.keyboardFocus?.resource?.client === id.client) offerTo(id)
        }

        override fun release(resource: WlDataDeviceManager) {}
    }

    private fun setSelection(source: Source?) {
        val old = selection
        if (old === source) return
        selection = source
        if (old != null && old.resource.isAlive) old.resource.sendCancelled()
        seat.keyboardFocus?.let { offerSelectionTo(it.resource.client) }
    }

    private fun offerSelectionTo(client: Client) {
        devices.filter { it.client === client }.forEach(::offerTo)
    }

    // Describes the clipboard to one program with a fresh wl_data_offer.
    private fun offerTo(device: WlDataDevice) {
        val source = selection
        if (source == null) {
            device.sendSelection(null)
            return
        }
        val offer = device.client.createServerResource(WlDataOffer, device.version)
        offer.requests = object : WlDataOffer.Requests {
            override fun accept(resource: WlDataOffer, serial: Int, mimeType: String?) {}

            override fun receive(resource: WlDataOffer, mimeType: String, fd: Fd) {
                // The pasting program gets the data straight from the copying one.
                if (selection === source && source.resource.isAlive && mimeType in source.mimeTypes) {
                    source.resource.sendSend(mimeType, fd)
                }
                fd.close() // the source got its own copy
            }

            override fun destroy(resource: WlDataOffer) {}

            override fun finish(resource: WlDataOffer) {}

            override fun setActions(resource: WlDataOffer, dndActions: Int, preferredAction: Int) {}
        }
        device.sendDataOffer(offer)
        source.mimeTypes.forEach(offer::sendOffer)
        device.sendSelection(offer)
    }

    companion object {
        const val VERSION = 3
        private const val MAX_MIME_TYPES = 64
    }
}
