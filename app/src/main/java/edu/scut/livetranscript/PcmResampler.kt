package edu.scut.livetranscript

/** Streaming linear resampling; channel averaging precedes conversion to 16 kHz mono. */
class PcmResampler(private val sourceRate: Int, private val channels: Int) {
    init { require(sourceRate in 8000..192000 && channels in 1..8) }
    private var inputFrames = 0L
    private var outputFrames = 0L
    private var previous = 0.0

    fun convert(input: ShortArray): ShortArray {
        require(input.size % channels == 0) { "PCM 帧不完整" }
        val result = ArrayList<Short>()
        for (offset in input.indices step channels) {
            val current = (0 until channels).sumOf { input[offset + it].toDouble() } / channels
            if (inputFrames == 0L) previous = current
            while (outputFrames * sourceRate.toDouble() / 16000 <= inputFrames) {
                val position = outputFrames * sourceRate.toDouble() / 16000
                val fraction = (position - (inputFrames - 1)).coerceIn(0.0, 1.0)
                result.add((previous + (current - previous) * fraction).toInt().coerceIn(-32768, 32767).toShort())
                outputFrames++
            }
            previous = current
            inputFrames++
        }
        return result.toShortArray()
    }
}
