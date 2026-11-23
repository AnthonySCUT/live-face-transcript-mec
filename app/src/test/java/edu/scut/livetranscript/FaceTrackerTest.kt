package edu.scut.livetranscript

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FaceTrackerTest {
    @Test
    fun keepsTheSameTrackAcrossABriefOcclusion() {
        val tracker = FaceTracker()
        val first = tracker.update(listOf(sample(10f)), emptyList(), 1_000L).single()

        tracker.update(emptyList(), emptyList(), 6_000L)
        val returned = tracker.update(listOf(sample(18f)), emptyList(), 6_200L).single()

        assertEquals(first.id, returned.id)
        assertEquals(first.label, tracker.labelForTrackId(returned.id))
    }

    @Test
    fun expiresATrackAfterTheOcclusionRetentionWindow() {
        val tracker = FaceTracker()
        val first = tracker.update(listOf(sample(10f)), emptyList(), 1_000L).single()

        val returned = tracker.update(listOf(sample(18f)), emptyList(), 10_000L).single()

        assertNotEquals(first.id, returned.id)
    }

    private fun sample(left: Float) = FaceSample(RectF(left, 20f, left + 80f, 100f), floatArrayOf(1f, 0f, 0f))
}
