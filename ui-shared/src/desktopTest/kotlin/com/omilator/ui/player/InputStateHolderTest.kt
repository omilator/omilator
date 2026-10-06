package com.omilator.ui.player

import com.omilator.core.libretro.api.JoypadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keyboard/gamepad merge semantics. The single-array model let the
 * gamepad poller (which writes every mapped button each frame — false for
 * a neutral pad, all-false for no pad at all) erase keyboard-held
 * buttons on the core thread before the core could read them.
 */
class InputStateHolderTest {

    @Test
    fun keyboardHoldSurvivesNeutralGamepadPoll() {
        val h = InputStateHolder()
        h.press(JoypadButton.A)
        // A connected-but-neutral pad reports false for every button.
        for (b in 0 until InputStateHolder.BUTTON_COUNT) {
            h.setGamepadButton(b, false)
        }
        assertEquals(1, h.get(JoypadButton.A))
    }

    @Test
    fun keyboardHoldSurvivesNoGamepadConnected() {
        val h = InputStateHolder()
        h.press(JoypadButton.START)
        // No pad found: the poller clears pad state only.
        h.clearGamepad()
        assertEquals(1, h.get(JoypadButton.START))
    }

    @Test
    fun padHoldSurvivesKeyboardRelease() {
        val h = InputStateHolder()
        h.press(JoypadButton.B)
        h.setGamepadButton(JoypadButton.B, true)
        h.release(JoypadButton.B)
        assertEquals(1, h.get(JoypadButton.B))
        // And once the pad releases too, the button is finally up.
        h.setGamepadButton(JoypadButton.B, false)
        assertEquals(0, h.get(JoypadButton.B))
    }

    @Test
    fun disconnectClearsOnlyGamepadStateAndAxes() {
        val h = InputStateHolder()
        h.press(JoypadButton.DPAD_UP)
        h.setGamepadButton(JoypadButton.X, true)
        h.setAnalog(0, 12345)
        h.clearGamepad()
        assertEquals(1, h.get(JoypadButton.DPAD_UP), "keyboard Up must survive pad disconnect")
        assertEquals(0, h.get(JoypadButton.X), "pad-held X must clear on disconnect")
        assertEquals(0, h.analog(0), "axes must recentre on disconnect")
    }

    @Test
    fun invalidIdsAreIgnored() {
        val h = InputStateHolder()
        h.press(-1)
        h.press(InputStateHolder.BUTTON_COUNT)
        h.setGamepadButton(99, true)
        h.setAnalog(-2, 100)
        for (b in 0 until InputStateHolder.BUTTON_COUNT) {
            assertEquals(0, h.get(b))
        }
        assertEquals(0, h.get(-1))
        assertEquals(0, h.get(InputStateHolder.BUTTON_COUNT))
        assertEquals(0, h.analog(-2))
        assertEquals(0, h.analog(InputStateHolder.ANALOG_COUNT))
    }

    @Test
    fun analogValuesAreClampedToInt16() {
        val h = InputStateHolder()
        h.setAnalog(0, 999_999)
        h.setAnalog(1, -999_999)
        h.setAnalog(2, 42)
        assertEquals(32767, h.analog(0))
        assertEquals(-32768, h.analog(1))
        assertEquals(42, h.analog(2))
    }

    @Test
    fun everyJoypadButtonIndexFitsTheMask() {
        // The poller maps GLFW buttons to these ids; an id outside the
        // array would be silently dropped.
        val ids = listOf(
            JoypadButton.B, JoypadButton.Y, JoypadButton.SELECT, JoypadButton.START,
            JoypadButton.DPAD_UP, JoypadButton.DPAD_DOWN, JoypadButton.DPAD_LEFT,
            JoypadButton.DPAD_RIGHT, JoypadButton.A, JoypadButton.X, JoypadButton.L,
            JoypadButton.R, JoypadButton.L2, JoypadButton.R2, JoypadButton.L3,
            JoypadButton.R3,
        )
        val h = InputStateHolder()
        for (id in ids) {
            h.press(id)
            assertTrue(h.get(id) == 1, "button $id does not fit the mask")
            h.release(id)
            assertFalse(h.get(id) == 1)
        }
    }
}
