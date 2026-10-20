package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEventTest {
    @Test
    fun partialAndFinalEventsUseTheAudioSpeechWindow() {
        val decoder = FakeSpeechDecoder(
            listOf(
                DecoderOutput(isFinal = false, text = "hello"),
                DecoderOutput(isFinal = true, text = "hello 世界")
            )
        )
        val engine = VoskSpeechEngine(decoder)
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(speechChunk(), 1600, capturedAtMs = 1_100L)
        engine.acceptPcm(speechChunk(), 1600, capturedAtMs = 1_200L)

        assertEquals(2, events.size)
        assertEquals("hello", events[0].text)
        assertFalse(events[0].isFinal)
        assertEquals(1_000L, events[0].startedAtMs)
        assertEquals(1_100L, events[0].endedAtMs)
        assertEquals("hello 世界", events[1].text)
        assertTrue(events[1].isFinal)
        assertEquals(1_000L, events[1].startedAtMs)
        assertEquals(1_200L, events[1].endedAtMs)
    }

    @Test
    fun silenceDoesNotEmitSpeechEvents() {
        val engine = VoskSpeechEngine(FakeSpeechDecoder(listOf(DecoderOutput(false, ""))))
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1600), 1600, capturedAtMs = 1_100L)

        assertTrue(events.isEmpty())
    }

    private fun speechChunk() = ShortArray(1600) { 5000 }

    private class FakeSpeechDecoder(outputs: List<DecoderOutput>) : SpeechDecoder {
        private val pending = ArrayDeque(outputs)
        private var current = DecoderOutput(isFinal = false, text = "")

        override fun acceptPcm(samples: ShortArray, count: Int): DecoderOutput {
            current = pending.removeFirst()
            return current
        }

        override fun close() = Unit
    }
}
