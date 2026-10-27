package dev.caulis.wm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The startup sound: the presets as WAV files, and the players — fake ones (no sound is ever played). */
class StartupSoundTest {

    @Test
    fun `every preset is a proper WAV of 3 to 5 seconds, never clipping, the same every time`() {
        for (preset in StartupChimes.PRESETS) {
            val bytes = StartupChimes.wav(preset)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals(bytes.size - 8, b.getInt(4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals("fmt ", String(bytes, 12, 4))
            assertEquals(1, b.getShort(20).toInt(), "PCM")
            assertEquals(2, b.getShort(22).toInt(), "stereo")
            assertEquals(StartupChimes.RATE, b.getInt(24))
            assertEquals(16, b.getShort(34).toInt())
            assertEquals("data", String(bytes, 36, 4))
            val dataBytes = b.getInt(40)
            assertEquals(bytes.size - 44, dataBytes)
            val seconds = dataBytes / 4.0 / StartupChimes.RATE
            assertTrue(seconds in 3.0..5.0, "$preset lasts $seconds s")
            var peak = 0
            var sum = 0.0
            for (i in 44 until bytes.size step 2) {
                val v = Math.abs(b.getShort(i).toInt())
                peak = maxOf(peak, v)
                sum += v
            }
            assertTrue(peak in 16_000..30_000, "$preset peaks at $peak (well below 32767)")
            assertTrue(sum / (dataBytes / 2) > 300, "$preset is not silence")
            // The tail fades out to nothing.
            assertTrue((bytes.size - 400 until bytes.size step 2).all { Math.abs(b.getShort(it).toInt()) < 200 }, "$preset ends quietly")
            assertContentEquals(bytes, StartupChimes.wav(preset), "$preset is the same every time")
        }
        assertTrue(!StartupChimes.wav("airy").contentEquals(StartupChimes.wav("classic")), "the presets differ")
    }

    @Test
    fun `presets are checked, kept in the cache once`() {
        assertFailsWith<IllegalArgumentException> { StartupSound().preset = "loud" }
        assertFailsWith<IllegalArgumentException> { StartupSound().volume = 1.5 }
        val folder = Files.createTempDirectory("caulis-sounds")
        try {
            val file = StartupChimes.file("glass", folder)
            assertTrue(file.fileName.toString().startsWith("glass-v"), "$file")
            val made = Files.getLastModifiedTime(file)
            Thread.sleep(20)
            assertEquals(file, StartupChimes.file("glass", folder))
            assertEquals(made, Files.getLastModifiedTime(file), "not made again")
            assertEquals(1, Files.list(folder).use { it.count() }, "no temporary file left")
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    // A fake player: a script writing its arguments to [out], ending with [status].
    private fun player(folder: Path, name: String, out: Path, status: Int): Path =
        folder.resolve(name).also {
            Files.writeString(it, "#!/bin/sh\necho \"$name \$*\" >> '$out'\nexit $status\n")
            it.toFile().setExecutable(true)
        }

    @Test
    fun `the first player that works plays it, with the volume`() {
        val folder = Files.createTempDirectory("caulis-players")
        try {
            val out = folder.resolve("played.txt")
            val failing = player(folder, "failing", out, 1)
            val good = player(folder, "good", out, 0)
            val sound = folder.resolve("s.wav")
            val players = listOf(
                listOf(folder.resolve("not-installed").toString(), "{file}"),
                listOf(failing.toString(), "{file}"),
                listOf(good.toString(), "--volume={volume}", "{percent}"),
            )
            assertEquals(good.toString(), SoundPlayer.play(sound, 0.5, players))
            assertEquals(listOf("failing $sound", "good --volume=0.50 50 $sound"), Files.readAllLines(out), "{file} put last when not written")
            assertNull(SoundPlayer.play(sound, 0.5, listOf(listOf(failing.toString()))), "none played")
            // A long sound: its player stopped after the time, and the next one does not play it over.
            Files.delete(out)
            val slow = folder.resolve("slow").also {
                Files.writeString(it, "#!/bin/sh\necho \"slow\" >> '$out'\nexec sleep 5\n")
                it.toFile().setExecutable(true)
            }
            val started = System.nanoTime()
            assertEquals(slow.toString(), SoundPlayer.play(sound, 0.5, listOf(listOf(slow.toString()), listOf(good.toString())), timeoutMillis = 300))
            assertTrue(System.nanoTime() - started < 3_000_000_000, "stopped after its time")
            assertEquals(listOf("slow"), Files.readAllLines(out), "the next player did not start it over")
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a file of yours and a player of yours, on a thread of its own`() {
        val folder = Files.createTempDirectory("caulis-players")
        try {
            val out = folder.resolve("played.txt")
            val mine = player(folder, "mine", out, 0)
            val file = folder.resolve("hello.ogg").also { Files.writeString(it, "not really ogg") }
            val sound = StartupSound().apply {
                enabled = true
                this.file = "hello.ogg"
                volume = 0.25
                player = listOf(mine.toString(), "{paVolume}", "{file}")
            }
            SoundPlayer.playLater(sound, folder.resolve("cache"), resolve = { folder.resolve(it) }, env = mapOf("PATH" to "/usr/bin:/bin"))
            waitFor { Files.exists(out) }
            assertEquals(listOf("mine 16384 $file"), Files.readAllLines(out))
            assertTrue(!Files.exists(folder.resolve("cache")), "no preset made for a file of yours")

            // A preset asked for (the action): made in the cache, played by the same player.
            SoundPlayer.playLater(sound, folder.resolve("cache"), resolve = { folder.resolve(it) }, env = mapOf("PATH" to "/usr/bin:/bin"), presetOverride = "soft")
            waitFor { Files.readAllLines(out).size == 2 }
            assertTrue(Files.readAllLines(out)[1].contains("/cache/soft-v"), Files.readAllLines(out)[1])
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }
}
