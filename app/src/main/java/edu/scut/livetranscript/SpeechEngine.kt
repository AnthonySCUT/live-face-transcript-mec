package edu.scut.livetranscript

data class SpeechEvent(
    val text: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val isFinal: Boolean,
    val hasReliableSourceTimestamp: Boolean = true,
    val speakerVector: List<Float>? = null,
    val speakerFrames: Int = 0
)

interface SpeechEngine {
    fun start(onEvent: (SpeechEvent) -> Unit)
    fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long)
    fun finishInput() = stop()
    fun stop()
}

data class DecoderOutput(val isFinal: Boolean, val text: String, val speakerVector: List<Float>? = null, val speakerFrames: Int = 0)

interface SpeechDecoder {
    fun acceptPcm(samples: ShortArray, count: Int): DecoderOutput
    fun finish(): DecoderOutput? = null
    fun close()
}
