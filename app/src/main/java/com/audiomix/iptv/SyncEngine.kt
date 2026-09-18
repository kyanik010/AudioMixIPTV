package com.audiomix.iptv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

class SyncEngine(
    private val videoPlayer: ExoPlayer,
    private val audioPlayer: ExoPlayer
) {
    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private var manualDelayMs = 0L
    private var lastCorrectionAt = 0L

    companion object {
        private const val TAG = "AudioMix-Sync"
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val CORRECTION_THRESHOLD_MS = 1_500L
        private const val FORCE_THRESHOLD_MS = 700L
        private const val CORRECTION_COOLDOWN_MS = 4_000L
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (released) return
            synchronize(false)
            if (!released) handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    fun start() {
        if (released) return
        handler.removeCallbacks(monitor)
        handler.postDelayed(monitor, 2_500L)
    }

    fun setDelay(deltaMs: Long) {
        manualDelayMs = (manualDelayMs + deltaMs).coerceIn(-5_000L, 5_000L)
        synchronize(true)
    }

    fun delayText(): String = String.format("%.1fs", manualDelayMs / 1000.0)

    fun forceSync() {
        synchronize(true)
    }

    private fun synchronize(force: Boolean) {
        if (released) return
        if (!videoPlayer.isPlaying || !audioPlayer.isPlaying) return
        if (videoPlayer.playbackState != Player.STATE_READY ||
            audioPlayer.playbackState != Player.STATE_READY
        ) return

        val videoOffset = videoPlayer.currentLiveOffset
        val audioOffset = audioPlayer.currentLiveOffset

        if (videoOffset != C.TIME_UNSET && audioOffset != C.TIME_UNSET) {
            val desiredAudioOffset = videoOffset + manualDelayMs
            correctAudio(audioOffset - desiredAudioOffset, force)
        } else {
            val difference = audioPlayer.currentPosition -
                videoPlayer.currentPosition - manualDelayMs
            correctAudio(difference, force)
        }
    }

    private fun correctAudio(difference: Long, force: Boolean) {
        val absolute = abs(difference)
        if (!force && absolute < CORRECTION_THRESHOLD_MS) return

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCorrectionAt < CORRECTION_COOLDOWN_MS) return
        if (force && absolute < FORCE_THRESHOLD_MS) return

        val target = audioPlayer.currentPosition + difference
        if (target < 0L) return

        Log.d(TAG, "drift=" + difference + "ms target=" + target + " delay=" + manualDelayMs)
        audioPlayer.seekTo(target)
        lastCorrectionAt = now
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
    }
}
