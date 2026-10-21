package edu.scut.livetranscript

data class TranscriptLine(val speaker: String, val text: String)

class TranscriptSession(
    private val resolveSpeaker: (SpeechEvent) -> String?,
    private val onPreview: (Long, TranscriptLine) -> Unit,
    private val onFinal: (Long, TranscriptLine) -> Unit
) {
    private data class FinalKey(val text: String, val startedAtMs: Long, val endedAtMs: Long)

    private var activeGeneration: Long? = null
    private var lastFinalKey: FinalKey? = null

    @Synchronized
    fun start(generation: Long) {
        activeGeneration = generation
        lastFinalKey = null
    }

    @Synchronized
    fun accept(generation: Long, event: SpeechEvent): Boolean {
        if (activeGeneration != generation) return false
        val text = event.text.trim()
        if (text.isEmpty()) return false
        val key = FinalKey(text, event.startedAtMs, event.endedAtMs)
        if (event.isFinal && lastFinalKey == key) return false

        val speaker = resolveSpeaker(event)?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_SPEAKER
        val line = TranscriptLine(speaker, text)
        onPreview(generation, line)
        if (event.isFinal) {
            lastFinalKey = key
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
