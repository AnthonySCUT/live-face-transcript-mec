package edu.scut.livetranscript

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.log10
import kotlin.math.sqrt

class VoskSpeechEngine(private val decoder: SpeechDecoder) : SpeechEngine {
    constructor(model: Model) : this(VoskDecoder(model))

    private var onEvent: ((SpeechEvent) -> Unit)? = null
    private var speechStartedAtMs: Long? = null
    private var lastCapturedAtMs = 0L
    private var previousPartial = ""
    private var started = false

    override fun start(onEvent: (SpeechEvent) -> Unit) {
        this.onEvent = onEvent
        speechStartedAtMs = null
        lastCapturedAtMs = 0L
        previousPartial = ""
        started = true
    }

    override fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long) {
        if (!started || count <= 0) return
        lastCapturedAtMs = capturedAtMs

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

        val output = decoder.acceptPcm(samples, count)
        val speechStart = speechStartedAtMs ?: return
        val text = output.text.trim()
        if (output.isFinal) {
            if (text.isNotEmpty()) onEvent?.invoke(SpeechEvent(text, speechStart, capturedAtMs, true, confidence = output.confidence))
            speechStartedAtMs = null
            previousPartial = ""
        } else if (text.isNotEmpty() && text != previousPartial) {
            previousPartial = text
            onEvent?.invoke(SpeechEvent(text, speechStart, capturedAtMs, false))
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        val output = decoder.finish()
        val speechStart = speechStartedAtMs ?: lastCapturedAtMs
        val text = output?.text?.trim().orEmpty()
        if (text.isNotEmpty()) onEvent?.invoke(SpeechEvent(text, speechStart, lastCapturedAtMs, true, confidence = output?.confidence))
        onEvent = null
        speechStartedAtMs = null
        lastCapturedAtMs = 0L
        previousPartial = ""
        decoder.close()
    }

    private class VoskDecoder(model: Model) : SpeechDecoder {
        private val recognizer = Recognizer(model, AUDIO_SAMPLE_RATE.toFloat())

        init {
            recognizer.setWords(true)
        }

        override fun acceptPcm(samples: ShortArray, count: Int): DecoderOutput {
            return if (recognizer.acceptWaveForm(samples, count)) {
                val result = JSONObject(recognizer.result)
                DecoderOutput(isFinal = true, text = result.optString("text"), confidence = averageConfidence(result))
            } else {
                DecoderOutput(isFinal = false, text = JSONObject(recognizer.partialResult).optString("partial"))
            }
        }

        override fun finish(): DecoderOutput {
            val result = JSONObject(recognizer.finalResult)
            return DecoderOutput(isFinal = true, text = result.optString("text"), confidence = averageConfidence(result))
        }

        private fun averageConfidence(result: JSONObject): Double? {
            val words = result.optJSONArray("result") ?: return null
            val scores = (0 until words.length()).mapNotNull { index ->
                words.optJSONObject(index)?.optDouble("conf")?.takeIf(Double::isFinite)
            }
            return scores.takeIf { it.isNotEmpty() }?.average()
        }

        override fun close() = recognizer.close()
    }

    private companion object {
        const val AUDIO_SAMPLE_RATE = 16_000
        const val MIN_SPEECH_DBFS = -52.0
    }
}

