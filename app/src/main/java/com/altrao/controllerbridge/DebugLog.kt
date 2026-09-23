package com.altrao.controllerbridge

import android.util.Log

/**
 * Bounded in-memory event log shown by the Debug panel. Every line also goes to logcat.
 *
 * [enabled] only gates the high-rate key/axis lines; connection and device events are
 * always recorded so turning Debug on shows what already happened.
 */
object DebugLog {

    private const val TAG = "ControllerBridge"
    private const val MAX_LINES = 200

    /** Newest first. */
    private val lines = ArrayDeque<String>()

    @Volatile
    var enabled = false

    /** Bumped on every write, so the UI only re-renders when something changed. */
    @Volatile
    var version = 0
        private set

    fun d(message: String) {
        Log.d(TAG, message)
        val line = "%tT %s".format(System.currentTimeMillis(), message)
        synchronized(lines) {
            lines.addFirst(line)
            if (lines.size > MAX_LINES) lines.removeLast()
            version++
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}
