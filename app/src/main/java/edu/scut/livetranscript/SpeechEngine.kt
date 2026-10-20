package edu.scut.livetranscript

data class SpeechEvent(
    val text: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val isFinal: Boolean
)

interface SpeechEngine {
    fun start(onEvent: (SpeechEvent) -> Unit)
    fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long)
    fun stop()
}

data class DecoderOutput(val isFinal: Boolean, val text: String)

interface SpeechDecoder {
    fun acceptPcm(samples: ShortArray, count: Int): DecoderOutput
    fun close()
}
