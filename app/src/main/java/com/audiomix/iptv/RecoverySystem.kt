package com.audiomix.iptv

import android.os.Handler
import android.os.Looper
import android.util.Log

class RecoverySystem {
    private val handler = Handler(Looper.getMainLooper())
    private val attempts = mutableMapOf<String, Int>()
    private val pending = mutableSetOf<String>()
    private var released = false

    companion object {
        private const val TAG = "AudioMix-Recovery"
        private const val MAX_RETRIES = 4
    }

    fun retry(
        key: String,
        generation: Long = 0L,
        isGenerationValid: () -> Boolean = { true },
        action: () -> Unit
    ) {
        if (released || !isGenerationValid() || pending.contains(key)) return

        val attempt = (attempts[key] ?: 0) + 1
        attempts[key] = attempt
        if (attempt > MAX_RETRIES) {
            Log.w(TAG, "Retry limit reached for $key generation=$generation")
            return
        }

        val delay = when (attempt) {
            1 -> 500L
            2 -> 1_000L
            3 -> 2_000L
            else -> 4_000L
        }

        Log.w(TAG, "Recovery $key generation=$generation attempt=$attempt delay=$delay")
        pending.add(key)
        handler.postDelayed({
            pending.remove(key)
            if (!released && isGenerationValid()) {
                action()
            } else {
                Log.d(TAG, "Ignored stale recovery key=$key generation=$generation")
            }
        }, delay)
    }

    fun reset(key: String) {
        attempts.remove(key)
        pending.remove(key)
    }

    fun resetAllFor(keyPrefix: String) {
        attempts.keys.filter { it == keyPrefix || it.startsWith("$keyPrefix-") }
            .toList()
            .forEach { attempts.remove(it); pending.remove(it) }
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        attempts.clear()
        pending.clear()
    }
}
