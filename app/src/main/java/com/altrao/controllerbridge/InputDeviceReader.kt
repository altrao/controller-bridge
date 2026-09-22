package com.altrao.controllerbridge

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Primary input path: Android's sanctioned gamepad API. No permissions, no root.
 *
 * [MainActivity] overrides `dispatchGenericMotionEvent` and `dispatchKeyEvent`, forwards
 * here, and returns `true` from both. Returning true matters -- otherwise Android
 * swallows the D-pad and face buttons for its own focus navigation.
 *
 * Axis changes arrive as MotionEvents, buttons as KeyEvents. [BridgeClient] streams at a
 * fixed rate regardless, so a stick held at 60% keeps reporting 60% even though Android
 * stops emitting MotionEvents the moment it stops moving.
 *
 * Device mapping is not standardised, which is the one real wrinkle:
 *   - left stick is always AXIS_X / AXIS_Y
 *   - the right stick is AXIS_Z / AXIS_RZ on some pads and AXIS_RX / AXIS_RY on others
 *   - triggers expose AXIS_LTRIGGER / AXIS_RTRIGGER only if the pad reports analogue
 *     triggers; digital-only pads fall back to KEYCODE_BUTTON_L2 / R2
 * So the axes are probed once per device rather than assumed.
 */
class InputDeviceReader(private val state: GamepadState) {

    private companion object {
        /** Axis deviations below this snap to exactly 0. */
        const val STICK_DEADZONE = 0.08f

        /** Trigger noise below this reads as fully released. */
        const val TRIGGER_DEADZONE = 0.06f

        /** Motion axes only count as "reported" above this magnitude. */
        const val HAT_THRESHOLD = 0.5f
    }

    private var probedDeviceId = -1
    private var hasLeftTriggerAxis = false
    private var hasRightTriggerAxis = false
    private var useRxRyForRightStick = false

    // D-pad arrives either as analogue HAT axes or as discrete keycodes, depending on
    // the pad. Both are tracked and OR'd together, so neither path clobbers the other.
    private var hatLeft = false
    private var hatRight = false
    private var hatUp = false
    private var hatDown = false

    private var keyLeft = false
    private var keyRight = false
    private var keyUp = false
    private var keyDown = false

    /** Set once a real gamepad has been seen, for the UI. */
    @Volatile
    var hasDevice = false
        private set

    @Volatile
    var deviceName: String? = null
        private set

