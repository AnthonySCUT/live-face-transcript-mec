package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskSpeechEngineTest {
    @Test
    fun stopFlushesFinalRecognitionText() {
        val engine = VoskSpeechEngine(FinalizingDecoder("final phrase"))
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600) { 1_000 }, 1_600, 1_100L)
        engine.stop()

        assertTrue(events.last().isFinal)
        assertEquals("final phrase", events.last().text)
        assertEquals(1_000L, events.last().startedAtMs)
        assertEquals(1_100L, events.last().endedAtMs)
    }

    @Test
    fun stopWithEmptyFinalDoesNotEmitTranscript() {
        val engine = VoskSpeechEngine(FinalizingDecoder(""))
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600), 1_600, 1_100L)
        engine.stop()

        assertTrue(events.isEmpty())
    }

    @Test
    fun preservesWordConfidenceForBilingualCandidateScoring() {
        val engine = VoskSpeechEngine(FinalizingDecoder("hello", 0.82))
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600) { 1_000 }, 1_600, 1_100L)
        engine.stop()

        assertEquals(0.82, events.last().confidence!!, 0.0)
    }

    private class FinalizingDecoder(private val finalText: String, private val confidence: Double? = null) : SpeechDecoder {
        override fun acceptPcm(samples: ShortArray, count: Int) = DecoderOutput(false, "")
        override fun finish() = DecoderOutput(true, finalText, confidence)
        override fun close() = Unit
    }
}
