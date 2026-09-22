package com.altrao.controllerbridge

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket session with the PC bridge.
 *
 * Handshake sequence, matching `WebSocketConnector.cs` on the Unity client and the
 * server's `handleWebSocketMessage` switch:
 *
 *   1. connect
 *   2. -> handshake                        (id -1, empty gamepadType, nil gamepadData)
 *   3. <- handshake_ack { status, payload }
 *   4. -> register  gamepadType = GAMEPAD_XBOX360
 *   5. <- register_ack { payload = "<clientId>" }
 *   6. -> input ... at [SEND_HZ], until the socket closes
 *
 * The server also pushes `delay_test_request`; answering it is what lets the PC UI show
 * a measured round-trip latency. It is optional for correctness.
 */
class BridgeClient(private val state: GamepadState) {

    companion object {
        private const val TAG = "BridgeClient"

        /**
         * 125 Hz. Comfortably above the 60 Hz most pads sample at, and small enough
         * (~300 bytes/frame) to be irrelevant on WiFi. MessagePack keeps each frame
         * about a third the size of the equivalent JSON.
         */
        const val SEND_HZ = 125

        private const val CONNECT_TIMEOUT_S = 4L

        /**
         * OkHttp-level liveness probe. A WiFi drop can leave a TCP socket half-open for
         * minutes; without a ping the app would keep "streaming" into a dead socket.
         */
        private const val PING_INTERVAL_S = 15L
    }

    interface Listener {
        fun onStatus(message: String)
        fun onConnected(clientId: Int?)
        fun onDisconnected(reason: String)
    }

    @Volatile
    var listener: Listener? = null

    private val socketRef = AtomicReference<WebSocket?>(null)

    /** Assigned by the server in `register_ack`. -1 until then. */
    private val clientId = AtomicInteger(-1)

    @Volatile
    private var testingDelay = false

    /** True between register_ack and disconnect -- i.e. when frames should be sent. */
    private val streaming = AtomicBoolean(false)

    private var senderThread: Thread? = null

    /** Distinguishes a user-initiated close from a dropped connection, for the status line. */
    @Volatile
    private var closingIntentionally = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
        .build()

    fun connect(ip: String, port: Int) {
        closingIntentionally = false
        clientId.set(-1)

        val url = "ws://$ip:$port"
        listener?.onStatus("Connecting to $url")

        val request = Request.Builder().url(url).build()
        socketRef.set(httpClient.newWebSocket(request, SocketListener()))
    }

    fun disconnect() {
        closingIntentionally = true
        stopSender()

        val socket = socketRef.getAndSet(null)
        if (socket != null) {
            // Only send an explicit disconnect once registration succeeded. Before that
            // the server has no clientMap entry for us, and would emit a `gamepad:disconnected`
            // event for an id it never issued.
            val id = clientId.get()
            if (id >= 0) {
                socket.send(ByteString.of(*MsgPackCodec.controlPayload(
                    MsgPackCodec.ACTION_DISCONNECT,
                    id,
                    MsgPackCodec.GAMEPAD_TYPE_XBOX
                )))
            }
            socket.close(1000, "client closing")
        }

        clientId.set(-1)
        streaming.set(false)
    }

    fun isStreaming(): Boolean = streaming.get()

    // ---------------------------------------------------------------- sender loop

    /**
     * One dedicated thread pacing frames. Runs off the UI thread and off OkHttp's own
     * dispatcher, so a slow enqueue cannot stall the socket's reader.
     */
    private fun startSender(socket: WebSocket) {
        stopSender()

        val thread = Thread({
            val frameNs = 1_000_000_000L / SEND_HZ
            var next = System.nanoTime()

            while (streaming.get()) {
                if (!sendFrame(socket)) break

                // Drift-corrected pacing. `Thread.sleep(8)` accumulates error and the
                // real rate drifts below 125 Hz; advancing a whole period keeps the
                // average exact.
                next += frameNs
                val remaining = next - System.nanoTime()
                if (remaining > 0) {
                    Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                } else {
                    // Fell behind on a slow frame. Re-base instead of spinning to catch up.
                    next = System.nanoTime()
                }
            }
        }, "gamepad-sender")

        thread.isDaemon = true
        senderThread = thread
        thread.start()
    }