    fun onGenericMotion(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        if (!isGamepad(device)) return false

        probe(device)

        // Android reports negative Y as UP. The wire format follows Unity / the Input
        // System, where positive Y is UP, so both stick Y axes are negated.
        state.leftStickX = deadzone(event.getAxisValue(MotionEvent.AXIS_X))
        state.leftStickY = -deadzone(event.getAxisValue(MotionEvent.AXIS_Y))

        if (useRxRyForRightStick) {
            state.rightStickX = deadzone(event.getAxisValue(MotionEvent.AXIS_RX))
            state.rightStickY = -deadzone(event.getAxisValue(MotionEvent.AXIS_RY))
        } else {
            state.rightStickX = deadzone(event.getAxisValue(MotionEvent.AXIS_Z))
            state.rightStickY = -deadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
        }

        if (hasLeftTriggerAxis) {
            state.leftTrigger = trigger(event.getAxisValue(MotionEvent.AXIS_LTRIGGER))
        }
        if (hasRightTriggerAxis) {
            state.rightTrigger = trigger(event.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        }

        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        // Only treat HAT as authoritative while it is actually deflected. A pad that
        // sends HAT=0 on every event would otherwise permanently clear the keycode path.
        hatLeft = hx < -HAT_THRESHOLD
        hatRight = hx > HAT_THRESHOLD
        hatUp = hy < -HAT_THRESHOLD
        hatDown = hy > HAT_THRESHOLD

        publishDpad()
        return true
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (!isGamepad(device)) return false

        probe(device)

        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> state.buttonSouth = pressed
            KeyEvent.KEYCODE_BUTTON_B -> state.buttonEast = pressed
            KeyEvent.KEYCODE_BUTTON_X -> state.buttonWest = pressed
            KeyEvent.KEYCODE_BUTTON_Y -> state.buttonNorth = pressed

            KeyEvent.KEYCODE_BUTTON_L1 -> state.leftShoulder = pressed
            KeyEvent.KEYCODE_BUTTON_R1 -> state.rightShoulder = pressed

            KeyEvent.KEYCODE_BUTTON_THUMBL -> state.leftStickButton = pressed
            KeyEvent.KEYCODE_BUTTON_THUMBR -> state.rightStickButton = pressed

            KeyEvent.KEYCODE_BUTTON_START -> state.buttonStart = pressed
            KeyEvent.KEYCODE_BUTTON_SELECT -> state.buttonSelect = pressed

            // Only used when the pad has no analogue trigger axis. On a pad that does
            // report the axis, this button event fires at the ~0.5 threshold and would
            // fight the real analogue value, so it is ignored.
            KeyEvent.KEYCODE_BUTTON_L2 -> if (!hasLeftTriggerAxis) {
                state.leftTrigger = if (pressed) 1f else 0f
            }

            KeyEvent.KEYCODE_BUTTON_R2 -> if (!hasRightTriggerAxis) {
                state.rightTrigger = if (pressed) 1f else 0f
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> keyLeft = pressed
            KeyEvent.KEYCODE_DPAD_RIGHT -> keyRight = pressed
            KeyEvent.KEYCODE_DPAD_UP -> keyUp = pressed
            KeyEvent.KEYCODE_DPAD_DOWN -> keyDown = pressed

            else -> return false
        }

        publishDpad()
        return true
    }

    /**
     * Reconciles cached probe state against the devices Android currently reports.
     *
     * Called ~10 Hz from the UI loop rather than driven by InputManager's
     * InputDeviceListener. Its `onInputDeviceRemoved(int)` signature was superseded by a
     * no-argument default method in API 29, so which override actually fires depends on
     * the platform version. Polling `getDeviceIds()` behaves identically on every level,
     * and a 100 ms detection delay is imperceptible beside a controller that would
     * otherwise stream stuck input forever.
     *
     * Handles both directions: a pad that is plugged in but not yet touched is adopted
     * so the status line is honest, and the probed pad disappearing clears its state.
     */
    fun refreshDevicePresence() {
        val gamepads = InputDevice.getDeviceIds()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { isGamepad(it) }

        if (hasDevice && gamepads.none { it.id == probedDeviceId }) {
            onDeviceDetached()
        }

        // Adopt a pad that is plugged in but has not produced an event yet.
        if (probedDeviceId == -1) {
            gamepads.firstOrNull()?.let { probe(it) }
        }
    }

    /**
     * Clears cached input and probe state, so the controller reads as neutral.
     *
     * Resetting [probedDeviceId] matters: Android recycles device ids, so a replacement
     * pad inheriting the previous id would otherwise keep the old pad's axis layout --
     * misreading Z/RZ as the right stick, or losing analogue triggers to the L2/R2
     * keycode fallback.
     */
    fun onDeviceDetached() {
        state.reset()
        clearToggles()

        hasDevice = false
        deviceName = null

        probedDeviceId = -1
        hasLeftTriggerAxis = false
        hasRightTriggerAxis = false
        useRxRyForRightStick = false
    }

    private fun clearToggles() {
        hatLeft = false
        hatRight = false
        hatUp = false
        hatDown = false
        keyLeft = false
        keyRight = false
        keyUp = false
        keyDown = false
    }

    private fun publishDpad() {
        // The server's Xbox path reads these four booleans independently, so the union
        // of both sources is exactly what it expects. (Its DualShock path additionally
        // synthesises 8-way diagonals from them, which is irrelevant while we register
        // as an Xbox 360 pad.)
        state.left = hatLeft || keyLeft
        state.right = hatRight || keyRight
        state.up = hatUp || keyUp
        state.down = hatDown || keyDown
    }

    /** True when the device advertises itself as a gamepad or joystick. */
    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * Probes the device's declared motion ranges once per device id. Pads that lack an
     * axis simply report no range for it, which is the signal used to pick the fallback.
     */
    private fun probe(device: InputDevice) {
        if (probedDeviceId == device.id) return

        probedDeviceId = device.id
        hasDevice = true
        deviceName = device.name

        hasLeftTriggerAxis = device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null
        hasRightTriggerAxis = device.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null

        // Prefer RX/RY for the right stick when declared, since pads exposing both use
        // Z/RZ for something else (often the triggers).
        useRxRyForRightStick =
            device.getMotionRange(MotionEvent.AXIS_RX) != null &&
                device.getMotionRange(MotionEvent.AXIS_RY) != null
    }

    private fun deadzone(value: Float): Float {
        val clamped = value.coerceIn(-1f, 1f)
        return if (abs(clamped) < STICK_DEADZONE) 0f else clamped
    }

    private fun trigger(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return if (clamped < TRIGGER_DEADZONE) 0f else clamped
    }
}
