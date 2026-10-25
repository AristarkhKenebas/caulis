package dev.caulis.core

import dev.caulis.ffi.Fd
import dev.caulis.protocol.Display
import dev.caulis.protocol.Resource
import dev.caulis.protocol.extdatacontrolv1.ExtDataControlDeviceV1
import dev.caulis.protocol.extdatacontrolv1.ExtDataControlManagerV1
import dev.caulis.protocol.extdatacontrolv1.ExtDataControlOfferV1
import dev.caulis.protocol.extdatacontrolv1.ExtDataControlSourceV1
import dev.caulis.protocol.wayland.WlSeat
import dev.caulis.protocol.wlrdatacontrolunstablev1.ZwlrDataControlDeviceV1
import dev.caulis.protocol.wlrdatacontrolunstablev1.ZwlrDataControlManagerV1
import dev.caulis.protocol.wlrdatacontrolunstablev1.ZwlrDataControlOfferV1
import dev.caulis.protocol.wlrdatacontrolunstablev1.ZwlrDataControlSourceV1

/**
 * data-control (ext-data-control-v1, and wlr-data-control for older programs): clipboard
 * managers (cliphist, clipman), `wl-copy` and `wl-paste` read and set the clipboard and the
 * primary selection without having the keyboard — they are told every change.
 */
class DataControl(display: Display, private val clipboard: DataDeviceManager) {

    private val log = dev.caulis.log.Logger("clipboard")

    // A device of either protocol: it is told what is in the clipboard and the selection.
    private abstract class Device {
        abstract val resource: Resource
        abstract val primaryToo: Boolean
        abstract fun offer(data: ClipboardData?, primary: Boolean)
    }

    private val devices = ArrayList<Device>()

    // A source of either protocol.
    private class Source(private val write: (String, Fd) -> Unit, private val cancel: () -> Unit, private val owner: String) : ClipboardData {
        override val mimeTypes = ArrayList<String>()
        override fun send(mimeType: String, fd: Fd) {
            fd.use { if (mimeType in mimeTypes) write(mimeType, it) }
        }

        override fun cancelled() = cancel()
        override fun toString() = "what $owner copied"
    }

    init {
        clipboard.onClipboardChanged { data -> devices.toList().forEach { it.offer(data, primary = false) } }
        clipboard.onPrimaryChanged { data -> devices.toList().filter { it.primaryToo }.forEach { it.offer(data, primary = true) } }
        ext(display)
        wlr(display)
    }

    private fun add(device: Device) {
        devices += device
        device.resource.onDestroy { devices -= device }
        log.info { "${device.resource.client.name} watches the clipboard" }
        device.offer(clipboard.clipboard, primary = false)
        if (device.primaryToo) device.offer(clipboard.primary, primary = true)
    }

    // The data of a source that goes away leaves the clipboard (or the selection).
    private fun forget(source: Source) {
        if (clipboard.clipboard === source) clipboard.setClipboard(null)
        if (clipboard.primary === source) clipboard.setPrimary(null)
    }

    private fun receive(data: ClipboardData, primary: Boolean, mimeType: String, fd: Fd) {
        val now = if (primary) clipboard.primary else clipboard.clipboard
        if (now === data && mimeType in data.mimeTypes) data.send(mimeType, fd) else fd.close()
    }

