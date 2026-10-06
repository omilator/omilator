package com.omilator.ui.player

import com.omilator.core.libretro.api.InputDevice
import kotlinx.atomicfu.atomic
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Touch input source semantics: the old inline lambda answered every poll
 * by id alone, so RETRO_DEVICE_ANALOG queries (where id is the axis) read
 * the digital B/Y touch buttons (ids 0/1) as the stick position.
 */
class TouchInputSourceTest {

    private val bits = List(16) { atomic(0) }
    private val source = TouchInputSource(bits)

    @Test
    fun joypadPressedButtonIsReported() {
        bits[0].value = 1 // B
        bits[8].value = 1 // A
        assertEquals(1, source.poll(0, InputDevice.JOYPAD, 0, 0))
        assertEquals(1, source.poll(0, InputDevice.JOYPAD, 0, 8))
        assertEquals(0, source.poll(0, InputDevice.JOYPAD, 0, 9))
    }

    @Test
    fun analogQueriesAreNeutralEvenWhileButtonsAreHeld() {
        bits[0].value = 1 // B held
        bits[1].value = 1 // Y held
        // Analog: index = stick, id = axis (0=X, 1=Y).
        assertEquals(0, source.poll(0, InputDevice.ANALOG, 0, 0), "analog X must not read the B button bit")
        assertEquals(0, source.poll(0, InputDevice.ANALOG, 0, 1), "analog Y must not read the Y button bit")
        assertEquals(0, source.poll(0, InputDevice.ANALOG, 1, 0))
    }

    @Test
    fun otherPortsAndDevicesAreNeutral() {
        bits[8].value = 1
        assertEquals(0, source.poll(1, InputDevice.JOYPAD, 0, 8), "port 1 has no touch controller")
        assertEquals(0, source.poll(0, InputDevice.MOUSE, 0, 8))
        assertEquals(0, source.poll(0, InputDevice.KEYBOARD, 0, 8))
        assertEquals(0, source.poll(0, InputDevice.NONE, 0, 0))
    }

    @Test
    fun outOfRangeIdIsNeutral() {
        assertEquals(0, source.poll(0, InputDevice.JOYPAD, 0, 16))
        assertEquals(0, source.poll(0, InputDevice.JOYPAD, 0, -1))
    }
}
