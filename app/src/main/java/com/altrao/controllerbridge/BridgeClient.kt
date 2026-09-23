package com.altrao.controllerbridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
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
        fun onConnected()
        fun onDisconnected(reason: String)
    }

    @Volatile
    var listener: Listener? = null

    /** The live socket, or null. Also the sender loop's run condition. */
    private val socketRef = AtomicReference<WebSocket?>(null)

    /** Assigned by the server in `register_ack`. -1 until then. */
    private val clientId = AtomicInteger(-1)

    private var senderThread: Thread? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
        .build()

    fun connect(ip: String, port: Int) {
        // Never leave a previous socket open behind a new one: the server would keep its
        // virtual pad alive as an extra controller.
        if (socketRef.get() != null) disconnect()

        clientId.set(-1)

        val url = "ws://$ip:$port"
        listener?.onStatus("Connecting to $url")
        DebugLog.d("Connecting to $url")

        val request = Request.Builder().url(url).build()
        socketRef.set(httpClient.newWebSocket(request, SocketListener()))
    }

    fun disconnect() {
        // Clearing socketRef first also makes this socket's own close callbacks no-ops.
        val socket = socketRef.getAndSet(null)
        stopSender()
        if (socket != null) {
            DebugLog.d("Disconnecting (id ${clientId.get()})")
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
            // Reset the UI now rather than waiting for the server's close frame.
            listener?.onDisconnected("Closed")
        }

        clientId.set(-1)
    }

    /**
     * True from [connect] until the socket is closed or fails -- including the handshake
     * phase, so the UI's button can never open a second socket alongside a live one.
     */
    fun isActive(): Boolean = socketRef.get() != null

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

            try {
                while (socketRef.get() === socket) {
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
            } catch (e: InterruptedException) {
                // stopSender() interrupts the sleep. Uncaught, this would crash the app.
            }
        }, "gamepad-sender")

        thread.isDaemon = true
        senderThread = thread
        thread.start()
    }

    /** Returns false when the socket is gone and the loop should stop. */
    private fun sendFrame(socket: WebSocket): Boolean {
        return try {
            val queued = socket.send(ByteString.of(*MsgPackCodec.inputPayload(clientId.get(), state)))
            if (!queued) {
                // OkHttp returns false when the socket is already closed. This is the
                // normal way a WiFi drop surfaces -- not an exception.
                DebugLog.d("Send rejected: socket no longer open")
                false
            } else {
                true
            }
        } catch (e: Throwable) {
            if (socketRef.get() === socket) {
                DebugLog.d("Send failed: $e")
            }
            false
        }
    }

    private fun stopSender() {
        // Interrupt rather than join: a blocking teardown must never stall the UI thread.
        senderThread?.interrupt()
        senderThread = null
    }

    // ---------------------------------------------------------------- socket events

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener?.onStatus("Handshaking")
            DebugLog.d("Socket open, sending handshake")
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
                DebugLog.d("Undecodable server message: $e")
                return
            }

            DebugLog.d("<- ${response.action} status=${response.status} payload=${response.payload}")

            when (response.action) {
                "handshake_ack" -> handleHandshakeAck(webSocket, response)

                "register_ack" -> handleRegisterAck(webSocket, response)

                "delay_test_request" -> handleDelayTestRequest(webSocket)

                "error" -> {
                    listener?.onStatus("Server error: ${response.payload ?: "unknown"}")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            DebugLog.d("Socket failure: $t")
            socketGone(webSocket, "Connection failed: ${t.message ?: t::class.java.simpleName}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            DebugLog.d("Socket closing: $code $reason")
            webSocket.close(1000, null)
            socketGone(webSocket, if (reason.isBlank()) "Server closed" else "Server closed: $reason")
        }
    }

    /**
     * Tears down session state once [webSocket] is finished. A no-op unless it is still
     * the live socket: after [disconnect] (which already reported "Closed") or a newer
     * [connect], its late callbacks must not stop the sender or reset the UI.
     */
    private fun socketGone(webSocket: WebSocket, reason: String) {
        if (!socketRef.compareAndSet(webSocket, null)) return

        stopSender()
        clientId.set(-1)
        listener?.onDisconnected(reason)
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

        listener?.onConnected()
        listener?.onStatus("Streaming (id $id)")

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
