package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Test

class FunAsrEndpointTest {
    @Test
    fun defaultEndpointUsesAdbReverseLoopback() {
        assertEquals("ws://localhost:10095", BuildConfig.FUNASR_WS_URL)
    }
}
