package com.omilator.core.input

import com.omilator.core.libretro.api.InputDevice
import com.omilator.core.libretro.api.InputSource
import com.omilator.core.libretro.api.JoypadButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class InputState {
    private val buttons = IntArray(16)
    private val analogs = IntArray(4)

    fun setButton(button: Int, pressed: Boolean) {
        if (button in buttons.indices) buttons[button] = if (pressed) 1 else 0
    }

    fun setAnalog(index: Int, value: Int) {
        if (index in analogs.indices) analogs[index] = value.coerceIn(-32768, 32767)
    }

    fun button(button: Int): Int = buttons.getOrElse(button) { 0 }
    fun analog(index: Int): Int = analogs.getOrElse(index) { 0 }

    fun reset() {
        buttons.fill(0)
        analogs.fill(0)
    }

    fun copy(): InputState {
        val next = InputState()
        buttons.copyInto(next.buttons)
        analogs.copyInto(next.analogs)
        return next
    }
}

class InputManager : InputSource {
    private val _state = MutableStateFlow(InputState())
    val state: StateFlow<InputState> = _state

    fun update(transform: InputState.() -> Unit) {
        // Mutate a copy, not the current value: assigning the same instance
        // back compares equal to itself and StateFlow suppresses the emission.
        val next = _state.value.copy()
        next.transform()
        _state.value = next
    }

    fun press(button: Int) = update { setButton(button, true) }
    fun release(button: Int) = update { setButton(button, false) }

    override fun poll(port: Int, device: InputDevice, index: Int, id: Int): Int {
        if (port != 0) return 0
        return when (device) {
            InputDevice.JOYPAD -> _state.value.button(id)
            InputDevice.ANALOG -> _state.value.analog(id)
            else -> 0
        }
    }
}
