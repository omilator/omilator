package com.omilator.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Destination rectangle for the emulated surface. */
internal data class ViewportRect(val offset: Offset, val size: Size)

/**
 * Pure viewport calculation for the desktop player surface.
 *
 * - Aspect (0): fit the core's reported DISPLAY aspect ratio inside the
 *   canvas. Scaling by bitmap pixels alone displayed e.g. SNES content at
 *   its 8:7 pixel ratio instead of the 4:3 the core reports.
 * - Stretch (1): destination fills the canvas with independent X/Y scales.
 *   (maxOf(scaleX, scaleY) here was mislabeled "stretch" — it cropped.)
 * - Integer (2): whole-number multiples of the source bitmap, centered —
 *   keeps the bitmap's own pixel grid crisp; the display ratio does not
 *   apply because fractional sizes are not allowed in this mode.
 *
 * A non-positive/NaN displayAspectRatio falls back to the bitmap's pixel
 * aspect so a core reporting garbage geometry still renders centered.
 */
internal fun computeViewport(
    canvasWidth: Float,
    canvasHeight: Float,
    bitmapWidth: Int,
    bitmapHeight: Int,
    displayAspectRatio: Float,
    scaleMode: Int,
): ViewportRect {
    if (canvasWidth <= 0f || canvasHeight <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return ViewportRect(Offset.Zero, Size(canvasWidth.coerceAtLeast(0f), canvasHeight.coerceAtLeast(0f)))
    }
    return when (scaleMode) {
        1 -> ViewportRect(Offset.Zero, Size(canvasWidth, canvasHeight))

        2 -> {
            val n = minOf(canvasWidth / bitmapWidth, canvasHeight / bitmapHeight)
                .toInt().coerceAtLeast(1)
            val w = bitmapWidth * n.toFloat()
            val h = bitmapHeight * n.toFloat()
            ViewportRect(
                Offset((canvasWidth - w) / 2f, (canvasHeight - h) / 2f),
                Size(w, h),
            )
        }

        else -> {
            val ar = if (displayAspectRatio > 0f && !displayAspectRatio.isNaN()) {
                displayAspectRatio
            } else {
                bitmapWidth.toFloat() / bitmapHeight.toFloat()
            }
            var w = canvasWidth
            var h = w / ar
            if (h > canvasHeight) {
                h = canvasHeight
                w = h * ar
            }
            ViewportRect(
                Offset((canvasWidth - w) / 2f, (canvasHeight - h) / 2f),
                Size(w, h),
            )
        }
    }
}
