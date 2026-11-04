package edu.scut.livetranscript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Base64
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import java.io.FileOutputStream

data class FaceSample(val bounds: RectF, val embedding: FloatArray)
data class AlignedFace(val bounds: RectF, val image: String)
data class LocalStages(val samples: List<FaceSample>, val aligned: List<AlignedFace>, val detectMs: Double, val alignMs: Double, val featureMs: Double)

class FacePipeline(private val context: Context) {
    private val detector by lazy { FaceDetectorYN.create(model("face_detection_yunet_2023mar.onnx").absolutePath, "", Size(320.0, 320.0), 0.72f, 0.3f, 50) }
    private val recognizer by lazy { FaceRecognizerSF.create(model("face_recognition_sface_2021dec.onnx").absolutePath, "") }

    fun process(bitmap: Bitmap, offloadFeatures: Boolean): LocalStages {
        val rgba = Mat(); val bgr = Mat(); val faces = Mat()
        val samples = mutableListOf<FaceSample>(); val alignedFaces = mutableListOf<AlignedFace>()
        var alignMs = 0.0; var featureMs = 0.0
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val start = SystemClock.elapsedRealtimeNanos()
            detector.setInputSize(Size(bgr.cols().toDouble(), bgr.rows().toDouble()))
            detector.detect(bgr, faces)
            val detectMs = elapsed(start)
            for (i in 0 until faces.rows()) {
                val row = faces.row(i); val aligned = Mat(); val feature = Mat()
                try {
                    val values = FloatArray(faces.cols()); row.get(0, 0, values)
                    val bounds = RectF(values[0], values[1], values[0] + values[2], values[1] + values[3])
                    var tick = SystemClock.elapsedRealtimeNanos()
                    recognizer.alignCrop(bgr, row, aligned)
                    alignMs += elapsed(tick)
                    tick = SystemClock.elapsedRealtimeNanos()
                    if (offloadFeatures) {
                        val encoded = MatOfByte()
                        try {
                            Imgcodecs.imencode(".png", aligned, encoded)
                            alignedFaces.add(AlignedFace(bounds, Base64.encodeToString(encoded.toArray(), Base64.NO_WRAP)))
                        } finally { encoded.release() }
                    } else {
                        recognizer.feature(aligned, feature)
                        val embedding = FloatArray(feature.total().toInt()); feature.get(0, 0, embedding)
                        samples.add(FaceSample(bounds, embedding))
                    }
                    featureMs += elapsed(tick)
                } finally { row.release(); aligned.release(); feature.release() }
            }
            return LocalStages(samples, alignedFaces, detectMs, alignMs, featureMs)
        } finally { rgba.release(); bgr.release(); faces.release() }
    }

    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    private fun model(name: String): File {
        val file = File(context.filesDir, name)
        if (!file.exists()) context.assets.open("models/$name").use { input -> FileOutputStream(file).use(input::copyTo) }
        return file
    }

    companion object {
        const val KNOWN_THRESHOLD = 0.363
        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return -1.0
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
            return if (aa == 0.0 || bb == 0.0) 0.0 else dot / kotlin.math.sqrt(aa * bb)
        }
    }
}
