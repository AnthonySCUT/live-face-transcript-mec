package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTranscriptAccumulatorTest {
    @Test
    fun finalSegmentsAreAppendedInArrivalOrder() {
        val transcript = SpeechTranscriptAccumulator()

        transcript.accept(SpeechEvent("Good morning", 0, 500, true))
        val rendered = transcript.accept(SpeechEvent("welcome everyone", 500, 1_000, true))

        assertEquals("Good morning welcome everyone", rendered)
    }

    @Test
    fun finalSegmentReplacesItsPreviousPartial() {
        val transcript = SpeechTranscriptAccumulator()

        transcript.accept(SpeechEvent("we are tes", 0, 500, false))
        val rendered = transcript.accept(SpeechEvent("we are testing", 0, 700, true))

        assertEquals("we are testing", rendered)
    }

    @Test
    fun blankPartialClearsARejectedLocalDraft() {
        val transcript = SpeechTranscriptAccumulator()

        transcript.accept(SpeechEvent("我 怀疑 态度", 0, 2_000, false))
        val rendered = transcript.accept(SpeechEvent("", 0, 2_000, false))

        assertEquals("", rendered)
    }

    @Test
    fun ignoresLatePartialThatOverlapsFinalizedText() {
        val transcript = SpeechTranscriptAccumulator()

        transcript.accept(SpeechEvent("and unleash the longest stretch", 1_000, 3_000, true))
        val rendered = transcript.accept(SpeechEvent("and unleash the longest stretch", 1_100, 3_100, false))

        assertEquals("and unleash the longest stretch", rendered)
    }
}
