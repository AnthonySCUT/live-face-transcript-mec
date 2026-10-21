package edu.scut.livetranscript

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class FaceOverlayView(context: Context) : View(context) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * resources.displayMetrics.density
        color = 0xFF46D5A6.toInt()
    }
    private val knownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * resources.displayMetrics.density
        color = 0xFFFFC05A.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 14f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9172321.toInt() }
    private var faces: List<TrackedFace> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    var selectedId: Int? = null
    var onFaceSelected: ((Int) -> Unit)? = null

    fun submit(faces: List<TrackedFace>, sourceWidth: Int, sourceHeight: Int) {
        this.faces = faces
        imageWidth = sourceWidth.coerceAtLeast(1)
        imageHeight = sourceHeight.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val transform = ImageViewportTransform.fitCenter(imageWidth, imageHeight, width, height)
        faces.forEach { face ->
            val rect = RectF(
                transform.mapX(face.bounds.left),
                transform.mapY(face.bounds.top),
                transform.mapX(face.bounds.right),
                transform.mapY(face.bounds.bottom)
            )
            val paint = if (face.known) knownPaint else boxPaint
            canvas.drawRoundRect(rect, 8f, 8f, paint)
            val title = if (face.id == selectedId) "${face.label} · 说话人" else face.label
            val textWidth = labelPaint.measureText(title)
            val chip = RectF(rect.left, (rect.top - 30f * resources.displayMetrics.density).coerceAtLeast(0f), rect.left + textWidth + 20f, rect.top)
            canvas.drawRoundRect(chip, 6f, 6f, chipPaint)
            canvas.drawText(title, chip.left + 10f, chip.bottom - 9f * resources.displayMetrics.density, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val transform = ImageViewportTransform.fitCenter(imageWidth, imageHeight, width, height)
        val x = transform.sourceX(event.x)
        val y = transform.sourceY(event.y)
        val hit = faces.minByOrNull { face ->
            val centerX = face.bounds.centerX()
            val centerY = face.bounds.centerY()
            (centerX - x) * (centerX - x) + (centerY - y) * (centerY - y)
        }?.takeIf { it.bounds.contains(x, y) }
        if (hit != null) onFaceSelected?.invoke(hit.id)
        return true
    }
}
