package edu.scut.livetranscript

import kotlin.math.sqrt

/** Experimental Vosk speaker-vector comparison; thresholds require device/data calibration. */
object VoiceprintMatcher {
    fun valid(vector: FloatArray): Boolean = vector.size == 128 &&
        vector.all { it.isFinite() } && vector.any { it != 0f }

    fun match(vector: FloatArray, profiles: Map<String, FloatArray>): String? {
        if (!valid(vector)) return null
        val scores = profiles.mapNotNull { (name, profile) ->
            if (!valid(profile)) null else {
                val dot = vector.indices.sumOf { vector[it].toDouble() * profile[it] }
                val norm = sqrt(vector.sumOf { it.toDouble() * it } * profile.sumOf { it.toDouble() * it })
                name to dot / norm
            }
        }.sortedByDescending { it.second }
        val best = scores.firstOrNull() ?: return null
        if (best.second < 0.75) return null
        if (scores.size > 1 && best.second - scores[1].second < 0.15) return null
        return best.first
    }
}
