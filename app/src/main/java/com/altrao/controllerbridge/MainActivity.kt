package com.altrao.controllerbridge

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The entire UI: an IP field, a connect button, a status line, and a live controller
 * readout.
 *
 * No XML layouts and no AppCompat -- everything is built in code, so the app has zero
 * resource dependencies and the APK stays tiny.
 *
 * Input arrives through [InputDeviceReader] via the two overrides below. Both must
 * return `true` when they handle an event, or Android consumes the D-pad and face
 * buttons for its own focus navigation.
 */
class MainActivity : Activity() {

    private val state = GamepadState()

    private lateinit var inputReader: InputDeviceReader
    private lateinit var bridge: BridgeClient

    private lateinit var ipField: EditText
    private lateinit var connectButton: Button
    private lateinit var debugButton: Button
    private lateinit var statusView: TextView
    private lateinit var deviceView: TextView
    private lateinit var readoutView: TextView
    private lateinit var logView: TextView

    private var renderedLogVersion = -1

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * FLAG_KEEP_SCREEN_ON stops the device sleeping mid-session; this drops the backlight
     * to minimum after [DIM_AFTER_MS] without a touch to save battery. Any touch restores it.
     */
    private val dimRunnable = Runnable { setBrightness(DIM_BRIGHTNESS) }

