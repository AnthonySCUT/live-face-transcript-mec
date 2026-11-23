package edu.scut.livetranscript

import java.util.ArrayDeque
import kotlin.math.sqrt
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
    fun overlappingTracks(event: SpeechEvent, visibleTrackIds: Set<Int>): Set<Int> {
        if (!event.hasReliableSourceTimestamp || visibleTrackIds.size < 2 || event.endedAtMs < event.startedAtMs) return emptySet()
        val windowStart = event.startedAtMs - PRE_SPEECH_MS
        val active = samples.filter {
            it.trackId in visibleTrackIds && it.atMs in windowStart..event.endedAtMs && it.activity >= MULTI_FACE_ACTIVE_SAMPLE
        }
        val overlapping = mutableSetOf<Int>()
        for (first in active.indices) for (second in first + 1 until active.size) {
            val a = active[first]
            val b = active[second]
            if (a.trackId != b.trackId && kotlin.math.abs(a.atMs - b.atMs) <= OVERLAP_WINDOW_MS) {
                overlapping += a.trackId
                overlapping += b.trackId
            }
        }
        return overlapping.takeIf { it.size >= 2 } ?: emptySet()
    }

    @Synchronized
    fun overlappingTracks(event: SpeechEvent): Set<Int> {
        if (!event.hasReliableSourceTimestamp || event.endedAtMs < event.startedAtMs) return emptySet()
        val windowStart = event.startedAtMs - PRE_SPEECH_MS
        val trackIds = samples.asSequence()
            .filter { it.atMs in windowStart..event.endedAtMs }
            .map { it.trackId }
            .toSet()
        return overlappingTracks(event, trackIds)
    }

    @Synchronized
    fun associateSeparatedSources(
        event: SpeechEvent,
        sources: List<ShortArray>,
        candidateTrackIds: Set<Int>,
        sampleRate: Int = 16_000,
        windowMs: Long = 200L
    ): Map<Int, Int> {
        if (sources.size != 2 || candidateTrackIds.size < 2 || event.endedAtMs <= event.startedAtMs) return emptyMap()
        val windowSamples = sampleRate * windowMs / 1000L
        val windows = minOf(
            ((event.endedAtMs - event.startedAtMs) / windowMs).toInt(),
            sources.minOf { (it.size / windowSamples).toInt() }
        )
        if (windows < 3) return emptyMap()
        val tracks = candidateTrackIds.sorted().take(2)
        val audioProfiles = sources.map { source ->
            (0 until windows).map { index ->
                val start = (index * windowSamples).toInt()
                val end = (start + windowSamples).toInt()
                sqrt(source.sliceArray(start until end).map { it.toDouble() * it }.average()).toFloat()
            }
        }
        val mouthProfiles = tracks.map { trackId ->
            (0 until windows).map { index ->
                val start = event.startedAtMs + index * windowMs
                val end = start + windowMs
                samples.asSequence()
                    .filter { it.trackId == trackId && it.atMs in start..end }
                    .map { it.activity.toDouble() }
                    .average()
                    .takeIf { it.isFinite() }
                    ?.toFloat() ?: 0f
            }
        }
        val direct = correlation(audioProfiles[0], mouthProfiles[0]) + correlation(audioProfiles[1], mouthProfiles[1])
        val swapped = correlation(audioProfiles[0], mouthProfiles[1]) + correlation(audioProfiles[1], mouthProfiles[0])
        val best = maxOf(direct, swapped)
        if (best < MIN_SEPARATION_CORRELATION_SUM || kotlin.math.abs(direct - swapped) < MIN_SEPARATION_MARGIN) return emptyMap()
        return if (direct > swapped) mapOf(0 to tracks[0], 1 to tracks[1]) else mapOf(0 to tracks[1], 1 to tracks[0])
    }

    private fun correlation(first: List<Float>, second: List<Float>): Double {
        val meanFirst = first.average()
        val meanSecond = second.average()
        val centeredFirst = first.map { it - meanFirst }
        val centeredSecond = second.map { it - meanSecond }
        val denominator = sqrt(centeredFirst.sumOf { it * it } * centeredSecond.sumOf { it * it })
        if (denominator < 1e-9) return 0.0
        return centeredFirst.indices.sumOf { centeredFirst[it] * centeredSecond[it] } / denominator
    }

    @Synchronized
    fun removeTrackSamples(trackId: Int) {
        samples.removeIf { it.trackId == trackId }
    }

    @Synchronized
    fun samplesBetween(startedAtMs: Long, endedAtMs: Long): List<MouthSample> =
        samples.filter { it.atMs in startedAtMs..endedAtMs }

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
        const val OVERLAP_WINDOW_MS = 250L
        const val MIN_SEPARATION_CORRELATION_SUM = 0.25
        const val MIN_SEPARATION_MARGIN = 0.12
    }
}