    /** Returns false when the socket is gone and the loop should stop. */
    private fun sendFrame(socket: WebSocket): Boolean {
        return try {
            val queued = socket.send(ByteString.of(*MsgPackCodec.inputPayload(
                clientId.get(),
                state,
                testingDelay
            )))
            if (!queued) {
                // OkHttp returns false when the socket is already closed. This is the
                // normal way a WiFi drop surfaces -- not an exception.
                Log.w(TAG, "Send rejected: socket no longer open")
                false
            } else {
                true
            }
        } catch (e: Throwable) {
            if (streaming.get()) {
                Log.w(TAG, "Send failed: ${e.message}")
            }
            false
        }
    }

    private fun stopSender() {
        streaming.set(false)
        // Interrupt rather than join: a blocking teardown must never stall the UI thread.
        senderThread?.interrupt()
        senderThread = null
    }

    // ---------------------------------------------------------------- socket events

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener?.onStatus("Handshaking")
            webSocket.send(ByteString.of(*MsgPackCodec.controlPayload(
                MsgPackCodec.ACTION_HANDSHAKE,
                -1
            )))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val response = try {
                MsgPackCodec.decodeControlResponse(bytes.toByteArray())
            } catch (e: Throwable) {
                // The server can legitimately send something we don't model. Log and
                // ignore rather than tearing down a working session.
                Log.w(TAG, "Undecodable server message: ${e.message}")
                return
            }

            when (response.action) {
                "handshake_ack" -> handleHandshakeAck(webSocket, response)

                "register_ack" -> handleRegisterAck(webSocket, response)

                "delay_test_request" -> handleDelayTestRequest(webSocket)

                "delay_test_end" -> {
                    testingDelay = false
                }

                "error" -> {
                    listener?.onStatus("Server error: ${response.payload ?: "unknown"}")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            stopSender()
            streaming.set(false)
            clientId.set(-1)

            if (closingIntentionally) {
                listener?.onDisconnected("Closed")
            } else {
                val detail = t.message ?: t::class.java.simpleName
                listener?.onDisconnected("Connection failed: $detail")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            stopSender()
            streaming.set(false)
            webSocket.close(1000, null)
            listener?.onDisconnected(if (reason.isBlank()) "Server closed" else "Server closed: $reason")
        }
    }

    /**
     * The server reports a full lobby in the *status* field with payload "E_MAX_CONN",
     * then closes the socket. Both fields are checked so a future version skew cannot
     * leave the app waiting on a handshake that will never be answered.
     */
    private fun handleHandshakeAck(webSocket: WebSocket, response: MsgPackCodec.ControlResponse) {
        val rejected = response.status == "error" || response.payload == "E_MAX_CONN"
        if (rejected) {
            listener?.onStatus("Rejected: server is at max connections")
            webSocket.close(1000, "max connections")
            return
        }
        register(webSocket)
    }

    private fun handleRegisterAck(webSocket: WebSocket, response: MsgPackCodec.ControlResponse) {
        val id = response.payload?.toIntOrNull() ?: -1
        clientId.set(id)

        if (id < 0) {
            listener?.onStatus("Register failed: server sent no client id")
            return
        }

        listener?.onConnected(id)
        listener?.onStatus("Streaming (id $id)")

        streaming.set(true)
        startSender(webSocket)
    }

    /**
     * `delay_test_request` carries the server's send time in `payload`. The ack must echo
     * our *receive* time in its own `payload` field and stamp our *send* time in
     * `timestamp`; the server computes `rtt = (T4 - T1 - (T3 - T2)) / 2`. Swapping the two
     * yields a negative latency rather than an error, so they are kept straight here.
     */
    private fun handleDelayTestRequest(webSocket: WebSocket) {
        val receivedAt = System.currentTimeMillis()
        testingDelay = true

        webSocket.send(ByteString.of(*MsgPackCodec.delayTestAck(clientId.get(), receivedAt)))
    }

    private fun register(webSocket: WebSocket) {
        listener?.onStatus("Registering")
        webSocket.send(ByteString.of(*MsgPackCodec.controlPayload(
            MsgPackCodec.ACTION_REGISTER,
            -1,
            MsgPackCodec.GAMEPAD_TYPE_XBOX
        )))
    }
}
