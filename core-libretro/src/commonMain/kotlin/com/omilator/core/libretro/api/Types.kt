package com.omilator.core.libretro.api

import kotlinx.serialization.Serializable

@Serializable
data class SystemInfo(
    val libraryName: String,
    val libraryVersion: String,
    val validExtensions: List<String>,
    val needFullpath: Boolean,
    val blockExtract: Boolean,
) {
    companion object
}

@Serializable
data class AvInfo(
    val geometry: Geometry,
    val timing: Timing,
)

@Serializable
data class Geometry(
    val baseWidth: UInt,
    val baseHeight: UInt,
    val maxWidth: UInt,
    val maxHeight: UInt,
    val aspectRatio: Float,
)

@Serializable
data class Timing(
    val fps: Float,
    val sampleRate: Double,
)

enum class PixelFormat(val retroId: UInt) {
    /** libretro's default (0): 16-bit 0RGB1555. */
    ORGB1555(0u),
    XRGB8888(1u),
    RGB565(2u),
    ;

    val bytesPerPixel: Int
        get() = when (this) {
            ORGB1555 -> 2
            XRGB8888 -> 4
            RGB565 -> 2
        }
}

enum class InputDevice(val retroId: UInt) {
    NONE(0u),
    JOYPAD(1u),
    MOUSE(2u),
    KEYBOARD(3u),
    LIGHTGUN(4u),
    ANALOG(5u),
    POINTER(6u),
}

object JoypadButton {
    const val B = 0
    const val Y = 1
    const val SELECT = 2
    const val START = 3
    const val DPAD_UP = 4
    const val DPAD_DOWN = 5
    const val DPAD_LEFT = 6
    const val DPAD_RIGHT = 7
    const val A = 8
    const val X = 9
    const val L = 10
    const val R = 11
    const val L2 = 12
    const val R2 = 13
    const val L3 = 14
    const val R3 = 15
}

object AnalogIndex {
    const val LEFT_X = 0
    const val LEFT_Y = 1
    const val RIGHT_X = 2
    const val RIGHT_Y = 3
}
