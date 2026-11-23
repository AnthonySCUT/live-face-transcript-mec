package edu.scut.livetranscript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.max
import kotlin.math.min

class MouthActivityAnalyzer(context: Context, private val attributor: SpeakerAttributor) : AutoCloseable {
    private data class PreviousMouth(val shapes: Map<String, Float>, val atMs: Long)

    private val appContext = context.applicationContext
    private val landmarkerDelegate = lazy {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(MAX_FACES)
            .setOutputFaceBlendshapes(true)
            .build()
        FaceLandmarker.createFromOptions(appContext, options)
    }
    private val landmarker by landmarkerDelegate
    private val previousMouthByTrack = mutableMapOf<Int, PreviousMouth>()
    @Volatile var lastDiagnostic = "not-run"
        private set

    fun process(bitmap: Bitmap, trackedFaces: List<TrackedFace>, atMs: Long): Int {
        if (trackedFaces.isEmpty()) {
            previousMouthByTrack.clear()
            attributor.clear()
            lastDiagnostic = "landmarks=0,blendshapes=0,matched=0"
            return 0
        }
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return 0
        val ownsInput = input !== bitmap
        val fallbackSource = input.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Could not copy frame for per-face landmark analysis")
        val inputWidth = input.width
        val inputHeight = input.height
        try {
            val currentTrackIds = trackedFaces.map { it.id }.toSet()
            (previousMouthByTrack.keys - currentTrackIds).forEach(attributor::removeTrackSamples)
            previousMouthByTrack.keys.retainAll(currentTrackIds)
            val result = BitmapImageBuilder(input).build().use { mpImage -> landmarker.detect(mpImage) }
            val landmarksByFace = result.faceLandmarks()
            val blendshapes = result.faceBlendshapes().orElse(emptyList())
            val matchedTrackIds = mutableSetOf<Int>()
            val usedTrackIds = mutableSetOf<Int>()

            var jawShapeCount = 0
            landmarksByFace.forEachIndexed { faceIndex, landmarks ->
                val mouthShapes = blendshapes.getOrNull(faceIndex)
                    ?.associate { it.categoryName() to it.score() }
                    ?.takeIf { it.containsKey(JAW_OPEN) } ?: return@forEachIndexed
                jawShapeCount++
                val faceBounds = boundsOf(landmarks, inputWidth, inputHeight) ?: return@forEachIndexed
                val trackId = MouthTrackMatcher.selectTrack(
                    trackedFaces.asSequence()
                    .filter { it.id !in usedTrackIds }
                    .map { it.id to overlap(faceBounds, it.bounds) }
                    .toList(),
                    MIN_FACE_OVERLAP,
                    MIN_FACE_OVERLAP_MARGIN
                ) ?: return@forEachIndexed
                val match = trackedFaces.firstOrNull { it.id == trackId } ?: return@forEachIndexed

                updateTrackMouth(match.id, mouthShapes, atMs)
                matchedTrackIds += match.id
                usedTrackIds += match.id
            }

            var cropLandmarkCount = 0
            trackedFaces.filter { it.id !in matchedTrackIds }.forEach { track ->
                val crop = squareFaceCrop(fallbackSource, track.bounds) ?: return@forEach
                try {
                    val cropResult = BitmapImageBuilder(crop).build().use { landmarker.detect(it) }
                    val cropShapes = cropResult.faceBlendshapes().orElse(emptyList()).firstOrNull()
                        ?.associate { it.categoryName() to it.score() }
                        ?.takeIf { it.containsKey(JAW_OPEN) } ?: return@forEach
                    cropLandmarkCount++
                    jawShapeCount++
                    updateTrackMouth(track.id, cropShapes, atMs)
                    matchedTrackIds += track.id
                } finally {
                    crop.recycle()
                }
            }

            (currentTrackIds - matchedTrackIds).forEach { trackId ->
                val previous = previousMouthByTrack[trackId]
                if (previous != null && atMs - previous.atMs > MAX_SAMPLE_GAP_MS) {
                    previousMouthByTrack.remove(trackId)
                    attributor.removeTrackSamples(trackId)
                }
            }
            lastDiagnostic = "landmarks=${landmarksByFace.size},crop=$cropLandmarkCount,blendshapes=${blendshapes.size},jaw=$jawShapeCount,matched=${matchedTrackIds.size}"
            return matchedTrackIds.size
        } finally {
            if (!fallbackSource.isRecycled) fallbackSource.recycle()
            if (ownsInput && !input.isRecycled) input.recycle()
        }
    }

    override fun close() {
        if (landmarkerDelegate.isInitialized()) landmarker.close()
        previousMouthByTrack.clear()
    }

    private fun squareFaceCrop(bitmap: Bitmap, bounds: RectF): Bitmap? {
        val side = (max(bounds.width(), bounds.height()) * CROP_SCALE).toInt().coerceAtLeast(2)
        val left = (bounds.centerX() - side / 2f).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bounds.centerY() - side / 2f).toInt().coerceIn(0, bitmap.height - 1)
        val right = (left + side).coerceAtMost(bitmap.width)
        val bottom = (top + side).coerceAtMost(bitmap.height)
        if (right - left < 2 || bottom - top < 2) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val enlarged = Bitmap.createScaledBitmap(crop, CROP_INPUT_SIZE, CROP_INPUT_SIZE, true)
        if (enlarged !== crop) crop.recycle()
        return enlarged
    }

    private fun updateTrackMouth(trackId: Int, shapes: Map<String, Float>, atMs: Long) {
        val previous = previousMouthByTrack[trackId]
        val elapsed = previous?.let { atMs - it.atMs } ?: 0L
        if (previous != null && elapsed in MIN_SAMPLE_GAP_MS..MAX_SAMPLE_GAP_MS) {
            attributor.addSample(MouthSample(trackId, atMs, MouthMotionScore.activity(previous.shapes, shapes)))
        } else if (previous != null && elapsed > MAX_SAMPLE_GAP_MS) {
            attributor.removeTrackSamples(trackId)
        }
        previousMouthByTrack[trackId] = PreviousMouth(shapes, atMs)
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
        private const val MIN_FACE_OVERLAP_MARGIN = 0.08f
        private const val MIN_SAMPLE_GAP_MS = 80L
        private const val MAX_SAMPLE_GAP_MS = 1_000L
        private const val CROP_SCALE = 2.0f
        private const val CROP_INPUT_SIZE = 224
    }
}
