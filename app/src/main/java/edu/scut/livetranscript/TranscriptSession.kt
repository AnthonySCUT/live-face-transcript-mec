package edu.scut.livetranscript

data class TranscriptLine(val speaker: String, val text: String)

class TranscriptSession(
    private val resolveSpeaker: (SpeechEvent) -> String?,
    private val onPreview: (Long, TranscriptLine) -> Unit,
    private val onFinal: (Long, TranscriptLine) -> Unit
) {
    private data class FinalKey(val text: String, val startedAtMs: Long, val endedAtMs: Long)
    private data class PartialText(val text: String, val startedAtMs: Long)

    private var activeGeneration: Long? = null
    private var lastFinalKey: FinalKey? = null
    private var latestPartial: PartialText? = null

    @Synchronized
    fun start(generation: Long) {
        activeGeneration = generation
        lastFinalKey = null
        latestPartial = null
    }

    @Synchronized
    fun accept(generation: Long, event: SpeechEvent): Boolean {
        if (activeGeneration != generation) return false
        val eventText = event.text.trim()
        if (eventText.isEmpty()) return false
        val text = if (event.isFinal) {
            latestPartial
                ?.takeIf { it.startedAtMs == event.startedAtMs && it.text.startsWith(eventText) }
                ?.text ?: eventText
        } else {
            eventText
        }
        val finalKey = FinalKey(eventText, event.startedAtMs, event.endedAtMs)
        if (event.isFinal && lastFinalKey == finalKey) return false

        val speaker = resolveSpeaker(event)?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_SPEAKER
        val line = TranscriptLine(speaker, text)
        onPreview(generation, line)
        if (event.isFinal) {
            lastFinalKey = finalKey
            latestPartial = null
            onFinal(generation, line)
        } else {
            latestPartial = PartialText(text, event.startedAtMs)
        }
        return true
    }

    @Synchronized
    fun stop(generation: Long) {
        if (activeGeneration == generation) {
            activeGeneration = null
            lastFinalKey = null
            latestPartial = null
        }
    }

    private companion object {
        const val UNKNOWN_SPEAKER = "未确认说话人"
    }
}
