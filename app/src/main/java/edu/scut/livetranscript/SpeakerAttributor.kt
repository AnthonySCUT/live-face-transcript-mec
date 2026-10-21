package edu.scut.livetranscript

import java.util.ArrayDeque
import kotlin.math.min

data class MouthSample(val trackId: Int, val atMs: Long, val activity: Float)

class SpeakerAttributor {
    private data class Score(val trackId: Int, val activity: Float, val activeSampleCount: Int)

    private val samples = ArrayDeque<MouthSample>()
    private var latestSampleAtMs = Long.MIN_VALUE

    @Synchronized
    fun addSample(sample: MouthSample) {
        if (!sample.activity.isFinite()) return
        if (latestSampleAtMs != Long.MIN_VALUE && sample.atMs < latestSampleAtMs - MAX_HISTORY_MS) return
        val normalized = sample.copy(activity = sample.activity.coerceIn(0f, 1f))
        samples.addLast(normalized)
        latestSampleAtMs = maxOf(latestSampleAtMs, sample.atMs)
        while (samples.isNotEmpty() && latestSampleAtMs - samples.first().atMs > MAX_HISTORY_MS) {
            samples.removeFirst()
        }
        while (samples.size > MAX_SAMPLE_COUNT) samples.removeFirst()
    }

    @Synchronized
    fun attribute(startedAtMs: Long, endedAtMs: Long, visibleTrackIds: Set<Int>): Int? {
        if (endedAtMs < startedAtMs || visibleTrackIds.isEmpty()) return null
        val windowStart = maxOf(startedAtMs - PRE_SPEECH_MS, endedAtMs - RECENT_ATTRIBUTION_TAIL_MS)
        val byTrack = samples.asSequence()
            .filter { it.trackId in visibleTrackIds && it.atMs in windowStart..endedAtMs }
            .groupBy { it.trackId }
        val multiFace = visibleTrackIds.size > 1
        val scores = visibleTrackIds.mapNotNull { trackId ->
            val values = byTrack[trackId].orEmpty()
            if (multiFace) {
                val active = values.map { it.activity }.filter { it >= MULTI_FACE_ACTIVE_SAMPLE }
                Score(trackId, active.fold(0f) { total, sample -> total + min(sample, MAX_MULTI_SAMPLE_ACTIVITY) }, active.size)
            } else {
                values.takeIf { it.isNotEmpty() }
                    ?.let { Score(trackId, it.maxOf { sample -> sample.activity }, it.size) }
            }
        }
        if (scores.isEmpty()) return null

        val ranked = scores
            .filter { !multiFace || it.activeSampleCount >= MULTI_FACE_MIN_ACTIVE_SAMPLES }
            .sortedByDescending { it.activity }
        if (ranked.isEmpty()) return null
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.activity ?: 0f
        val minimumActivity = if (multiFace) MIN_ACTIVITY else SINGLE_FACE_MIN_ACTIVITY
        if (best.activity < minimumActivity) return null
        val requiredMargin = min(MIN_MARGIN, best.activity * MIN_MARGIN_RATIO)
        if (multiFace && best.activity - runnerUp < requiredMargin) return null
        if (runnerUp > 0f && best.activity < runnerUp * MIN_RATIO) return null
        return best.trackId
    }

    @Synchronized
    fun attribute(event: SpeechEvent, visibleTrackIds: Set<Int>): Int? {
        if (!event.hasReliableSourceTimestamp) return null
        return attribute(event.startedAtMs, event.endedAtMs, visibleTrackIds)
    }

    @Synchronized
    fun removeTrackSamples(trackId: Int) {
        samples.removeIf { it.trackId == trackId }
    }

    @Synchronized
    fun clear() {
        samples.clear()
        latestSampleAtMs = Long.MIN_VALUE
    }

    private companion object {
        const val MAX_HISTORY_MS = 10_000L
        const val MAX_SAMPLE_COUNT = 512
        const val PRE_SPEECH_MS = 150L
        const val MIN_ACTIVITY = 0.003f
        const val SINGLE_FACE_MIN_ACTIVITY = 0.003f
        const val MULTI_FACE_ACTIVE_SAMPLE = 0.003f
        const val MULTI_FACE_MIN_ACTIVE_SAMPLES = 1
        const val MAX_MULTI_SAMPLE_ACTIVITY = 0.04f
        const val MIN_MARGIN = 0.025f
        const val MIN_MARGIN_RATIO = 0.75f
        const val MIN_RATIO = 1.5f
        const val RECENT_ATTRIBUTION_TAIL_MS = 1_000L
    }
}
