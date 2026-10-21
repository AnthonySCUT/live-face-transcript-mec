package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerAttributorTest {
    @Test
    fun selectsTheOnlyClearlyMovingMouth() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.08f), MouthSample(1, 1_100, 0.10f), MouthSample(1, 1_200, 0.09f),
            MouthSample(2, 1_000, 0.01f), MouthSample(2, 1_100, 0.01f), MouthSample(2, 1_200, 0.02f)
        )

        assertEquals(1, attributor.attribute(1_000, 1_250, setOf(1, 2)))
    }

    @Test
    fun similarMouthActivityRemainsUnconfirmed() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.08f), MouthSample(1, 1_100, 0.07f), MouthSample(1, 1_200, 0.09f),
            MouthSample(2, 1_000, 0.07f), MouthSample(2, 1_100, 0.08f), MouthSample(2, 1_200, 0.08f)
        )

        assertNull(attributor.attribute(1_000, 1_250, setOf(1, 2)))
    }

    @Test
    fun samplesOutsideSpeechWindowAreIgnored() {
        val attributor = attributor(
            MouthSample(1, 500, 0.10f), MouthSample(1, 600, 0.12f),
            MouthSample(2, 500, 0.00f), MouthSample(2, 600, 0.01f)
        )

        assertNull(attributor.attribute(1_000, 1_300, setOf(1, 2)))
    }

    @Test
    fun trackNotVisibleAtAttributionTimeCannotBeSelected() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.10f), MouthSample(1, 1_100, 0.12f),
            MouthSample(2, 1_000, 0.00f), MouthSample(2, 1_100, 0.01f)
        )

        assertNull(attributor.attribute(1_000, 1_200, setOf(2)))
    }

    @Test
    fun aTrackLostByLandmarkerInvalidatesItsRecentMotion() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.10f), MouthSample(1, 1_100, 0.12f),
            MouthSample(2, 1_000, 0.00f), MouthSample(2, 1_100, 0.01f)
        )
        attributor.removeTrackSamples(1)

        assertNull(attributor.attribute(1_000, 1_200, setOf(1, 2)))
    }

    @Test
    fun unreliableRemoteAudioTimeCannotSelectASpeaker() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.10f), MouthSample(1, 1_100, 0.12f),
            MouthSample(2, 1_000, 0.00f), MouthSample(2, 1_100, 0.01f)
        )
        val event = SpeechEvent("text", 1_000L, 1_200L, true, hasReliableSourceTimestamp = false)

        assertNull(attributor.attribute(event, setOf(1, 2)))
    }

    private fun attributor(vararg samples: MouthSample) = SpeakerAttributor().apply {
        samples.forEach(::addSample)
    }
}
