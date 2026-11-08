package edu.scut.livetranscript

import org.json.JSONObject
import org.vosk.SpeakerModel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.log10
import kotlin.math.sqrt

class VoskSpeechEngine(private val decoder: SpeechDecoder) : SpeechEngine {
    constructor(model: Model, speakerModel: SpeakerModel? = null) : this(VoskDecoder(model, speakerModel))

    private var onEvent: ((SpeechEvent) -> Unit)? = null
    private var speechStartedAtMs: Long? = null
    private var previousPartial = ""
    private var started = false
    private var lastCapturedAtMs = 0L

    override fun start(onEvent: (SpeechEvent) -> Unit) {
        this.onEvent = onEvent
        speechStartedAtMs = null
        previousPartial = ""
        started = true
    }

    override fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long) {
        if (!started || count <= 0) return

        var energy = 0.0
        for (index in 0 until count) {
            val sample = samples[index].toDouble()
            energy += sample * sample
        }
        val rms = sqrt(energy / count)
        val levelDb = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else -96.0
        val chunkStartedAtMs = capturedAtMs - count * 1000L / AUDIO_SAMPLE_RATE
        if (levelDb >= MIN_SPEECH_DBFS && speechStartedAtMs == null) {
            speechStartedAtMs = chunkStartedAtMs
        }

        lastCapturedAtMs = capturedAtMs
        val output = decoder.acceptPcm(samples, count)
        val speechStart = speechStartedAtMs ?: return
        val text = output.text.trim()
        if (output.isFinal) {
            if (text.isNotEmpty()) onEvent?.invoke(SpeechEvent(text, speechStart, capturedAtMs, true, speakerVector = output.speakerVector, speakerFrames = output.speakerFrames))
            speechStartedAtMs = null
            previousPartial = ""
        } else if (text.isNotEmpty() && text != previousPartial) {
            previousPartial = text
            onEvent?.invoke(SpeechEvent(text, speechStart, capturedAtMs, false))
        }
    }

    override fun finishInput() {
        if (!started) return
        val output = decoder.finish()
        val start = speechStartedAtMs
        if (start != null && output != null && output.text.isNotBlank()) {
            onEvent?.invoke(SpeechEvent(output.text.trim(), start, lastCapturedAtMs, true,
                speakerVector = output.speakerVector, speakerFrames = output.speakerFrames))
        }
        speechStartedAtMs = null
        previousPartial = ""
    }

    override fun stop() {
        if (!started) return
        started = false
        onEvent = null
        speechStartedAtMs = null
        previousPartial = ""
        decoder.close()
    }

    private class VoskDecoder(model: Model, speakerModel: SpeakerModel?) : SpeechDecoder {
        private val recognizer = Recognizer(model, AUDIO_SAMPLE_RATE.toFloat()).apply {
            if (speakerModel != null) setSpeakerModel(speakerModel)
        }

        override fun acceptPcm(samples: ShortArray, count: Int): DecoderOutput {
            return if (recognizer.acceptWaveForm(samples, count)) {
                val result = JSONObject(recognizer.result)
                val vector = result.optJSONArray("spk")?.let { array -> List(array.length()) { array.getDouble(it).toFloat() } }
                DecoderOutput(true, result.optString("text"), vector, result.optInt("spk_frames"))
            } else {
                DecoderOutput(isFinal = false, text = JSONObject(recognizer.partialResult).optString("partial"))
            }
        }

        override fun finish(): DecoderOutput {
            val result = JSONObject(recognizer.finalResult)
            val vector = result.optJSONArray("spk")?.let { array -> List(array.length()) { array.getDouble(it).toFloat() } }
            return DecoderOutput(true, result.optString("text"), vector, result.optInt("spk_frames"))
        }

        override fun close() = recognizer.close()
    }

    private companion object {
        const val AUDIO_SAMPLE_RATE = 16_000
        const val MIN_SPEECH_DBFS = -52.0
    }
}