    /**
     * The readout polls rather than being push-driven, which is deliberate: Android only
     * emits a MotionEvent when an axis *changes*, so a push-driven display would freeze
     * on a held stick. Polling also exercises the same snapshot the sender thread reads.
     *
     * The same tick reconciles controller presence, which is what detects a pad being
     * yanked mid-session -- see [InputDeviceReader.refreshDevicePresence]. Riding the
     * existing loop costs nothing and keeps the app free of InputManager listener
     * registration and its API-level differences.
     */
    private val readoutRunnable = object : Runnable {
        override fun run() {
            inputReader.refreshDevicePresence()
            renderReadout()
            renderLog()
            mainHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        inputReader = InputDeviceReader(state)
        bridge = BridgeClient(state)

        buildUi()
        attachBridgeListener()

        mainHandler.post(readoutRunnable)
        mainHandler.postDelayed(dimRunnable, DIM_AFTER_MS)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            mainHandler.removeCallbacks(dimRunnable)
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            mainHandler.postDelayed(dimRunnable, DIM_AFTER_MS)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setBrightness(value: Float) {
        val params = window.attributes
        if (params.screenBrightness == value) return
        params.screenBrightness = value
        window.attributes = params
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(readoutRunnable)
        mainHandler.removeCallbacks(dimRunnable)
        bridge.disconnect()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ bridge wiring

    private fun attachBridgeListener() {
        bridge.listener = object : BridgeClient.Listener {
            override fun onStatus(message: String) {
                mainHandler.post { statusView.text = message }
            }

            override fun onConnected() {
                mainHandler.post {
                    connectButton.text = getString(R.string.disconnect)
                    ipField.isEnabled = false
                }
            }

            override fun onDisconnected(reason: String) {
                mainHandler.post {
                    statusView.text = reason
                    connectButton.text = getString(R.string.connect)
                    ipField.isEnabled = true
                }
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            // Hold focus on the container, not the IP field. A focused EditText keeps the
            // IME in the key-event path, where it can eat gamepad buttons before
            // dispatchKeyEvent sees them.
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
        })

        ipField = EditText(this).apply {
            hint = getString(R.string.ip_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setText(loadLastIp())
        }

        root.addView(ipField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        debugButton = Button(this).apply {
            text = getString(R.string.debug_off)
            setOnClickListener { toggleDebug() }
        }

        connectButton = Button(this).apply {
            text = getString(R.string.connect)
            setOnClickListener { toggleConnection() }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(debugButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(connectButton, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { leftMargin = dp(8) })
        }

        root.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        statusView = TextView(this).apply {
            text = getString(R.string.idle)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(statusView)

        deviceView = TextView(this).apply {
            text = getString(R.string.no_controller)
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(deviceView)

        readoutView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(readoutView)

        // Newest line first, so fresh events sit right under the readout; the page's
        // ScrollView scrolls back through older ones.
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.GRAY)
            setPadding(0, dp(16), 0, 0)
            visibility = View.GONE
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
        root.requestFocus()
    }

    private fun toggleDebug() {
        DebugLog.enabled = !DebugLog.enabled
        debugButton.text = getString(if (DebugLog.enabled) R.string.debug_on else R.string.debug_off)
        logView.visibility = if (DebugLog.enabled) View.VISIBLE else View.GONE
        renderedLogVersion = -1
        renderLog()
    }

    private fun renderLog() {
        if (!DebugLog.enabled) return
        val version = DebugLog.version
        if (version == renderedLogVersion) return
        renderedLogVersion = version
        logView.text = DebugLog.text()
    }

    private fun toggleConnection() {
        // isActive() covers the handshake phase too, so a second press while connecting
        // disconnects instead of opening another socket (= another controller on the PC).
        if (bridge.isActive()) {
            bridge.disconnect()
            return
        }

        val raw = ipField.text.toString().trim()
        val target = parseTarget(raw)
        if (target == null) {
            statusView.text = getString(R.string.invalid_ip)
            return
        }

        saveLastIp(raw)
        hideKeyboard()

        bridge.connect(target.first, target.second)
        connectButton.text = getString(R.string.disconnect)
        ipField.isEnabled = false
    }

    /**
     * Accepts "192.168.1.50", "192.168.1.50:60001", or a pasted "ws://192.168.1.50:60001"
     * so the value shown in the PC window can be copied verbatim. Returns null when no
     * usable host is present.
     */
    private fun parseTarget(raw: String): Pair<String, Int>? {
        var value = raw.trim()
        if (value.startsWith("ws://", ignoreCase = true)) value = value.substring(5)

        val parts = value.split(":")
        val host = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val port = if (parts.size > 1) {
            parts[1].trim().toIntOrNull() ?: return null
        } else {
            DEFAULT_PORT
        }

        return host to port
    }

    // ------------------------------------------------------------------ input hooks

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (inputReader.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputReader.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ readout

    private fun renderReadout() {
        deviceView.text = if (inputReader.hasDevice) {
            getString(R.string.controller_fmt, inputReader.deviceName ?: "?")
        } else {
            getString(R.string.no_controller)
        }

        val s = state
        readoutView.text = buildString {
            append("LX %+.2f  LY %+.2f\n".format(s.leftStickX, s.leftStickY))
            append("RX %+.2f  RY %+.2f\n".format(s.rightStickX, s.rightStickY))
            append("LT %.2f  RT %.2f\n".format(s.leftTrigger, s.rightTrigger))
            append("A %d  B %d  X %d  Y %d\n".format(
                b(s.buttonSouth), b(s.buttonEast), b(s.buttonWest), b(s.buttonNorth)
            ))
            append("LB %d  RB %d  L3 %d  R3 %d\n".format(
                b(s.leftShoulder), b(s.rightShoulder), b(s.leftStickButton), b(s.rightStickButton)
            ))
            append("D-Pad %s%s%s%s\n".format(
                if (s.up) "U" else "-",
                if (s.down) "D" else "-",
                if (s.left) "L" else "-",
                if (s.right) "R" else "-"
            ))
            append("Start %d  Select %d  Guide %d".format(b(s.buttonStart), b(s.buttonSelect), b(s.ps)))
        }
    }

    private fun b(value: Boolean) = if (value) 1 else 0

    // ------------------------------------------------------------------ misc

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(ipField.windowToken, 0)
        ipField.clearFocus()
    }

    private fun loadLastIp(): String {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_IP, DEFAULT_IP)
            .orEmpty()
    }

    private fun saveLastIp(value: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_IP, value)
            .apply()
    }

    private companion object {
        const val PREFS = "controller_bridge"
        const val KEY_LAST_IP = "last_ip"

        /** Matches the server's default port in websocket.ts. */
        const val DEFAULT_PORT = 60001
        const val DEFAULT_IP = "192.168.1.50"

        const val DIM_AFTER_MS = 30_000L

        /** Not 0f: some devices treat an exact 0 as backlight off. */
        const val DIM_BRIGHTNESS = 0.01f
    }
}
