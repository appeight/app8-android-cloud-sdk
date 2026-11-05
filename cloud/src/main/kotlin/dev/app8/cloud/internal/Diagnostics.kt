package dev.app8.cloud.internal

import android.util.Log

/**
 * Logcat diagnostics, tag `App8Cloud` (iOS `Diagnostics`, subsystem `dev.app8.cloud`). Every level
 * is gated on [enabled]: a host that leaves diagnostics off writes nothing to logcat.
 */
internal class Diagnostics(val enabled: Boolean) {

    fun debug(message: () -> String) {
        if (enabled) runCatching { Log.d(TAG, message()) }
    }

    fun info(message: () -> String) {
        if (enabled) runCatching { Log.i(TAG, message()) }
    }

    fun warning(message: () -> String) {
        if (enabled) runCatching { Log.w(TAG, message()) }
    }

    fun error(message: () -> String) {
        if (enabled) runCatching { Log.e(TAG, message()) }
    }

    /**
     * Always emitted, even with diagnostics off. Only for one-time setup warnings with static text:
     * never user or host data.
     */
    fun configurationWarning(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        const val TAG: String = "App8Cloud"
        val disabled: Diagnostics = Diagnostics(false)
    }
}
