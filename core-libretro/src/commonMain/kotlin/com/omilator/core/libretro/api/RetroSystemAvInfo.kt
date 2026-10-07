package com.omilator.core.libretro.api

/**
 * retro_system_av_info parsing shared by the mobile controllers (Android
 * JNI + iOS cinterop), so SET_SYSTEM_AV_INFO forwarding has one offset
 * table instead of hand-rolled per-platform copies. The layout matches
 * desktop's LibretroLayouts.systemAvInfo (pinned there by
 * SetSystemAvInfoTest against the vendored libretro.h):
 *
 *   struct retro_game_geometry — base_width u32 @0, base_height u32 @4,
 *     max_width u32 @8, max_height u32 @12, aspect_ratio f32 @16, pad @20
 *   (geometry padded to 24 so the timing doubles stay 8-byte aligned)
 *   struct retro_system_timing — fps f64 @24, sample_rate f64 @32
 *
 * Cores may leave aspect_ratio unspecified (0.0) and some report 0 fps /
 * sample rate before content settles, so unset fields fall back to the
 * same sane defaults the platform controllers already used.
 */
object RetroSystemAvInfo {

    fun parse(
        base: Long,
        readInt: (Long) -> Int,
        readFloat: (Long) -> Float,
        readDouble: (Long) -> Double,
    ): AvInfo {
        val baseWidth = readInt(base).toUInt()
        val baseHeight = readInt(base + 4).toUInt()
        val maxWidth = readInt(base + 8).toUInt()
        val maxHeight = readInt(base + 12).toUInt()
        val aspect = readFloat(base + 16)
        val fps = readDouble(base + 24)
        val sampleRate = readDouble(base + 32)
        return AvInfo(
            Geometry(
                baseWidth, baseHeight, maxWidth, maxHeight,
                if (aspect > 0f) aspect else 1.5f,
            ),
            Timing(
                if (fps > 0.0) fps.toFloat() else 60f,
                if (sampleRate > 0.0) sampleRate else 48000.0,
            ),
        )
    }
}
