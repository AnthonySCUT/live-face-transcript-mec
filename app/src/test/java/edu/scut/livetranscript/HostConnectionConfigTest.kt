package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostConnectionConfigTest {
    @Test fun physicalPhoneUsesLoopbackForUsbReverse() {
        val addresses = HostConnectionConfig.defaults(false, "ws://localhost:10095")

        assertEquals("http://127.0.0.1:8000", addresses.faceBaseUrl)
        assertEquals("ws://localhost:10095", addresses.funAsrWsUrl)
        assertEquals("http://127.0.0.1:8001/separate-and-transcribe", addresses.overlapUrl)
    }

    @Test fun emulatorUsesHostGatewayAndRecognizesDeviceBuild() {
        val emulator = HostConnectionConfig.isEmulator("google/sdk_gphone64", "sdk_gphone64_x86_64", "sdk_gphone64")
        val addresses = HostConnectionConfig.defaults(emulator, "ws://localhost:10095")

        assertTrue(emulator)
        assertEquals("http://10.0.2.2:8000", addresses.faceBaseUrl)
        assertEquals("http://10.0.2.2:8001/separate-and-transcribe", addresses.overlapUrl)
        assertFalse(HostConnectionConfig.isEmulator("vivo/build", "V2309A", "PD2309"))
    }
}
