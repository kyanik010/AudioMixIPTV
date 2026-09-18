package com.audiomix.iptv

import android.os.Handler
import android.os.Looper
import android.util.Log

class RecoverySystem {
    private val handler = Handler(Looper.getMainLooper())
    private val attempts = mutableMapOf<String, Int>()
    private var released = false

    companion object {
        private const val TAG = "AudioMix-Recovery"
        private const val MAX_RETRIES = 4
    }

    fun retry(key: String, action: () -> Unit) {
        if (released) return
        val attempt = (attempts[key] ?: 0) + 1
        attempts[key] = attempt
        if (attempt > MAX_RETRIES) {
            Log.w(TAG, "Retry limit reached for " + key)
            return
        }

        val delay = when (attempt) {
            1 -> 500L
            2 -> 1_000L
            3 -> 2_000L
            else -> 4_000L
        }

        Log.w(TAG, "Recovery " + key + " attempt=" + attempt + " delay=" + delay)
        handler.postDelayed({
            if (!released) action()
        }, delay)
    }

    fun reset(key: String) {
        attempts.remove(key)
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        attempts.clear()
    }
}
