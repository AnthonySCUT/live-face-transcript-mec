package edu.scut.livetranscript

data class TranscriptLine(val speaker: String, val text: String)

class TranscriptSession(
    private val resolveSpeaker: (SpeechEvent) -> String?,
    private val onPreview: (Long, TranscriptLine) -> Unit,
    private val onFinal: (Long, TranscriptLine) -> Unit
) {
    private data class FinalKey(val text: String, val startedAtMs: Long, val endedAtMs: Long, val speaker: String)
    private var activeGeneration: Long? = null
    private var lastFinalKey: FinalKey? = null

    @Synchronized
    fun start(generation: Long) {
        activeGeneration = generation
        lastFinalKey = null
    }

    @Synchronized
    fun accept(generation: Long, event: SpeechEvent, speakerOverride: String? = null): Boolean {
        if (activeGeneration != generation) return false
        val eventText = event.text.trim()
        if (eventText.isEmpty()) {
            if (!event.isFinal) onPreview(generation, TranscriptLine("", ""))
            return false
        }
        val text = eventText
        val speaker = speakerOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolveSpeaker(event)?.trim()?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN_SPEAKER
        val finalKey = FinalKey(eventText, event.startedAtMs, event.endedAtMs, speaker)
        if (event.isFinal && lastFinalKey == finalKey) return false

        val line = TranscriptLine(speaker, text)
        onPreview(generation, line)
        if (event.isFinal) {
            lastFinalKey = finalKey
            onFinal(generation, line)
        }
        return true
    }

    @Synchronized
    fun stop(generation: Long) {
        if (activeGeneration == generation) {
            activeGeneration = null
            lastFinalKey = null
        }
    }

    private companion object {
        const val UNKNOWN_SPEAKER = "未确认说话人"
    }
}
