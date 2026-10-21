package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MouthTrackMatcherTest {
    @Test
    fun selectsTheClearlyOverlappingTrack() {
        val selected = MouthTrackMatcher.selectTrack(
            listOf(1 to 0.72f, 2 to 0.20f),
            minimumOverlap = 0.12f,
            minimumMargin = 0.08f
        )

        assertEquals(1, selected)
    }

    @Test
    fun rejectsTwoTracksWithSimilarOverlap() {
        val selected = MouthTrackMatcher.selectTrack(
            listOf(1 to 0.34f, 2 to 0.30f),
            minimumOverlap = 0.12f,
            minimumMargin = 0.08f
        )

        assertNull(selected)
    }

    @Test
    fun rejectsWhenNoTrackHasEnoughOverlap() {
        val selected = MouthTrackMatcher.selectTrack(
            listOf(1 to 0.10f, 2 to 0.08f),
            minimumOverlap = 0.12f,
            minimumMargin = 0.08f
        )

        assertNull(selected)
    }
}
