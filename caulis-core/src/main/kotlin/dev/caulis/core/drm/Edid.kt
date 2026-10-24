package dev.caulis.core.drm

/**
 * What a monitor says about itself (EDID): maker, model name and serial number.
 *
 * Monitors send all kinds of broken data, so every field is optional and parsing never
 * throws: missing or garbled parts simply stay empty.
 */
data class Edid(
    /** Three-letter maker code, e.g. "DEL" (Dell), "SAM" (Samsung). */
    val make: String,
    val model: String,
    val serial: String,
) {
    companion object {
        private val HEADER = byteArrayOf(0, -1, -1, -1, -1, -1, -1, 0)

        /** Reads the basic EDID block; null if it is not EDID at all. */
        fun parse(bytes: ByteArray): Edid? {
            if (bytes.size < 128 || !bytes.copyOfRange(0, 8).contentEquals(HEADER)) return null
            val maker = makerCode(bytes[8].toInt() and 0xff, bytes[9].toInt() and 0xff)
            val productCode = (bytes[10].toInt() and 0xff) or ((bytes[11].toInt() and 0xff) shl 8)
            val serialNumber = (0..3).sumOf { ((bytes[12 + it].toLong() and 0xff) shl (8 * it)) }

            var name = ""
            var serialText = ""
            // Four 18-byte descriptors; text ones start with 0, 0, 0, <type>.
            for (start in listOf(54, 72, 90, 108)) {
                if (bytes[start].toInt() != 0 || bytes[start + 1].toInt() != 0 || bytes[start + 2].toInt() != 0) continue
                val text = descriptorText(bytes, start + 5)
                when (bytes[start + 3].toInt() and 0xff) {
                    0xFC -> name = text
                    0xFF -> serialText = text
                }
            }
            return Edid(
                make = maker,
                model = name.ifEmpty { "0x%04X".format(productCode) },
                serial = serialText.ifEmpty { if (serialNumber != 0L) serialNumber.toString() else "" },
            )
        }

        // Three letters, five bits each (1 = 'A').
        private fun makerCode(high: Int, low: Int): String {
            val packed = (high shl 8) or low
            val letters = listOf((packed shr 10) and 31, (packed shr 5) and 31, packed and 31)
            return if (letters.all { it in 1..26 }) letters.joinToString("") { ('A' + it - 1).toString() } else ""
        }

        // Up to 13 characters, ended by a line feed; only printable ASCII is kept.
        private fun descriptorText(bytes: ByteArray, start: Int): String = buildString {
            for (i in start until start + 13) {
                val c = bytes[i].toInt() and 0xff
                if (c == 0x0A || c == 0) break
                if (c in 0x20..0x7E) append(c.toChar())
            }
        }.trim()
    }
}
