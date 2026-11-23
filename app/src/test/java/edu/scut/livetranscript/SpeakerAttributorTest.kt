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

    @Test
    fun detectsTwoFacesWithConcurrentMouthMotion() {
        val attributor = attributor(
            MouthSample(1, 1_000, 0.03f), MouthSample(1, 1_100, 0.04f),
            MouthSample(2, 1_050, 0.03f), MouthSample(2, 1_150, 0.04f)
        )

        assertEquals(setOf(1, 2), attributor.overlappingTracks(SpeechEvent("hello", 1_000, 1_200, true), setOf(1, 2)))
    }

    @Test
    fun detectsOverlapAcrossTheFullSpeechWindowWhenTheTailIsQuiet() {
        val attributor = attributor(
            MouthSample(1, 1_200, 0.04f), MouthSample(2, 1_250, 0.04f),
            MouthSample(1, 3_800, 0.00f), MouthSample(2, 3_850, 0.00f)
        )

        assertEquals(setOf(1, 2), attributor.overlappingTracks(SpeechEvent("hello", 1_000, 4_000, true), setOf(1, 2)))
    }

    @Test
    fun sequentialMouthMotionDoesNotCountAsOverlap() {
        val attributor = attributor(
            MouthSample(1, 1_100, 0.04f), MouthSample(1, 1_200, 0.05f),
            MouthSample(2, 1_700, 0.04f), MouthSample(2, 1_800, 0.05f)
        )

        assertEquals(emptySet<Int>(), attributor.overlappingTracks(SpeechEvent("hello", 1_000, 4_000, true), setOf(1, 2)))
    }

    @Test
    fun associatesSeparatedAudioWithTheMatchingMouthActivityPattern() {
        val attributor = attributor(
            MouthSample(1, 1_050, 0.9f), MouthSample(1, 1_250, 0.1f), MouthSample(1, 1_450, 0.9f), MouthSample(1, 1_650, 0.1f),
            MouthSample(2, 1_050, 0.1f), MouthSample(2, 1_250, 0.9f), MouthSample(2, 1_450, 0.1f), MouthSample(2, 1_650, 0.9f)
        )
        val first = shortArrayOf(8_000, 8_000, 500, 500, 8_000, 8_000, 500, 500)
        val second = shortArrayOf(500, 500, 8_000, 8_000, 500, 500, 8_000, 8_000)

        assertEquals(
            mapOf(0 to 1, 1 to 2),
            attributor.associateSeparatedSources(SpeechEvent("overlap", 1_000, 1_800, true), listOf(first, second), setOf(1, 2), sampleRate = 10)
        )
    }

    @Test
    fun ambiguousMouthPatternsDoNotForceAnAudioToFaceAssignment() {
        val attributor = attributor(
            MouthSample(1, 1_050, 0.4f), MouthSample(1, 1_250, 0.4f), MouthSample(1, 1_450, 0.4f),
            MouthSample(2, 1_050, 0.4f), MouthSample(2, 1_250, 0.4f), MouthSample(2, 1_450, 0.4f)
        )
        val first = shortArrayOf(100, 200, 300, 400)
        val second = shortArrayOf(400, 300, 200, 100)

        assertEquals(
            emptyMap<Int, Int>(),
            attributor.associateSeparatedSources(SpeechEvent("overlap", 1_000, 1_600, true), listOf(first, second), setOf(1, 2), sampleRate = 10)
        )
    }

    private fun attributor(vararg samples: MouthSample) = SpeakerAttributor().apply {
        samples.forEach(::addSample)
    }
}
