package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceprintTest {
    @Test
    fun voicedPcmProducesNormalizedSignatureAndSilenceIsRejected() {
        val samples = ShortArray(32_000) { index ->
            val time = index / 16_000.0
            (7_000 * sin(2 * PI * 145 * time) + 2_000 * sin(2 * PI * 290 * time)).toInt().toShort()
        }

        val signature = VoiceprintExtractor.extract(samples)

        assertNotNull(signature)
        assertEquals(32, signature!!.size)
        assertTrue(kotlin.math.abs(sqrt(signature.sumOf { it.toDouble() * it }) - 1.0) < 1e-5)
        assertNull(VoiceprintExtractor.extract(ShortArray(32_000)))
    }

    @Test
    fun matcherReturnsUniqueVoiceAndLeavesEqualProfilesUnconfirmed() {
        val first = FloatArray(32).apply { this[0] = 1f }
        val second = FloatArray(32).apply { this[1] = 1f }
        val match = VoiceprintMatcher.match(first, listOf(
            Participant("林", FloatArray(0), first),
            Participant("周", FloatArray(0), second)
        ))
        val ambiguous = VoiceprintMatcher.match(first, listOf(
            Participant("林", FloatArray(0), first),
            Participant("林同音", FloatArray(0), first.copyOf())
        ))

        assertEquals("林", match?.name)
        assertNull(ambiguous)
    }

    @Test
    fun pcmTimelineReturnsSamplesFromTheRequestedTimeWindow() {
        val timeline = PcmTimelineBuffer()
        timeline.append(ShortArray(1_600) { 1 }, 1_600, 100)
        timeline.append(ShortArray(1_600) { 2 }, 1_600, 200)

        val selected = timeline.slice(50, 150)

        assertEquals(1_600, selected.size)
        assertTrue(selected.take(800).all { it == 1.toShort() })
        assertTrue(selected.drop(800).all { it == 2.toShort() })
    }
}
