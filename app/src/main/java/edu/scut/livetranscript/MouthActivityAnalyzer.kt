package edu.scut.livetranscript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MouthActivityAnalyzer(context: Context, private val attributor: SpeakerAttributor) : AutoCloseable {
    private data class PreviousMouth(val openness: Float, val atMs: Long)

    private val appContext = context.applicationContext
    private val landmarkerDelegate = lazy {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(MAX_FACES)
            .setOutputFaceBlendshapes(true)
            .build()
        FaceLandmarker.createFromOptions(appContext, options)
    }
    private val landmarker by landmarkerDelegate
    private val previousMouthByTrack = mutableMapOf<Int, PreviousMouth>()

    fun process(bitmap: Bitmap, trackedFaces: List<TrackedFace>, atMs: Long): Int {
        if (trackedFaces.isEmpty()) {
            previousMouthByTrack.clear()
            attributor.clear()
            return 0
        }
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return 0
        val ownsInput = input !== bitmap
        try {
            val currentTrackIds = trackedFaces.map { it.id }.toSet()
            (previousMouthByTrack.keys - currentTrackIds).forEach(attributor::removeTrackSamples)
            previousMouthByTrack.keys.retainAll(currentTrackIds)
            val result = BitmapImageBuilder(input).build().use { mpImage ->
                landmarker.detectForVideo(mpImage, atMs)
            }
            val blendshapes = result.faceBlendshapes().orElse(emptyList())
            val matchedTrackIds = mutableSetOf<Int>()
            val usedTrackIds = mutableSetOf<Int>()

            result.faceLandmarks().forEachIndexed { faceIndex, landmarks ->
                val jawOpen = blendshapes.getOrNull(faceIndex)
                    ?.firstOrNull { it.categoryName() == JAW_OPEN }
                    ?.score() ?: return@forEachIndexed
                val faceBounds = boundsOf(landmarks, input.width, input.height) ?: return@forEachIndexed
                val match = trackedFaces.asSequence()
                    .filter { it.id !in usedTrackIds }
                    .map { it to overlap(faceBounds, it.bounds) }
                    .maxByOrNull { it.second }
                    ?.takeIf { it.second >= MIN_FACE_OVERLAP }
                    ?.first ?: return@forEachIndexed

                val previous = previousMouthByTrack[match.id]
                val elapsed = previous?.let { atMs - it.atMs } ?: 0L
                val activity = if (previous != null && elapsed in MIN_SAMPLE_GAP_MS..MAX_SAMPLE_GAP_MS) {
                    abs(jawOpen - previous.openness)
                } else {
                    0f
                }
                attributor.addSample(MouthSample(match.id, atMs, activity))
                previousMouthByTrack[match.id] = PreviousMouth(jawOpen, atMs)
                matchedTrackIds += match.id
                usedTrackIds += match.id
            }

            (currentTrackIds - matchedTrackIds).forEach { trackId ->
                previousMouthByTrack.remove(trackId)
                attributor.removeTrackSamples(trackId)
            }
            return matchedTrackIds.size
        } finally {
            if (ownsInput && !input.isRecycled) input.recycle()
        }
    }

    override fun close() {
        if (landmarkerDelegate.isInitialized()) landmarker.close()
        previousMouthByTrack.clear()
    }

    private fun boundsOf(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        width: Int,
        height: Int
    ): RectF? {
        if (landmarks.isEmpty()) return null
        val left = landmarks.minOf { it.x() } * width
        val top = landmarks.minOf { it.y() } * height
        val right = landmarks.maxOf { it.x() } * width
        val bottom = landmarks.maxOf { it.y() } * height
        if (right <= left || bottom <= top) return null
        return RectF(left, top, right, bottom)
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        private const val MODEL_ASSET = "models/face_landmarker.task"
        private const val JAW_OPEN = "jawOpen"
        private const val MAX_FACES = 8
        private const val MIN_FACE_OVERLAP = 0.12f
        private const val MIN_SAMPLE_GAP_MS = 80L
        private const val MAX_SAMPLE_GAP_MS = 1_000L
    }
}
