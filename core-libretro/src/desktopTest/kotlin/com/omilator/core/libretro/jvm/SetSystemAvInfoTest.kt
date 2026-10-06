package com.omilator.core.libretro.jvm

import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SET_SYSTEM_AV_INFO handling: the env command was previously only in the
 * silent-decline list, so a core switching display mode mid-run (N64
 * 240p↔480i, PS1 interlace toggles) kept rendering at the new frame size
 * while the desktop aspect-fit rectangle stayed at the load-time ratio.
 * The handler must parse the retro_system_av_info the core hands over and
 * notify the frontend.
 */
class SetSystemAvInfoTest {

    @Test
    fun handlerParsesGeometryAndTimingAndNotifies() {
        Arena.ofConfined().use { arena ->
            val ffm = LibretroFfm(arena, "/tmp/omilator-test-system")
            val received = mutableListOf<AvInfo>()
            ffm.onSystemAvInfo = { received.add(it) }

            val seg = arena.allocate(LibretroLayouts.systemAvInfo)
            fun vh(vararg path: String) = LibretroLayouts.systemAvInfo.varHandle(
                *path.map { MemoryLayout.PathElement.groupElement(it) }.toTypedArray(),
            )
            vh("geometry", "base_width").set(seg, 640)
            vh("geometry", "base_height").set(seg, 480)
            vh("geometry", "max_width").set(seg, 1024)
            vh("geometry", "max_height").set(seg, 768)
            vh("geometry", "aspect_ratio").set(seg, 16f / 9f)
            vh("timing", "fps").set(seg, 50.0)
            vh("timing", "sample_rate").set(seg, 44100.0)

            val handled = ffm.onEnvironment(RetroEnv.SET_SYSTEM_AV_INFO, seg)

            assertTrue(handled, "accepted mode change must return true per the env contract")
            assertEquals(1, received.size)
            val av = received.first()
            assertEquals(640, av.baseWidth)
            assertEquals(480, av.baseHeight)
            assertEquals(1024, av.maxWidth)
            assertEquals(768, av.maxHeight)
            assertEquals(16f / 9f, av.aspectRatio)
            assertEquals(50.0, av.fps)
            assertEquals(44100.0, av.sampleRate)
        }
    }

    @Test
    fun nullDataSegmentIsDeclinedSafely() {
        Arena.ofConfined().use { arena ->
            val ffm = LibretroFfm(arena, "/tmp/omilator-test-system")
            var notified = 0
            ffm.onSystemAvInfo = { notified++ }
            // A NULL pointer must not crash the upcall; there is nothing to
            // parse, so nothing is notified either.
            val handled = ffm.onEnvironment(RetroEnv.SET_SYSTEM_AV_INFO, MemorySegment.NULL)
            assertTrue(handled)
            assertEquals(0, notified)
        }
    }
}
