package com.omilator.core.libretro.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * retro_system_av_info offsets used by the mobile SET_SYSTEM_AV_INFO
 * forwarding (pass C finding 3). The desktop FFM path pins the same
 * layout against the vendored libretro.h (SetSystemAvInfoTest); this
 * pins the shared mobile parser that both JniCoreController (JNI pointer
 * readers) and NativeCoreController (cinterop) consume.
 */
class RetroSystemAvInfoTest {

    private class FakeStruct {
        val ints = HashMap<Long, Int>()
        val floats = HashMap<Long, Float>()
        val doubles = HashMap<Long, Double>()
    }

    private fun parseWith(f: FakeStruct) = RetroSystemAvInfo.parse(
        base = 1000L,
        readInt = { f.ints[it] ?: 0 },
        readFloat = { f.floats[it] ?: 0f },
        readDouble = { f.doubles[it] ?: 0.0 },
    )

    @Test
    fun parsesGeometryAndTimingAtTheDocumentedOffsets() {
        val f = FakeStruct()
        f.ints[1000L] = 640          // base_width @0
        f.ints[1004L] = 480          // base_height @4
        f.ints[1008L] = 1024         // max_width @8
        f.ints[1012L] = 768          // max_height @12
        f.floats[1016L] = 16f / 9f   // aspect_ratio @16
        f.doubles[1024L] = 50.0      // fps @24
        f.doubles[1032L] = 44100.0   // sample_rate @32

        val info = parseWith(f)

        assertEquals(640u, info.geometry.baseWidth)
        assertEquals(480u, info.geometry.baseHeight)
        assertEquals(1024u, info.geometry.maxWidth)
        assertEquals(768u, info.geometry.maxHeight)
        assertEquals(16f / 9f, info.geometry.aspectRatio)
        assertEquals(50f, info.timing.fps)
        assertEquals(44100.0, info.timing.sampleRate)
    }

    @Test
    fun unsetOptionalFieldsFallBackToSaneDefaults() {
        val info = parseWith(FakeStruct())
        // aspect_ratio 0 → the frontend default; fps/rate 0 → NTSC/CD
        // defaults the platform controllers always used.
        assertEquals(1.5f, info.geometry.aspectRatio)
        assertEquals(60f, info.timing.fps)
        assertEquals(48000.0, info.timing.sampleRate)
    }

    @Test
    fun palSwitchCarriesBothTimingHalves() {
        val f = FakeStruct()
        f.doubles[1024L] = 50.0
        f.doubles[1032L] = 44100.0
        val info = parseWith(f)
        assertEquals(50f, info.timing.fps)
        assertEquals(44100.0, info.timing.sampleRate)
    }
}
