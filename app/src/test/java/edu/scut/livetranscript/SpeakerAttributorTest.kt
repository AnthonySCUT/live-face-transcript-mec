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
    fun selectsClearlyDominantMouthWithSubtleMovement() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.030f), MouthSample(1, 1_100, 0.031f), MouthSample(1, 1_200, 0.029f),
            MouthSample(2, 1_000, 0.002f), MouthSample(2, 1_100, 0.001f), MouthSample(2, 1_200, 0.002f)
        )

        assertEquals(1, attributor.attribute(1_000, 1_250, setOf(1, 2)))
    }

    @Test
    fun ignoresASingleMotionSpikeOnTheWrongFaceWhenAnotherFaceMovesContinuously() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.09f), MouthSample(1, 1_100, 0.001f), MouthSample(1, 1_200, 0.001f),
            MouthSample(2, 1_000, 0.030f), MouthSample(2, 1_100, 0.031f), MouthSample(2, 1_200, 0.029f)
        )

        assertEquals(2, attributor.attribute(1_000, 1_250, setOf(1, 2)))
    }

    @Test
    fun prefersTheRecentMouthMotionWhenSpeakersChangeDuringOneLongSpeechWindow() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.09f), MouthSample(1, 1_200, 0.08f),
            MouthSample(2, 4_700, 0.03f)
        )

        assertEquals(2, attributor.attribute(1_000, 5_000, setOf(1, 2)))
    }

    @Test
    fun historicMouthMotionDoesNotIdentifySomeoneWhoHasBeenCoveredForTheRecentTail() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.09f), MouthSample(1, 1_200, 0.08f),
            MouthSample(2, 4_700, 0f), MouthSample(2, 4_900, 0f)
        )

        assertNull(attributor.attribute(1_000, 5_000, setOf(1, 2)))
    }

    @Test
    fun attributesOneVisibleFaceFromModestMotionDespiteQuietFrames() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0f), MouthSample(1, 1_050, 0.012f)
        )

        assertEquals(1, attributor.attribute(1_000, 1_100, setOf(1)))
    }

    @Test
    fun attributesSmallRecentJawMotionWhenTwoFacesAreVisible() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.004f), MouthSample(2, 1_000, 0f)
        )

        assertEquals(1, attributor.attribute(1_000, 1_100, setOf(1, 2)))
    }

    @Test
    fun oneVisibleFaceWithNoMouthMotionRemainsUnconfirmed() {
        val attributor = attributor(MouthSample(1, 1_050, 0f))

        assertNull(attributor.attribute(1_000, 1_100, setOf(1)))
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

        assertEquals(2, attributor.attribute(1_000, 1_200, setOf(2)))
    }

    @Test
    fun aTrackLostByLandmarkerInvalidatesItsRecentMotion() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.10f), MouthSample(1, 1_100, 0.12f),
            MouthSample(2, 1_000, 0.00f), MouthSample(2, 1_100, 0.00f)
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
