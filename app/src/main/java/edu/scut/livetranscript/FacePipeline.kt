package edu.scut.livetranscript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import java.io.FileOutputStream

data class FaceSample(val bounds: RectF, val embedding: FloatArray)

class FacePipeline(private val context: Context) {
    private val detector by lazy {
        FaceDetectorYN.create(model("face_detection_yunet_2023mar.onnx").absolutePath, "", Size(320.0, 320.0), 0.72f, 0.3f, 50)
    }
    private val recognizer by lazy {
        FaceRecognizerSF.create(model("face_recognition_sface_2021dec.onnx").absolutePath, "")
    }

    fun detect(bitmap: Bitmap): List<FaceSample> {
        val rgba = Mat()
        val bgr = Mat()
        val faces = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            detector.setInputSize(Size(bgr.cols().toDouble(), bgr.rows().toDouble()))
            detector.detect(bgr, faces)
            return (0 until faces.rows()).mapNotNull { index ->
                val row = faces.row(index)
                val values = FloatArray(faces.cols())
                val aligned = Mat()
                val feature = Mat()
                try {
                    row.get(0, 0, values)
                    recognizer.alignCrop(bgr, row, aligned)
                    recognizer.feature(aligned, feature)
                    val embedding = FloatArray(feature.total().toInt())
                    feature.get(0, 0, embedding)
                    FaceSample(
                        RectF(values[0], values[1], values[0] + values[2], values[1] + values[3]),
                        embedding
                    )
                } catch (_: Exception) {
                    null
                } finally {
                    row.release()
                    aligned.release()
                    feature.release()
                }
            }
        } finally {
            rgba.release()
            bgr.release()
            faces.release()
        }
    }

    private fun model(name: String): File {
        val file = File(context.filesDir, name)
        if (!file.exists()) context.assets.open("models/$name").use { input ->
            FileOutputStream(file).use(input::copyTo)
        }
        return file
    }

    companion object {
        const val KNOWN_THRESHOLD = 0.363
        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return -1.0
            var dot = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                aa += a[i] * a[i]
                bb += b[i] * b[i]
            }
            return if (aa == 0.0 || bb == 0.0) 0.0 else dot / kotlin.math.sqrt(aa * bb)
        }
    }
}
