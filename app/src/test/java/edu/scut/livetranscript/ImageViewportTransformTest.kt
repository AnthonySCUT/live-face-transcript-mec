package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewportTransformTest {
    @Test
    fun fitCenterShowsTheWholeImageAndCentersLetterboxing() {
        val transform = ImageViewportTransform.fitCenter(640, 480, 960, 350)

        assertEquals(350f / 480f, transform.scale, 0.0001f)
        assertEquals((960f - 640f * transform.scale) / 2f, transform.offsetX, 0.0001f)
        assertEquals(0f, transform.offsetY, 0.0001f)
        assertEquals(transform.offsetX, transform.mapX(0f), 0.0001f)
        assertEquals(transform.offsetX + 640f * transform.scale, transform.mapX(640f), 0.0001f)
        assertEquals(480f * transform.scale, transform.mapY(480f), 0.0001f)
    }

    @Test
    fun viewCoordinatesMapBackToTheSameSourcePoint() {
        val transform = ImageViewportTransform.fitCenter(640, 480, 960, 350)
        val sourceX = 320f
        val sourceY = 240f

        assertEquals(sourceX, transform.sourceX(transform.mapX(sourceX)), 0.0001f)
        assertEquals(sourceY, transform.sourceY(transform.mapY(sourceY)), 0.0001f)
    }
}
