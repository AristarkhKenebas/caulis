package dev.caulis.core.drm

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EdidTest {

    @Test
    fun `maker, name and serial are read`() {
        val edid = assertNotNull(Edid.parse(sample()))
        assertEquals("DEL", edid.make)
        assertEquals("DELL U2720Q", edid.model)
        assertEquals("ABC123", edid.serial)
    }

    @Test
    fun `garbage is not EDID`() {
        assertNull(Edid.parse(ByteArray(128) { 7 }))
        assertNull(Edid.parse(ByteArray(10)))
    }

    @Test
    fun `missing texts fall back to the numbers`() {
        val bytes = sample()
        for (start in listOf(54, 72, 90, 108)) bytes.fill(1, start, start + 18) // no text descriptors
        val edid = assertNotNull(Edid.parse(bytes))
        assertEquals("0xA0B1", edid.model)
        assertEquals("16909060", edid.serial) // 0x01020304
    }

    @Test
    fun `the monitors of this computer can be read`() {
        // Only reads what the kernel already exposes in /sys; the screen is not touched.
        val edids = Files.list(Path.of("/sys/class/drm")).use { entries ->
            entries.map { it.resolve("edid") }.filter { Files.isReadable(it) }.toList()
        }.map { Files.readAllBytes(it) }.filter { it.isNotEmpty() }
        assumeTrue(edids.isNotEmpty(), "no monitor information available")
        for (bytes in edids) assertNotNull(Edid.parse(bytes))
    }

    private fun sample(): ByteArray {
        val bytes = ByteArray(128)
        byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0).copyInto(bytes)
        // "DEL": D=4, E=5, L=12 -> 00100 00101 01100
        val packed = (4 shl 10) or (5 shl 5) or 12
        bytes[8] = (packed shr 8).toByte()
        bytes[9] = packed.toByte()
        bytes[10] = 0xB1.toByte()
        bytes[11] = 0xA0.toByte()
        byteArrayOf(4, 3, 2, 1).copyInto(bytes, 12)
        text(bytes, 72, 0xFC, "DELL U2720Q")
        text(bytes, 90, 0xFF, "ABC123")
        return bytes
    }

    private fun text(bytes: ByteArray, start: Int, type: Int, value: String) {
        bytes[start + 3] = type.toByte()
        (value + "\n").toByteArray().copyInto(bytes, start + 5)
    }
}