    private fun ext(display: Display) {
        display.addPrivilegedGlobal(ExtDataControlManagerV1, 1) { manager ->
            manager.requests = object : ExtDataControlManagerV1.Requests {
                override fun createDataSource(resource: ExtDataControlManagerV1, id: ExtDataControlSourceV1) {
                    val source = Source({ m, fd -> if (id.isAlive) id.sendSend(m, fd) }, { if (id.isAlive) id.sendCancelled() }, id.client.name)
                    id.data = source
                    id.requests = object : ExtDataControlSourceV1.Requests {
                        override fun offer(resource: ExtDataControlSourceV1, mimeType: String) {
                            if (source.mimeTypes.size < MAX_MIME_TYPES) source.mimeTypes += mimeType
                        }

                        override fun destroy(resource: ExtDataControlSourceV1) {}
                    }
                    id.onDestroy { forget(source) }
                }

                override fun getDataDevice(resource: ExtDataControlManagerV1, id: ExtDataControlDeviceV1, seat: WlSeat) {
                    id.requests = object : ExtDataControlDeviceV1.Requests {
                        override fun setSelection(resource: ExtDataControlDeviceV1, source: ExtDataControlSourceV1?) = clipboard.setClipboard(source?.dataAs<Source>())
                        override fun setPrimarySelection(resource: ExtDataControlDeviceV1, source: ExtDataControlSourceV1?) = clipboard.setPrimary(source?.dataAs<Source>())
                        override fun destroy(resource: ExtDataControlDeviceV1) {}
                    }
                    add(object : Device() {
                        override val resource get() = id
                        override val primaryToo = true
                        override fun offer(data: ClipboardData?, primary: Boolean) {
                            if (!id.isAlive) return
                            val offer = data?.let {
                                id.client.createServerResource(ExtDataControlOfferV1, id.version).also { offer ->
                                    offer.requests = object : ExtDataControlOfferV1.Requests {
                                        override fun receive(resource: ExtDataControlOfferV1, mimeType: String, fd: Fd) = receive(data, primary, mimeType, fd)
                                        override fun destroy(resource: ExtDataControlOfferV1) {}
                                    }
                                    id.sendDataOffer(offer)
                                    data.mimeTypes.forEach(offer::sendOffer)
                                }
                            }
                            if (primary) id.sendPrimarySelection(offer) else id.sendSelection(offer)
                        }
                    })
                }

                override fun destroy(resource: ExtDataControlManagerV1) {}
            }
        }
    }

    private fun wlr(display: Display) {
        display.addPrivilegedGlobal(ZwlrDataControlManagerV1, 2) { manager ->
            manager.requests = object : ZwlrDataControlManagerV1.Requests {
                override fun createDataSource(resource: ZwlrDataControlManagerV1, id: ZwlrDataControlSourceV1) {
                    val source = Source({ m, fd -> if (id.isAlive) id.sendSend(m, fd) }, { if (id.isAlive) id.sendCancelled() }, id.client.name)
                    id.data = source
                    id.requests = object : ZwlrDataControlSourceV1.Requests {
                        override fun offer(resource: ZwlrDataControlSourceV1, mimeType: String) {
                            if (source.mimeTypes.size < MAX_MIME_TYPES) source.mimeTypes += mimeType
                        }

                        override fun destroy(resource: ZwlrDataControlSourceV1) {}
                    }
                    id.onDestroy { forget(source) }
                }

                override fun getDataDevice(resource: ZwlrDataControlManagerV1, id: ZwlrDataControlDeviceV1, seat: WlSeat) {
                    id.requests = object : ZwlrDataControlDeviceV1.Requests {
                        override fun setSelection(resource: ZwlrDataControlDeviceV1, source: ZwlrDataControlSourceV1?) = clipboard.setClipboard(source?.dataAs<Source>())
                        override fun setPrimarySelection(resource: ZwlrDataControlDeviceV1, source: ZwlrDataControlSourceV1?) = clipboard.setPrimary(source?.dataAs<Source>())
                        override fun destroy(resource: ZwlrDataControlDeviceV1) {}
                    }
                    add(object : Device() {
                        override val resource get() = id
                        override val primaryToo = id.version >= 2
                        override fun offer(data: ClipboardData?, primary: Boolean) {
                            if (!id.isAlive) return
                            val offer = data?.let {
                                id.client.createServerResource(ZwlrDataControlOfferV1, id.version).also { offer ->
                                    offer.requests = object : ZwlrDataControlOfferV1.Requests {
                                        override fun receive(resource: ZwlrDataControlOfferV1, mimeType: String, fd: Fd) = receive(data, primary, mimeType, fd)
                                        override fun destroy(resource: ZwlrDataControlOfferV1) {}
                                    }
                                    id.sendDataOffer(offer)
                                    data.mimeTypes.forEach(offer::sendOffer)
                                }
                            }
                            if (primary) id.sendPrimarySelection(offer) else id.sendSelection(offer)
                        }
                    })
                }

                override fun destroy(resource: ZwlrDataControlManagerV1) {}
            }
        }
    }

    private companion object {
        const val MAX_MIME_TYPES = 64
    }
}
