package edu.scut.livetranscript

import org.junit.Assert.*
import org.junit.Test

class PcmResamplerTest {
    @Test fun preserves16kMonoSamples() {
        val input = shortArrayOf(-32000, -50, 0, 100, 32000)
        assertArrayEquals(input, PcmResampler(16000, 1).convert(input))
    }

    @Test fun downmixesStereoAndPreservesChunkContinuity() {
        val input = ShortArray(4800 * 2) { i -> if (i % 2 == 0) 1000 else 3000 }
        val whole = PcmResampler(48000, 2).convert(input)
        val split = PcmResampler(48000, 2)
        val chunked = split.convert(input.copyOfRange(0, 2300)) + split.convert(input.copyOfRange(2300, input.size))
        assertArrayEquals(whole, chunked)
        assertEquals(1600, whole.size)
        assertTrue(whole.all { it == 2000.toShort() })
    }

    @Test fun interpolatesAndCancelsOppositeChannels() {
        assertArrayEquals(shortArrayOf(0, 500, 1000), PcmResampler(8000, 1).convert(shortArrayOf(0, 1000)))
        assertArrayEquals(shortArrayOf(0, 0), PcmResampler(16000, 2).convert(shortArrayOf(1000, -1000, 3000, -3000)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialMultichannelFrame() { PcmResampler(48000, 2).convert(shortArrayOf(1)) }
}
