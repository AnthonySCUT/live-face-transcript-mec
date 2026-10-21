package edu.scut.livetranscript

import android.graphics.RectF

data class TrackedFace(
    val id: Int,
    val label: String,
    val bounds: RectF,
    val embedding: FloatArray,
    val known: Boolean
)

class FaceTracker {
    private data class Track(
        val id: Int,
        var label: String,
        var bounds: RectF,
        var embedding: FloatArray,
        var known: Boolean,
        var seenAt: Long
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    @Synchronized fun clear() {
        tracks.clear()
        nextId = 1
    }

    @Synchronized fun update(samples: List<FaceSample>, knownPeople: List<Participant>, now: Long): List<TrackedFace> {
        tracks.removeAll { now - it.seenAt > TRACK_TIMEOUT_MS }
        val claimed = mutableSetOf<Int>()
        return samples.map { sample ->
            val knownMatch = knownPeople
                .map { it to FacePipeline.cosine(it.embedding, sample.embedding) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= FacePipeline.KNOWN_THRESHOLD }
            val knownLabel = knownMatch?.first?.name
            val prior = tracks.asSequence()
                .filter { it.id !in claimed }
                .map { it to associationScore(it, sample) }
                .maxByOrNull { it.second }
                ?.takeIf { (_, score) -> score >= 0.24 }
                ?.first

            val track = prior ?: Track(nextId++, strangerName(nextId - 1), sample.bounds, sample.embedding, false, now).also(tracks::add)
            if (knownLabel != null) {
                track.label = knownLabel
                track.known = true
            } else if (track.known && prior != null) {
                // Keep a recognized label briefly through a noisy frame.
            } else if (prior == null) {
                track.label = strangerName(track.id)
                track.known = false
            }
            track.bounds = RectF(sample.bounds)
            track.embedding = sample.embedding
            track.seenAt = now
            claimed += track.id
            TrackedFace(track.id, track.label, RectF(sample.bounds), sample.embedding, track.known)
        }.sortedBy { it.bounds.left }
    }

    private fun associationScore(track: Track, sample: FaceSample): Double {
        val similarity = FacePipeline.cosine(track.embedding, sample.embedding)
        val overlap = iou(track.bounds, sample.bounds)
        return maxOf(if (similarity > 0.28) similarity else 0.0, overlap * 0.85)
    }

    @Synchronized fun speakerLabel(selectedId: Int?, visible: List<TrackedFace>): String {
        val selected = visible.firstOrNull { it.id == selectedId }
        if (selected != null) return selected.label
        if (visible.size == 1) return visible.first().label
        return "未确认说话人"
    }

    private fun iou(a: RectF, b: RectF): Double {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (maxOf(0f, right - left) * maxOf(0f, bottom - top)).toDouble()
        val union = (a.width() * a.height() + b.width() * b.height()).toDouble() - intersection
        return if (union <= 0.0) 0.0 else intersection / union
    }

    private fun strangerName(id: Int): String {
        var number = id
        val letters = StringBuilder()
        while (number > 0) {
            number--
            letters.append(('A'.code + number % 26).toChar())
            number /= 26
        }
        return "陌生人 ${letters.reverse()}"
    }

    companion object { private const val TRACK_TIMEOUT_MS = 2200L }
}
