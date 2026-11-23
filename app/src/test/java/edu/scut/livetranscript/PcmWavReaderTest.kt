package edu.scut.livetranscript

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmWavReaderTest {
    @Test
    fun readsMonoPcm16AndDerivesDuration() {
        val samples = ShortArray(1_600) { (it % Short.MAX_VALUE).toShort() }
        val wav = PcmWavReader.read(wav(samples, 16_000))

        assertEquals(16_000, wav.sampleRate)
        assertEquals(1, wav.channels)
        assertEquals(3_200, wav.pcmBytes)
        assertArrayEquals(samples, wav.samples)
        assertEquals(100L, wav.durationMs)
    }

    @Test
    fun skipsUnknownRiffChunkBeforePcmData() {
        val samples = ShortArray(8_000) { (it % Short.MAX_VALUE).toShort() }
        val bytes = wav(samples, 8_000, extraChunk = byteArrayOf(5, 6, 7))
        val decoded = PcmWavReader.read(bytes)

        assertEquals(8_000, decoded.sampleRate)
        assertEquals(1_000L, decoded.durationMs)
        assertArrayEquals(samples, decoded.samples)
    }

    private fun wav(samples: ShortArray, sampleRate: Int, extraChunk: ByteArray? = null): ByteArray {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach(::putShort) }.array()
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write("fmt ".toByteArray(Charsets.US_ASCII))
            write(leInt(16))
            write(leShort(1)); write(leShort(1)); write(leInt(sampleRate))
            write(leInt(sampleRate * 2)); write(leShort(2)); write(leShort(16))
            if (extraChunk != null) {
                write("JUNK".toByteArray(Charsets.US_ASCII)); write(leInt(extraChunk.size)); write(extraChunk)
                if (extraChunk.size % 2 == 1) write(0)
            }
            write("data".toByteArray(Charsets.US_ASCII)); write(leInt(pcm.size)); write(pcm)
        }.toByteArray()
        return "RIFF".toByteArray(Charsets.US_ASCII) + leInt(body.size) + body
    }

    private fun leInt(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun leShort(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
