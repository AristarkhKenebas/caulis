package dev.tessera.ffi

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformTest {

    @Test
    fun `process id from C matches the one Java knows`() {
        assertEquals(ProcessHandle.current().pid().toInt(), Platform.processId())
    }
}
