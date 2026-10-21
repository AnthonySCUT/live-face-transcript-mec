package edu.scut.livetranscript

import kotlin.math.min

internal data class ImageViewportTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    fun mapX(sourceX: Float) = offsetX + sourceX * scale
    fun mapY(sourceY: Float) = offsetY + sourceY * scale
    fun sourceX(viewX: Float) = (viewX - offsetX) / scale
    fun sourceY(viewY: Float) = (viewY - offsetY) / scale

    companion object {
        fun fitCenter(sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int): ImageViewportTransform {
            val safeSourceWidth = sourceWidth.coerceAtLeast(1)
            val safeSourceHeight = sourceHeight.coerceAtLeast(1)
            val safeViewWidth = viewWidth.coerceAtLeast(1)
            val safeViewHeight = viewHeight.coerceAtLeast(1)
            val scale = min(
                safeViewWidth.toFloat() / safeSourceWidth,
                safeViewHeight.toFloat() / safeSourceHeight
            )
            return ImageViewportTransform(
                scale = scale,
                offsetX = (safeViewWidth - safeSourceWidth * scale) / 2f,
                offsetY = (safeViewHeight - safeSourceHeight * scale) / 2f
            )
        }
    }
}
