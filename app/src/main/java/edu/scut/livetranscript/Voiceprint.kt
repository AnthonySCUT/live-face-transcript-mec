package edu.scut.livetranscript

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

data class VoiceprintMatch(val name: String, val similarity: Float)
data class CapturedVoiceprint(val name: String, val embedding: FloatArray?)

class PcmTimelineBuffer {
    private data class Chunk(val samples: ShortArray, val endedAtMs: Long)
    private val chunks = ArrayDeque<Chunk>()

    @Synchronized
    fun append(samples: ShortArray, count: Int, endedAtMs: Long) {
        if (count <= 0) return
        chunks.addLast(Chunk(samples.copyOf(count), endedAtMs))
        while (chunks.isNotEmpty() && endedAtMs - chunks.first().endedAtMs > HISTORY_MS) chunks.removeFirst()
    }

    @Synchronized
    fun slice(startedAtMs: Long, endedAtMs: Long): ShortArray {
        if (endedAtMs <= startedAtMs) return ShortArray(0)
        val selected = chunks.mapNotNull { chunk ->
            val chunkStartedAt = chunk.endedAtMs - chunk.samples.size * 1000L / SAMPLE_RATE
            val fromMs = maxOf(startedAtMs, chunkStartedAt)
            val toMs = minOf(endedAtMs, chunk.endedAtMs)
            if (toMs <= fromMs) return@mapNotNull null
            val from = (((fromMs - chunkStartedAt) * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, chunk.samples.size)
            val to = (((toMs - chunkStartedAt) * SAMPLE_RATE) / 1000L).toInt().coerceIn(from, chunk.samples.size)
            chunk.samples.copyOfRange(from, to)
        }
        val size = selected.sumOf { it.size }
        return ShortArray(size).also { output ->
            var offset = 0
            selected.forEach { samples -> samples.copyInto(output, offset).also { offset += samples.size } }
        }
    }

    @Synchronized
    fun clear() = chunks.clear()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val HISTORY_MS = 12_000L
    }
}

class VoiceprintEnrollment {
    private var name: String? = null
    private var chunks = mutableListOf<ShortArray>()
    private var sampleCount = 0

    @Synchronized
    fun start(participantName: String) {
        name = participantName
        chunks = mutableListOf()
        sampleCount = 0
    }

    @Synchronized
    fun append(samples: ShortArray, count: Int): CapturedVoiceprint? {
        val target = name ?: return null
        chunks.add(samples.copyOf(count))
        sampleCount += count
        if (sampleCount < RECORD_SAMPLES) return null
        val audio = ShortArray(sampleCount)
        var offset = 0
        chunks.forEach { it.copyInto(audio, offset).also { offset += it.size } }
        name = null
        chunks.clear()
        sampleCount = 0
        return CapturedVoiceprint(target, VoiceprintExtractor.extract(audio))
    }

    @Synchronized
    fun cancel() {
        name = null
        chunks.clear()
        sampleCount = 0
    }

    @Synchronized
    fun activeName() = name

    companion object { private const val RECORD_SAMPLES = 16_000 * 5 }
}

object VoiceprintExtractor {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_SIZE = 400
    private const val FRAME_STEP = 160
    private const val FFT_SIZE = 512
    private const val MEL_BANDS = 24
    private const val COEFFICIENTS = 16
    private const val MIN_VOICED_FRAMES = 28
    private val melFilters by lazy { makeMelFilters() }