class BilingualVoskSpeechEngine(
    private val chinese: SpeechEngine,
    private val english: SpeechEngine
) : SpeechEngine {
    constructor(chineseModel: Model, englishModel: Model) : this(
        VoskSpeechEngine(chineseModel),
        VoskSpeechEngine(englishModel)
    )

    private var callback: ((SpeechEvent) -> Unit)? = null
    private var started = false
    private var latestChinese: SpeechEvent? = null
    private var latestEnglish: SpeechEvent? = null
    private var finalChinese: SpeechEvent? = null
    private var finalEnglish: SpeechEvent? = null
    private var partialVisible = false

    override fun start(onEvent: (SpeechEvent) -> Unit) {
        callback = onEvent
        started = true
        latestChinese = null
        latestEnglish = null
        finalChinese = null
        finalEnglish = null
        partialVisible = false
        chinese.start { event -> updateCandidate(event, true) }
        english.start { event -> updateCandidate(event, false) }
    }

    override fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long) {
        if (!started) return
        chinese.acceptPcm(samples, count, capturedAtMs)
        english.acceptPcm(samples, count, capturedAtMs)
        if (finalChinese != null && finalEnglish != null) emitPendingFinals()
        else if (finalChinese == null && finalEnglish == null) emitBestPartial()
    }

    override fun stop() {
        if (!started) return
        started = false
        chinese.stop()
        english.stop()
        if (finalChinese != null || finalEnglish != null) emitPendingFinals()
        callback = null
        latestChinese = null
        latestEnglish = null
        finalChinese = null
        finalEnglish = null
    }

    private fun updateCandidate(event: SpeechEvent, isChinese: Boolean) {
        if (event.isFinal) {
            val previous = if (isChinese) finalChinese else finalEnglish
            if (previous != null && event.startedAtMs > previous.endedAtMs + 100L) emitPendingFinals()
            if (isChinese) finalChinese = event else finalEnglish = event
        } else if (isChinese) {
            latestChinese = event
        } else {
            latestEnglish = event
        }
    }

    private fun emitBestPartial() {
        val best = listOfNotNull(latestChinese, latestEnglish)
            .filter { !it.isFinal && isUsable(it) && hasPlausibleSpeechRate(it) }
            .maxByOrNull(::languageScore)
        if (best != null) {
            callback?.invoke(best)
            partialVisible = true
        } else if (partialVisible) {
            val previous = listOfNotNull(latestChinese, latestEnglish).maxByOrNull { it.endedAtMs }
            if (previous != null) callback?.invoke(SpeechEvent("", previous.startedAtMs, previous.endedAtMs, false))
            partialVisible = false
        }
    }

    private fun emitPendingFinals() {
        val finals = listOfNotNull(finalChinese, finalEnglish)
            .filter { isUsable(it) && hasPlausibleSpeechRate(it) }
        val partials = if (finals.isEmpty()) emptyList() else listOfNotNull(latestChinese, latestEnglish)
            .filter { !it.isFinal && isUsable(it) && hasPlausibleSpeechRate(it) && finals.any { final ->
                kotlin.math.abs(it.startedAtMs - final.startedAtMs) <= 1_000L &&
                    kotlin.math.abs(it.endedAtMs - final.endedAtMs) <= 2_000L
            } }
        val candidates = finals.map { it to languageScore(it).toDouble() } +
            partials.map { it to languageScore(it) * 0.85 }
        val best = candidates.maxByOrNull { it.second }?.first
        if (best != null) callback?.invoke(best.copy(isFinal = true))
        else if (partialVisible) callback?.invoke(SpeechEvent("", 0L, 0L, true))
        partialVisible = false
        finalChinese = null
        finalEnglish = null
    }

    private fun languageScore(event: SpeechEvent): Int {
        val cjk = event.text.count { it in '\u4e00'..'\u9fff' }
        val latin = event.text.count { it.isLetter() && it.code < 128 }
        return if (cjk >= latin) cjk * 2 + latin else latin * 2 + cjk
    }

    private fun hasPlausibleSpeechRate(event: SpeechEvent): Boolean {
        val seconds = (event.endedAtMs - event.startedAtMs).coerceAtLeast(1L) / 1_000.0
        val cjk = event.text.count { it in '\u4e00'..'\u9fff' }
        val latinTokens = Regex("[A-Za-z]+(?:'[A-Za-z]+)?").findAll(event.text).map { it.value.lowercase() }.toList()
        val latinWords = latinTokens.size
        if (cjk == 0 && latinWords > 0 && latinTokens.all { it in NON_SPEECH_ENGLISH }) return false
        return when {
            cjk > 0 && latinWords == 0 -> cjk / seconds >= MIN_CJK_CHARS_PER_SECOND
            latinWords > 0 && cjk == 0 -> latinWords / seconds >= MIN_LATIN_WORDS_PER_SECOND
            else -> (cjk + latinWords) / seconds >= MIN_MIXED_TOKENS_PER_SECOND
        }
    }

    private fun isUsable(event: SpeechEvent): Boolean = event.text.isNotBlank() && '\uFFFD' !in event.text

    private companion object {
        const val MIN_CJK_CHARS_PER_SECOND = 1.05
        const val MIN_LATIN_WORDS_PER_SECOND = 0.4
        const val MIN_MIXED_TOKENS_PER_SECOND = 0.7
        val NON_SPEECH_ENGLISH = setOf("ha", "haha", "heh", "ah", "aah", "uh", "um", "hmm")
    }
}
