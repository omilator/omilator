package com.omilator.core.libretro.jvm

import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout

internal object LibretroLayouts {

    val cString: ValueLayout = ValueLayout.ADDRESS.withTargetLayout(ValueLayout.JAVA_BYTE)

    val systemInfo: MemoryLayout = MemoryLayout.structLayout(
        cString.withName("library_name"),
        cString.withName("library_version"),
        cString.withName("valid_extensions"),
        ValueLayout.JAVA_BYTE.withName("need_fullpath"),
        ValueLayout.JAVA_BYTE.withName("block_extract"),
        MemoryLayout.paddingLayout(6),
    )

    val gameInfo: MemoryLayout = MemoryLayout.structLayout(
        cString.withName("path"),
        ValueLayout.ADDRESS.withName("data"),
        ValueLayout.JAVA_LONG.withName("size"),
        cString.withName("meta"),
    )

    val gameGeometry: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("base_width"),
        ValueLayout.JAVA_INT.withName("base_height"),
        ValueLayout.JAVA_INT.withName("max_width"),
        ValueLayout.JAVA_INT.withName("max_height"),
        ValueLayout.JAVA_FLOAT.withName("aspect_ratio"),
    )

    val systemTiming: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_DOUBLE.withName("fps"),
        ValueLayout.JAVA_DOUBLE.withName("sample_rate"),
    )

    val systemAvInfo: MemoryLayout = MemoryLayout.structLayout(
        gameGeometry.withName("geometry"),
        MemoryLayout.paddingLayout(4),
        systemTiming.withName("timing"),
    )
}

internal object RetroEnv {
    // Values mirror libretro.h verbatim (see _reference/ludo/libretro/libretro.h).
    // The EXPERIMENTAL bit is part of the command the core sends, so flagged
    // commands must compare against N | 0x10000, not N.
    private const val EXPERIMENTAL = 0x10000

    const val GET_OVERSCAN = 2
    const val GET_CAN_DUPE = 3
    const val GET_SYSTEM_DIRECTORY = 9
    const val SET_PIXEL_FORMAT = 10
    const val SET_INPUT_DESCRIPTORS = 11
    const val SET_KEYBOARD_CALLBACK = 12
    const val SET_DISK_CONTROL_INTERFACE = 13
    const val SET_HW_RENDER = 14
    const val GET_VARIABLE = 15
    const val SET_VARIABLES = 16
    const val GET_VARIABLE_UPDATE = 17
    const val SET_SUPPORT_NO_GAME = 18
    const val GET_LIBRETRO_PATH = 19
    const val SET_FRAME_TIME_CALLBACK = 21
    const val SET_AUDIO_CALLBACK = 22
    const val GET_RUMBLE_INTERFACE = 23
    const val GET_LOG_INTERFACE = 27
    const val GET_PERF_INTERFACE = 28
    const val GET_SAVE_DIRECTORY = 31
    const val SET_SYSTEM_AV_INFO = 32
    const val SET_CONTROLLER_INFO = 35
    const val GET_USERNAME = 38
    const val GET_LANGUAGE = 39
    const val SET_SUPPORT_ACHIEVEMENTS = 42 or EXPERIMENTAL
    const val GET_AUDIO_VIDEO_ENABLE = 47 or EXPERIMENTAL
    const val GET_TARGET_REFRESH_RATE = 50 or EXPERIMENTAL
    const val GET_INPUT_BITMASKS = 51 or EXPERIMENTAL
    const val GET_CORE_OPTIONS_VERSION = 52
    const val SET_CORE_OPTIONS = 53
    const val SET_CORE_OPTIONS_INTL = 54
    const val SET_CORE_OPTIONS_DISPLAY = 55
    const val GET_PREFERRED_HW_RENDER = 56
    const val SET_DISK_CONTROL_EXT_INTERFACE = 58
}

internal object PixelFormatC {
    const val ORGB1555 = 0
    const val XRGB8888 = 1
    const val RGB565 = 2
}
