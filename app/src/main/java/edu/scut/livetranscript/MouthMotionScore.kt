package edu.scut.livetranscript

import kotlin.math.abs

internal object MouthMotionScore {
    fun activity(previous: Map<String, Float>, current: Map<String, Float>): Float =
        abs((current["jawOpen"] ?: 0f) - (previous["jawOpen"] ?: 0f)).coerceIn(0f, 1f)
}
