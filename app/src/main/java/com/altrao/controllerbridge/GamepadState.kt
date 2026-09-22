package com.altrao.controllerbridge

/**
 * Live snapshot of the physical controller.
 *
 * Field names and semantics deliberately mirror the Unity client's `GamepadData`
 * so the wire format is byte-for-byte compatible with the existing server:
 *
 *  - sticks are -1..1 with +Y meaning UP (the Unity/Input System convention)
 *  - triggers are 0..1
 *
 * Every field is independent and written from the UI thread by input events while
 * the sender thread reads them at 125 Hz. Each field is atomic on its own, so a
 * packet can contain a one-event mix between fields -- imperceptible at this rate
 * and far cheaper than boxing a snapshot per event.
 */
class GamepadState {

    @Volatile var buttonSouth = false

    @Volatile var buttonEast = false

    @Volatile var buttonWest = false

    @Volatile var buttonNorth = false

    @Volatile var up = false

    @Volatile var down = false

    @Volatile var left = false

    @Volatile var right = false

    @Volatile var leftShoulder = false

    @Volatile var rightShoulder = false

    @Volatile var leftTrigger = 0f

    @Volatile var rightTrigger = 0f

    @Volatile var leftStickButton = false

    @Volatile var rightStickButton = false

    @Volatile var leftStickX = 0f

    @Volatile var leftStickY = 0f

    @Volatile var rightStickX = 0f

    @Volatile var rightStickY = 0f

    @Volatile var buttonStart = false

    @Volatile var buttonSelect = false

    /** Clears everything. Used when the controller is unplugged or the session ends. */
    fun reset() {
        buttonSouth = false
        buttonEast = false
        buttonWest = false
        buttonNorth = false

        up = false
        down = false
        left = false
        right = false

        leftShoulder = false
        rightShoulder = false

        leftTrigger = 0f
        rightTrigger = 0f

        leftStickButton = false
        rightStickButton = false

        leftStickX = 0f
        leftStickY = 0f
        rightStickX = 0f
        rightStickY = 0f

        buttonStart = false
        buttonSelect = false
    }
}
