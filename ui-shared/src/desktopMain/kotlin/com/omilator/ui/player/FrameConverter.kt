package com.omilator.ui.player

import com.omilator.core.libretro.api.Framebuffer
import com.omilator.core.libretro.api.PixelFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

internal class FrameConverter {
    private var argb: IntArray = IntArray(0)
    private var image: BufferedImage? = null
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0

    fun convert(framebuffer: Framebuffer): BufferedImage {
        val width = framebuffer.width.toInt()
        val height = framebuffer.height.toInt()
        val pitch = framebuffer.pitch.toInt()
        val bytes = framebuffer.data as? ByteArray ?: return ensureImage(width, height)

        ensureBuffer(width, height)

        when (framebuffer.format) {
            PixelFormat.ORGB1555 -> convertORGB1555(bytes, width, height, pitch)
            PixelFormat.XRGB8888 -> convertXRGB8888(bytes, width, height, pitch)
            PixelFormat.RGB565 -> convertRGB565(bytes, width, height, pitch)
        }

        return image!!
    }

    private fun ensureBuffer(width: Int, height: Int) {
        val size = width * height
        if (argb.size != size) argb = IntArray(size)
        ensureImage(width, height)
    }

    private fun ensureImage(width: Int, height: Int): BufferedImage {
        if (image == null || cachedWidth != width || cachedHeight != height) {
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image = img
            cachedWidth = width
            cachedHeight = height
            val buffer = img.raster.dataBuffer as DataBufferInt
            argb = buffer.bankData[0]
        }
        return image!!
    }

    private fun convertXRGB8888(bytes: ByteArray, width: Int, height: Int, pitch: Int) {
        val bpp = 4
        for (y in 0 until height) {
            val rowOffset = y * pitch
            val destOffset = y * width
            for (x in 0 until width) {
                val src = rowOffset + x * bpp
                // XRGB8888 = bytes in BGRA order in memory on little-endian
                val b = bytes[src].toInt() and 0xFF
                val g = bytes[src + 1].toInt() and 0xFF
                val r = bytes[src + 2].toInt() and 0xFF
                argb[destOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun convertORGB1555(bytes: ByteArray, width: Int, height: Int, pitch: Int) {
        val bpp = 2
        for (y in 0 until height) {
            val rowOffset = y * pitch
            val destOffset = y * width
            for (x in 0 until width) {
                val src = rowOffset + x * bpp
                val lo = bytes[src].toInt() and 0xFF
                val hi = bytes[src + 1].toInt() and 0xFF
                val packed = lo or (hi shl 8)
                // 0RRRRRGG GGGBBBBB
                val r5 = (packed shr 10) and 0x1F
                val g5 = (packed shr 5) and 0x1F
                val b5 = packed and 0x1F
                val r = (r5 shl 3) or (r5 shr 2)
                val g = (g5 shl 3) or (g5 shr 2)
                val b = (b5 shl 3) or (b5 shr 2)
                argb[destOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun convertRGB565(bytes: ByteArray, width: Int, height: Int, pitch: Int) {
        val bpp = 2
        for (y in 0 until height) {
            val rowOffset = y * pitch
            val destOffset = y * width
            for (x in 0 until width) {
                val src = rowOffset + x * bpp
                val lo = bytes[src].toInt() and 0xFF
                val hi = bytes[src + 1].toInt() and 0xFF
                val packed = lo or (hi shl 8)
                val r5 = (packed shr 11) and 0x1F
                val g6 = (packed shr 5) and 0x3F
                val b5 = packed and 0x1F
                val r = (r5 shl 3) or (r5 shr 2)
                val g = (g6 shl 2) or (g6 shr 4)
                val b = (b5 shl 3) or (b5 shr 2)
                argb[destOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
