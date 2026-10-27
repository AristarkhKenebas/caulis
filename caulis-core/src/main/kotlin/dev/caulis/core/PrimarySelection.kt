package dev.caulis.core

import dev.caulis.core.input.Seat
import dev.caulis.ffi.Fd
import dev.caulis.protocol.Client
import dev.caulis.protocol.Display
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wpprimaryselectionunstablev1.ZwpPrimarySelectionDeviceManagerV1
import dev.caulis.protocol.wpprimaryselectionunstablev1.ZwpPrimarySelectionDeviceV1
import dev.caulis.protocol.wpprimaryselectionunstablev1.ZwpPrimarySelectionOfferV1
import dev.caulis.protocol.wpprimaryselectionunstablev1.ZwpPrimarySelectionSourceV1

/**
 * primary-selection: what is selected (with the mouse, in a text) is pasted with the middle
 * button, as on Linux desktops for decades — beside the clipboard, not instead of it. Kept
 * in [DataDeviceManager.primary]; like the clipboard, only the program with the keyboard
 * learns what is in it.
 */
class PrimarySelection(display: Display, private val seat: Seat, private val clipboard: DataDeviceManager) {

    private val devices = ArrayList<ZwpPrimarySelectionDeviceV1>()

    private class Source(val resource: ZwpPrimarySelectionSourceV1) : ClipboardData {
        override val mimeTypes = ArrayList<String>()

        override fun send(mimeType: String, fd: Fd) {
            fd.use { if (resource.isAlive && mimeType in mimeTypes) resource.sendSend(mimeType, it) }
        }

        override fun cancelled() {
            if (resource.isAlive) resource.sendCancelled()
        }

        override fun toString() = "the selection of ${resource.client.name}"
    }

    init {
        display.addGlobal(ZwpPrimarySelectionDeviceManagerV1, VERSION) { manager ->
            manager.requests = object : ZwpPrimarySelectionDeviceManagerV1.Requests {
                override fun createSource(resource: ZwpPrimarySelectionDeviceManagerV1, id: ZwpPrimarySelectionSourceV1) {
                    val source = Source(id)
                    id.data = source
                    id.requests = object : ZwpPrimarySelectionSourceV1.Requests {
                        override fun offer(resource: ZwpPrimarySelectionSourceV1, mimeType: String) {
                            if (source.mimeTypes.size < MAX_MIME_TYPES && resource.client.server.fitsForwarded(mimeType)) source.mimeTypes += mimeType
                        }

                        override fun destroy(resource: ZwpPrimarySelectionSourceV1) {}
                    }
                    id.onDestroy { if (clipboard.primary === source) clipboard.setPrimary(null) }
                }

                override fun getDevice(resource: ZwpPrimarySelectionDeviceManagerV1, id: ZwpPrimarySelectionDeviceV1, seat: WlSeat) {
                    devices += id
                    id.onDestroy { devices -= id }
                    id.requests = object : ZwpPrimarySelectionDeviceV1.Requests {
                        override fun setSelection(resource: ZwpPrimarySelectionDeviceV1, source: ZwpPrimarySelectionSourceV1?, serial: Int) {
                            // As the clipboard: only the program you use.
                            if (!this@PrimarySelection.seat.maySetSelection(resource.client, serial)) {
                                source?.sendCancelled()
                                return
                            }
                            clipboard.setPrimary(source?.dataAs<Source>())
                        }

                        override fun destroy(resource: ZwpPrimarySelectionDeviceV1) {}
                    }
                    if (this@PrimarySelection.seat.keyboardFocus?.resource?.client === id.client) offerTo(id)
                }

                override fun destroy(resource: ZwpPrimarySelectionDeviceManagerV1) {}
            }
        }
        seat.onKeyboardFocus { surface -> surface?.let { offerTo(it.resource.client) } }
        clipboard.onPrimaryChanged { seat.keyboardFocus?.let { offerTo(it.resource.client) } }
    }

    private fun offerTo(client: Client) {
        devices.filter { it.client === client }.forEach(::offerTo)
    }

    private fun offerTo(device: ZwpPrimarySelectionDeviceV1) {
        val data = clipboard.primary
        if (data == null) {
            device.sendSelection(null)
            return
        }
        val offer = device.client.createServerResource(ZwpPrimarySelectionOfferV1, device.version)
        offer.requests = object : ZwpPrimarySelectionOfferV1.Requests {
            override fun receive(resource: ZwpPrimarySelectionOfferV1, mimeType: String, fd: Fd) {
                if (clipboard.primary === data && mimeType in data.mimeTypes) data.send(mimeType, fd) else fd.close()
            }

            override fun destroy(resource: ZwpPrimarySelectionOfferV1) {}
        }
        device.sendDataOffer(offer)
        data.mimeTypes.forEach(offer::sendOffer)
        device.sendSelection(offer)
    }

    companion object {
        const val VERSION = 1
        private const val MAX_MIME_TYPES = 64
    }
}
