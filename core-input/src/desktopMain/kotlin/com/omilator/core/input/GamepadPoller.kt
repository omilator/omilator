package com.omilator.core.input

import com.omilator.core.libretro.api.JoypadButton
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWGamepadState
import org.lwjgl.system.Configuration
import kotlin.math.abs

class GamepadPoller {

    private var initialized = false
    private var available = false
    private val state = GLFWGamepadState.malloc()

    fun init(): Boolean {
        if (initialized) return available
        initialized = true
        try {
            Configuration.GLFW_CHECK_THREAD0.set(false)
            val ok = GLFW.glfwInit()
            if (ok) {
                for (jid in 0 until GLFW.GLFW_JOYSTICK_LAST) {
                    if (GLFW.glfwJoystickIsGamepad(jid)) {
                        println("[Gamepad] Controller detected at joystick $jid")
                    }
                }
                available = true
            }
        } catch (e: Throwable) {
            println("[Gamepad] Init error: ${e.message}")
        }
        return available
    }

    fun poll(
        setButton: (button: Int, pressed: Boolean) -> Unit,
        setAnalog: (index: Int, value: Int) -> Unit,
    ) {
        if (!available) return

        // GLFW_JOYSTICK_LAST is a valid id, so the range is inclusive — an
        // exclusive bound silently skipped the last joystick.
        for (jid in GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST) {
            if (!GLFW.glfwJoystickPresent(jid)) continue
            if (!GLFW.glfwJoystickIsGamepad(jid)) continue
            if (!GLFW.glfwGetGamepadState(jid, state)) continue

            setButton(JoypadButton.A, btn(GLFW.GLFW_GAMEPAD_BUTTON_A))
            setButton(JoypadButton.B, btn(GLFW.GLFW_GAMEPAD_BUTTON_B))
            setButton(JoypadButton.X, btn(GLFW.GLFW_GAMEPAD_BUTTON_X))
            setButton(JoypadButton.Y, btn(GLFW.GLFW_GAMEPAD_BUTTON_Y))
            setButton(JoypadButton.DPAD_UP, btn(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP))
            setButton(JoypadButton.DPAD_DOWN, btn(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN))
            setButton(JoypadButton.DPAD_LEFT, btn(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT))
            setButton(JoypadButton.DPAD_RIGHT, btn(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT))
            setButton(JoypadButton.START, btn(GLFW.GLFW_GAMEPAD_BUTTON_START))
            setButton(JoypadButton.SELECT, btn(GLFW.GLFW_GAMEPAD_BUTTON_BACK))
            setButton(JoypadButton.L, btn(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER))
            setButton(JoypadButton.R, btn(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER))
            setButton(JoypadButton.L2, axis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > 0.5f)
            setButton(JoypadButton.R2, axis(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0.5f)

            setAnalog(0, deadzoneScale(axis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X)))
            setAnalog(1, -deadzoneScale(axis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y)))
            setAnalog(2, deadzoneScale(axis(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X)))
            setAnalog(3, -deadzoneScale(axis(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y)))

            return
        }

        // No gamepad found: it may have just disconnected. Anything it held
        // stays pressed forever unless cleared here.
        for (b in listOf(
                JoypadButton.A, JoypadButton.B, JoypadButton.X, JoypadButton.Y,
                JoypadButton.DPAD_UP, JoypadButton.DPAD_DOWN,
                JoypadButton.DPAD_LEFT, JoypadButton.DPAD_RIGHT,
                JoypadButton.START, JoypadButton.SELECT,
                JoypadButton.L, JoypadButton.R, JoypadButton.L2, JoypadButton.R2,
            )
        ) {
            setButton(b, false)
        }
        for (i in 0 until 4) setAnalog(i, 0)
    }

    private fun btn(code: Int): Boolean = state.buttons(code) != 0.toByte()
    private fun axis(code: Int): Float = state.axes(code)

    private fun deadzoneScale(v: Float): Int {
        val dead = 0.15f
        val clamped = if (abs(v) < dead) 0f else v
        return (clamped * 32767f).toInt()
    }

    fun destroy() {
        if (initialized && available) {
            try { GLFW.glfwTerminate() } catch (_: Throwable) {}
        }
        state.free()
    }
}
