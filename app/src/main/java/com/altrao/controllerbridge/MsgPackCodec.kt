package com.altrao.controllerbridge

import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import java.io.ByteArrayOutputStream

/**
 * MessagePack encoding of the payloads the server understands.
 *
 * CRITICAL: the server decodes with `@msgpack/msgpack`'s `decode()`, which returns a
 * JS object, then switches on `decoded.action`. The Unity client serializes with
 * `[MessagePackObject(keyAsPropertyName = true)]`, so every key must be the exact C#
 * field name and the map must be a str-keyed map -- never an array.
 *
 * Adding fields is safe. Renaming or removing them is not: the server reads by name.
 */
object MsgPackCodec {

    const val ACTION_HANDSHAKE = "handshake"
    const val ACTION_REGISTER = "register"
    const val ACTION_INPUT = "input"
    const val ACTION_DISCONNECT = "disconnect"
    const val ACTION_DELAY_ACK = "delay_test_request_ack"

    /** Must match `GamepadType.Xbox` in the server's `shared/enums.ts`. */
    const val GAMEPAD_TYPE_XBOX = "GAMEPAD_XBOX360"

    /** `{ action, id, gamepadType, gamepadData: nil }` */
    fun controlPayload(action: String, id: Int, gamepadType: String = ""): ByteArray {
        val out = ByteArrayOutputStream(96)
        MessagePack.newDefaultPacker(out).use { packer ->
            packer.packMapHeader(4)
            packer.packString("action").packString(action)
            packer.packString("id").packInt(id)
            packer.packString("gamepadType").packString(gamepadType)
            packer.packString("gamepadData").packNil()
        }
        return out.toByteArray()
    }

    /**
     * `{ action: "input", id, gamepadType, gamepadData: {...}, [timestamp] }`
     *
     * `timestamp` is only added once the server has asked for a latency test, matching
     * the Unity client. The server reads it only while `client.isTestingDelay` is set.
     */
    fun inputPayload(id: Int, state: GamepadState, includeTimestamp: Boolean): ByteArray {
        val out = ByteArrayOutputStream(320)
        MessagePack.newDefaultPacker(out).use { packer ->
            packer.packMapHeader(if (includeTimestamp) 5 else 4)

            packer.packString("action").packString(ACTION_INPUT)
            packer.packString("id").packInt(id)
            packer.packString("gamepadType").packString(GAMEPAD_TYPE_XBOX)

            packer.packString("gamepadData")
            packGamepadData(packer, state)

            if (includeTimestamp) {
                packer.packString("timestamp").packLong(System.currentTimeMillis())
            }
        }
        return out.toByteArray()
    }

    /**
     * The `gamepadData` map: the 20 Unity `GamepadData` fields plus `ps`.
     *
     * The header count MUST equal the number of pairs packed below. A mismatch produces
     * truncated MessagePack that the server's `decode()` rejects, dropping every frame.
     *
     * Booleans go out as real booleans (`packBoolean`), not ints. `@msgpack/msgpack`
     * decodes them to JS booleans, which is what `xboxInput()` feeds to the FFI layer.
     * Sticks are float32 in [-1, 1] with +Y = up; triggers float32 in [0, 1].
     */
    private fun packGamepadData(packer: MessagePacker, s: GamepadState) {
        packer.packMapHeader(21)

        packer.packString("buttonEast").packBoolean(s.buttonEast)
        packer.packString("buttonWest").packBoolean(s.buttonWest)
        packer.packString("buttonNorth").packBoolean(s.buttonNorth)
        packer.packString("buttonSouth").packBoolean(s.buttonSouth)

        packer.packString("up").packBoolean(s.up)
        packer.packString("down").packBoolean(s.down)
        packer.packString("left").packBoolean(s.left)
        packer.packString("right").packBoolean(s.right)

        packer.packString("leftShoulder").packBoolean(s.leftShoulder)
        packer.packString("rightShoulder").packBoolean(s.rightShoulder)

        packer.packString("leftTrigger").packFloat(s.leftTrigger)
        packer.packString("rightTrigger").packFloat(s.rightTrigger)

        packer.packString("leftStickButton").packBoolean(s.leftStickButton)
        packer.packString("rightStickButton").packBoolean(s.rightStickButton)

        packer.packString("leftStickX").packFloat(s.leftStickX)
        packer.packString("leftStickY").packFloat(s.leftStickY)

        packer.packString("rightStickX").packFloat(s.rightStickX)
        packer.packString("rightStickY").packFloat(s.rightStickY)

        packer.packString("buttonStart").packBoolean(s.buttonStart)
        packer.packString("buttonSelect").packBoolean(s.buttonSelect)

        // Guide / Xbox / PS button. Extra key -- the server ignores it until it reads it.
        packer.packString("ps").packBoolean(s.ps)
    }

    /**
     * `{ action: "delay_test_request_ack", id, timestamp, payload }`
     *
     * `payload` is the client's *receive* time as a String (the server does
     * `Number(payload)`), and `timestamp` is the *send* time. The server derives
     * `rtt = (T4 - T1 - (T3 - T2)) / 2`, so swapping these two silently yields a
     * negative latency rather than an error.
     */
    fun delayTestAck(id: Int, receiveTimeMs: Long): ByteArray {
        val out = ByteArrayOutputStream(128)
        MessagePack.newDefaultPacker(out).use { packer ->
            packer.packMapHeader(4)
            packer.packString("action").packString(ACTION_DELAY_ACK)
            packer.packString("id").packInt(id)
            packer.packString("timestamp").packLong(System.currentTimeMillis())
            packer.packString("payload").packString(receiveTimeMs.toString())
        }
        return out.toByteArray()
    }

    /** Shape of `handshake_ack` / `register_ack` / `error` responses from the server. */
    data class ControlResponse(val action: String?, val status: String?, val payload: String?)

    /**
     * Reads the server's `{ action, status, payload }` response.
     *
     * `payload` is a plain string, but `tryUnpackNil()` is used defensively on every
     * field so a future null doesn't throw inside the reader thread.
     */
    fun decodeControlResponse(bytes: ByteArray): ControlResponse {
        MessagePack.newDefaultUnpacker(bytes).use { unpacker ->
            val size = unpacker.unpackMapHeader()
            var action: String? = null
            var status: String? = null
            var payload: String? = null

            repeat(size) {
                when (unpacker.unpackString()) {
                    "action" -> action = readStringOrNull(unpacker)
                    "status" -> status = readStringOrNull(unpacker)
                    "payload" -> payload = readStringOrNull(unpacker)
                    else -> unpacker.skipValue()
                }
            }
            return ControlResponse(action, status, payload)
        }
    }

    private fun readStringOrNull(unpacker: MessageUnpacker): String? {
        return if (unpacker.tryUnpackNil()) null else unpacker.unpackString()
    }
}
