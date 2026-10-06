package com.omilator.ui.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Destination-rect math for the desktop player. Aspect mode must honor
 * the core's reported DISPLAY aspect ratio (not the bitmap's pixel
 * ratio), stretch must fill the canvas on both axes (the old maxOf-scale
 * "stretch" cropped), and integer mode must scale by whole bitmap
 * multiples.
 */
class ViewportMathTest {

    private fun assertFuzzy(expected: Float, actual: Float, label: String) {
        assertTrue(abs(expected - actual) < 0.01f, "$label: expected $expected, got $actual")
    }

    @Test
    fun aspectModeUsesDisplayAspectRatioNotPixelRatio() {
        // SNES-class content: 256x224 bitmap (8:7 pixels), core reports 4:3.
        val dst = computeViewport(
            canvasWidth = 1600f, canvasHeight = 900f,
            bitmapWidth = 256, bitmapHeight = 224,
            displayAspectRatio = 4f / 3f,
            scaleMode = 0,
        )
        // Canvas is wider than 4:3 → height-bound: 900 tall, 1200 wide.
        assertFuzzy(1200f, dst.size.width, "width")
        assertFuzzy(900f, dst.size.height, "height")
        assertFuzzy(200f, dst.offset.x, "x centering")
        assertFuzzy(0f, dst.offset.y, "y centering")
    }

    @Test
    fun aspectModeIsWidthBoundInPortraitCanvas() {
        val dst = computeViewport(
            canvasWidth = 800f, canvasHeight = 1000f,
            bitmapWidth = 256, bitmapHeight = 224,
            displayAspectRatio = 4f / 3f,
            scaleMode = 0,
        )
        assertFuzzy(800f, dst.size.width, "width")
        assertFuzzy(600f, dst.size.height, "height")
        assertFuzzy(0f, dst.offset.x, "x")
        assertFuzzy(200f, dst.offset.y, "y")
    }

    @Test
    fun stretchModeFillsCanvasOnBothAxes() {
        // maxOf(scaleX, scaleY) cropped one axis; stretch means independent
        // scales filling every pixel.
        val dst = computeViewport(
            canvasWidth = 1600f, canvasHeight = 900f,
            bitmapWidth = 256, bitmapHeight = 224,
            displayAspectRatio = 4f / 3f,
            scaleMode = 1,
        )
        assertFuzzy(1600f, dst.size.width, "width")
        assertFuzzy(900f, dst.size.height, "height")
        assertFuzzy(0f, dst.offset.x, "x")
        assertFuzzy(0f, dst.offset.y, "y")
    }

    @Test
    fun integerModeUsesWholeBitmapMultiples() {
        val dst = computeViewport(
            canvasWidth = 700f, canvasHeight = 500f,
            bitmapWidth = 256, bitmapHeight = 224,
            displayAspectRatio = 4f / 3f,
            scaleMode = 2,
        )
        // min(700/256, 500/224) = 2.23 → n = 2 → 512x448 centered.
        assertFuzzy(512f, dst.size.width, "width")
        assertFuzzy(448f, dst.size.height, "height")
        assertFuzzy(94f, dst.offset.x, "x")
        assertFuzzy(26f, dst.offset.y, "y")
    }

    @Test
    fun integerModeNeverScalesBelowOne() {
        val dst = computeViewport(
            canvasWidth = 100f, canvasHeight = 100f,
            bitmapWidth = 256, bitmapHeight = 224,
            displayAspectRatio = 4f / 3f,
            scaleMode = 2,
        )
        assertFuzzy(256f, dst.size.width, "width")
        assertFuzzy(224f, dst.size.height, "height")
    }

    @Test
    fun garbageDisplayRatioFallsBackToBitmapAspectRatio() {
        val nan = Float.NaN
        for (bad in listOf(0f, -1f, nan)) {
            val dst = computeViewport(
                canvasWidth = 1600f, canvasHeight = 700f,
                bitmapWidth = 256, bitmapHeight = 224,
                displayAspectRatio = bad,
                scaleMode = 0,
            )
            // 8:7 ratio → 800x700 fits, centered.
            assertFuzzy(800f, dst.size.width, "width for ratio $bad")
            assertFuzzy(700f, dst.size.height, "height for ratio $bad")
        }
    }

    @Test
    fun degenerateInputsDoNotThrow() {
        val dst = computeViewport(0f, 0f, 256, 224, 4f / 3f, 0)
        assertEquals(0f, dst.size.width)
        assertEquals(0f, dst.size.height)
    }
}
