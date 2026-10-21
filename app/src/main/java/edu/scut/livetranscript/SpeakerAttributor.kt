package edu.scut.livetranscript

import java.util.ArrayDeque

data class MouthSample(val trackId: Int, val atMs: Long, val activity: Float)

class SpeakerAttributor {
    private data class Score(val trackId: Int, val activity: Float)

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
        val windowStart = startedAtMs - PRE_SPEECH_MS
        val byTrack = samples.asSequence()
            .filter { it.trackId in visibleTrackIds && it.atMs in windowStart..endedAtMs }
            .groupBy { it.trackId }
        val scores = visibleTrackIds.mapNotNull { trackId ->
            val values = byTrack[trackId].orEmpty()
            if (values.size < MIN_EVIDENCE_SAMPLES) null
            else Score(trackId, values.map { it.activity }.average().toFloat())
        }
        if (scores.isEmpty() || (visibleTrackIds.size > 1 && scores.size != visibleTrackIds.size)) return null

        val ranked = scores.sortedByDescending { it.activity }
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.activity ?: 0f
        if (best.activity < MIN_ACTIVITY || best.activity - runnerUp < MIN_MARGIN) return null
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
        const val MIN_EVIDENCE_SAMPLES = 2
        const val MIN_ACTIVITY = 0.035f
        const val MIN_MARGIN = 0.025f
        const val MIN_RATIO = 1.5f
    }
}