    fun extract(samples: ShortArray): FloatArray? {
        if (samples.size < SAMPLE_RATE) return null
        val sums = DoubleArray(COEFFICIENTS)
        val squares = DoubleArray(COEFFICIENTS)
        var voicedFrames = 0
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        val power = DoubleArray(FFT_SIZE / 2 + 1)
        val logMel = DoubleArray(MEL_BANDS)
        var offset = 0
        while (offset + FRAME_SIZE <= samples.size) {
            var energy = 0.0
            for (i in 0 until FRAME_SIZE) {
                val value = samples[offset + i].toDouble()
                energy += value * value
            }
            if (sqrt(energy / FRAME_SIZE) >= MIN_RMS) {
                real.fill(0.0); imag.fill(0.0)
                for (i in 0 until FRAME_SIZE) {
                    val current = samples[offset + i].toDouble() / 32768.0
                    val previous = if (i == 0) samples[(offset + i - 1).coerceAtLeast(0)].toDouble() / 32768.0 else samples[offset + i - 1].toDouble() / 32768.0
                    real[i] = (current - PRE_EMPHASIS * previous) * (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_SIZE - 1)))
                }
                fft(real, imag)
                for (i in power.indices) power[i] = real[i] * real[i] + imag[i] * imag[i]
                for (band in 0 until MEL_BANDS) {
                    val filter = melFilters[band]
                    var value = 0.0
                    for (bin in filter.indices) value += power[bin] * filter[bin]
                    logMel[band] = ln(value.coerceAtLeast(1e-12))
                }
                for (coefficient in 0 until COEFFICIENTS) {
                    var value = 0.0
                    for (band in 0 until MEL_BANDS) value += logMel[band] * cos(PI * coefficient * (band + 0.5) / MEL_BANDS)
                    val normalized = value / MEL_BANDS
                    sums[coefficient] += normalized
                    squares[coefficient] += normalized * normalized
                }
                voicedFrames++
            }
            offset += FRAME_STEP
        }
        if (voicedFrames < MIN_VOICED_FRAMES) return null
        val signature = FloatArray(COEFFICIENTS * 2)
        for (i in 0 until COEFFICIENTS) {
            val mean = sums[i] / voicedFrames
            signature[i] = mean.toFloat()
            signature[COEFFICIENTS + i] = sqrt((squares[i] / voicedFrames - mean * mean).coerceAtLeast(0.0)).toFloat()
        }
        val norm = sqrt(signature.sumOf { it.toDouble() * it })
        if (norm <= 1e-9) return null
        return FloatArray(signature.size) { (signature[it] / norm).toFloat() }
    }

    private fun makeMelFilters(): Array<DoubleArray> {
        val lowMel = hzToMel(80.0)
        val highMel = hzToMel(SAMPLE_RATE / 2.0)
        val points = (0..MEL_BANDS + 1).map { melToHz(lowMel + (highMel - lowMel) * it / (MEL_BANDS + 1)) }
        return Array(MEL_BANDS) { band ->
            DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                val hz = bin * SAMPLE_RATE.toDouble() / FFT_SIZE
                when {
                    hz < points[band] || hz > points[band + 2] -> 0.0
                    hz <= points[band + 1] -> (hz - points[band]) / (points[band + 1] - points[band])
                    else -> (points[band + 2] - hz) / (points[band + 2] - points[band + 1])
                }.coerceAtLeast(0.0)
            }
        }
    }

    private fun hzToMel(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val realValue = real[i]; real[i] = real[j]; real[j] = realValue
                val imagValue = imag[i]; imag[i] = imag[j]; imag[j] = imagValue
            }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImag = kotlin.math.sin(angle)
            for (start in 0 until FFT_SIZE step length) {
                var twiddleReal = 1.0
                var twiddleImag = 0.0
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = even + length / 2
                    val oddReal = real[odd] * twiddleReal - imag[odd] * twiddleImag
                    val oddImag = real[odd] * twiddleImag + imag[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextReal = twiddleReal * stepReal - twiddleImag * stepImag
                    twiddleImag = twiddleReal * stepImag + twiddleImag * stepReal
                    twiddleReal = nextReal
                }
            }
            length = length shl 1
        }
    }

    private const val MIN_RMS = 220.0
    private const val PRE_EMPHASIS = 0.97
    private fun Double.pow(power: Double) = kotlin.math.exp(ln(this) * power)
}

object VoiceprintMatcher {
    fun match(embedding: FloatArray?, participants: List<Participant>): VoiceprintMatch? {
        if (embedding == null) return null
        val ranked = participants.mapNotNull { participant ->
            val voice = participant.voiceEmbedding ?: return@mapNotNull null
            if (voice.size != embedding.size) return@mapNotNull null
            VoiceprintMatch(participant.name, cosine(embedding, voice))
        }.sortedByDescending { it.similarity }
        val best = ranked.firstOrNull() ?: return null
        val margin = best.similarity - (ranked.getOrNull(1)?.similarity ?: -1f)
        if (best.similarity < MIN_SIMILARITY || (ranked.size > 1 && margin < MIN_MARGIN)) return null
        return best
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0.0 || normB == 0.0) -1f else (dot / sqrt(normA * normB)).toFloat()
    }

    private const val MIN_SIMILARITY = 0.84f
    private const val MIN_MARGIN = 0.08f
}
