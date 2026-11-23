package edu.scut.livetranscript

class SpeechTranscriptAccumulator {
    private val finalSegments = mutableListOf<String>()
    private var partial = ""
    private var lastFinalEvent: SpeechEvent? = null

    @Synchronized
    fun accept(event: SpeechEvent): String {
        val text = event.text.trim()
        if (text.isBlank()) {
            partial = ""
            return render()
        }
        if (event.isFinal) {
            if (finalSegments.lastOrNull() != text) finalSegments += text
            lastFinalEvent = event
            partial = ""
        } else {
            val final = lastFinalEvent
            val overlapsFinal = final != null && event.startedAtMs <= final.endedAtMs + FINAL_PARTIAL_OVERLAP_MS &&
                event.endedAtMs >= final.startedAtMs - FINAL_PARTIAL_OVERLAP_MS
            partial = if (overlapsFinal) "" else text
        }
        return render()
    }

    @Synchronized
    fun text(): String = render()

    private fun render(): String = (finalSegments + partial).filter { it.isNotBlank() }.joinToString(" ")

    private companion object {
        const val FINAL_PARTIAL_OVERLAP_MS = 250L
    }
}
