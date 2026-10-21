package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Test

class MouthMotionScoreTest {
    @Test
    fun ignoresOtherInferredMouthShapesWhenJawOpeningIsAlmostStill() {
        val activity = MouthMotionScore.activity(
            mapOf("jawOpen" to 0.10f, "mouthFunnel" to 0.08f, "mouthPucker" to 0.04f),
            mapOf("jawOpen" to 0.1005f, "mouthFunnel" to 0.60f, "mouthPucker" to 0.50f)
        )

        assertEquals(0.0005f, activity, 0.0001f)
    }

    @Test
    fun unchangedMouthShapesHaveNoActivity() {
        val shapes = mapOf("jawOpen" to 0.10f, "mouthFunnel" to 0.08f)

        assertEquals(0f, MouthMotionScore.activity(shapes, shapes), 0.0001f)
    }
}
