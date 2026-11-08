package edu.scut.livetranscript

import org.junit.Assert.*
import org.junit.Test

class VoiceprintMatcherTest {
    private fun vector(index: Int) = FloatArray(128).apply { this[index] = 1f }

    @Test fun matchesDistinctProfileAndRejectsAmbiguity() {
        assertEquals("Alice", VoiceprintMatcher.match(vector(0), mapOf("Alice" to vector(0), "Bob" to vector(1))))
        assertNull(VoiceprintMatcher.match(vector(0), mapOf("Alice" to vector(0), "Bob" to vector(0))))
        assertNull(VoiceprintMatcher.match(vector(2), mapOf("Alice" to vector(0))))
    }

    @Test fun rejectsInvalidVectors() {
        assertFalse(VoiceprintMatcher.valid(FloatArray(128)))
        assertFalse(VoiceprintMatcher.valid(floatArrayOf(1f)))
        assertFalse(VoiceprintMatcher.valid(vector(0).apply { this[2] = Float.NaN }))
        assertNull(VoiceprintMatcher.match(vector(0), emptyMap()))
    }
}
