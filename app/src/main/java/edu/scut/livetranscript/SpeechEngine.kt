package edu.scut.livetranscript

data class SpeechEvent(
    val text: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val isFinal: Boolean,
    val hasReliableSourceTimestamp: Boolean = true,
    val confidence: Double? = null
)

data class SpeechTransferMetrics(val uploadedPayloadBytes: Long, val downloadedPayloadBytes: Long)

interface SpeechEngine {
    fun start(onEvent: (SpeechEvent) -> Unit)
    fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long)
    fun stop()
}

data class DecoderOutput(val isFinal: Boolean, val text: String, val confidence: Double? = null)

interface SpeechDecoder {
    fun acceptPcm(samples: ShortArray, count: Int): DecoderOutput
    fun finish(): DecoderOutput? = null
    fun close()
}
