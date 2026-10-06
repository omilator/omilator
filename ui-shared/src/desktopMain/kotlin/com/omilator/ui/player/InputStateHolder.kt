package com.omilator.ui.player

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Joypad state for one player port, merged from two independent sources
 * (keyboard and gamepad) at read time.
 *
 * The previous single-array model had both sources write the same ints:
 * gamepad polling writes every mapped button each frame — false when the
 * pad is neutral, and all-false when no pad is connected — so a keyboard
 * key held through `runFrame` was released on the core thread before the
 * core could read it. Per-source masks keep a neutral/absent gamepad from
 * erasing keyboard state, while a gamepad disconnect can still clear only
 * what the gamepad held.
 *
 * The atomic arrays also fix the UI-thread/core-thread visibility problem
 * the old plain `IntArray` had for digital input.
 */
internal class InputStateHolder {
    private val keyboard = AtomicIntegerArray(BUTTON_COUNT)
    private val gamepad = AtomicIntegerArray(BUTTON_COUNT)
    private val analogs = AtomicIntegerArray(ANALOG_COUNT)

    /** Keyboard source (UI thread). */
    fun press(button: Int) {
        if (button in 0 until BUTTON_COUNT) keyboard.set(button, 1)
    }

    fun release(button: Int) {
        if (button in 0 until BUTTON_COUNT) keyboard.set(button, 0)
    }

    /** Merged read (core thread): a button counts as held if either source holds it. */
    fun get(button: Int): Int =
        if (button in 0 until BUTTON_COUNT) maxOf(keyboard.get(button), gamepad.get(button)) else 0

    /** Gamepad source (core thread, once per frame). */
    fun setGamepadButton(button: Int, pressed: Boolean) {
        if (button in 0 until BUTTON_COUNT) gamepad.set(button, if (pressed) 1 else 0)
    }

    fun setAnalog(index: Int, value: Int) {
        if (index in 0 until ANALOG_COUNT) analogs.set(index, value.coerceIn(-32768, 32767))
    }

    fun analog(index: Int): Int = if (index in 0 until ANALOG_COUNT) analogs.get(index) else 0

    /** Gamepad vanished: only what the gamepad held stays stuck — keyboard
     *  state and nothing else is preserved, axes return to center. */
    fun clearGamepad() {
        for (i in 0 until BUTTON_COUNT) gamepad.set(i, 0)
        for (i in 0 until ANALOG_COUNT) analogs.set(i, 0)
    }

    companion object {
        const val BUTTON_COUNT = 16
        const val ANALOG_COUNT = 4
    }
}
