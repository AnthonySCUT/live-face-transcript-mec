package edu.scut.livetranscript

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmWav(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
    val durationMs: Long = samples.size * 1000L / sampleRate / channels
    val pcmBytes: Int = samples.size * 2
}

object PcmWavReader {
    fun read(bytes: ByteArray): PcmWav {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        data.position(12)

        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var pcm = byteArrayOf()
        while (data.remaining() >= 8) {
            val name = ByteArray(4).also(data::get).toString(Charsets.US_ASCII)
            val size = data.int
            val start = data.position()
            if (name == "fmt ") {
                format = data.short.toInt() and 0xffff
                channels = data.short.toInt() and 0xffff
                sampleRate = data.int
                data.int // byte rate
                data.short // block alignment
                bitsPerSample = data.short.toInt() and 0xffff
            } else if (name == "data") {
                pcm = ByteArray(size).also(data::get)
            }
            data.position(start + size + (size and 1))
        }

        require(format == 1 && channels == 1 && bitsPerSample == 16)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(pcm.size / 2) { buffer.short }
        return PcmWav(sampleRate, channels, samples)
    }
}
