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
    private var lastHardCorrectionAt = 0L
    private var speedCorrectionUntil = 0L

    companion object {
        private const val TAG = "AudioMix-Sync"
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val SOFT_THRESHOLD_MS = 120L
        private const val HARD_THRESHOLD_MS = 1_800L
        private const val FORCE_THRESHOLD_MS = 700L
        private const val HARD_COOLDOWN_MS = 8_000L
        private const val SPEED_WINDOW_MS = 1_500L
        private const val MIN_SPEED = 0.985f
        private const val MAX_SPEED = 1.015f
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
        handler.post(monitor)
    }

    fun setDelay(deltaMs: Long) {
        manualDelayMs = (manualDelayMs + deltaMs).coerceIn(-5_000L, 5_000L)
        synchronize(true)
    }

    fun delayText(): String = String.format("%.1fs", manualDelayMs / 1000.0)

    fun forceSync() { synchronize(true) }

    private fun synchronize(force: Boolean) {
        if (released || !videoPlayer.isPlaying || !audioPlayer.isPlaying) return
        if (videoPlayer.playbackState != Player.STATE_READY ||
            audioPlayer.playbackState != Player.STATE_READY) return

        val videoOffset = videoPlayer.currentLiveOffset
        val audioOffset = audioPlayer.currentLiveOffset
        if (videoOffset == C.TIME_UNSET || audioOffset == C.TIME_UNSET) {
            restoreAudioSpeed()
            return
        }

        val desiredAudioOffset = videoOffset + manualDelayMs
        correctAudio(audioOffset - desiredAudioOffset, force)
    }

    private fun correctAudio(drift: Long, force: Boolean) {
        val absolute = abs(drift)
        val now = SystemClock.elapsedRealtime()

        if (absolute < SOFT_THRESHOLD_MS) {
            restoreAudioSpeed()
            return
        }

        val hardThreshold = if (force) FORCE_THRESHOLD_MS else HARD_THRESHOLD_MS
        if (absolute >= hardThreshold && now - lastHardCorrectionAt >= HARD_COOLDOWN_MS) {
            val target = (audioPlayer.currentPosition + drift).coerceAtLeast(0L)
            Log.d(TAG, "hard-sync drift=\${drift}ms target=\${target} delay=\${manualDelayMs}ms")
            audioPlayer.setPlaybackSpeed(1f)
            audioPlayer.seekTo(target)
            lastHardCorrectionAt = now
            speedCorrectionUntil = now + SPEED_WINDOW_MS
            return
        }

        if (now >= speedCorrectionUntil) {
            val speed = if (drift > 0L) MAX_SPEED else MIN_SPEED
            audioPlayer.setPlaybackSpeed(speed)
            speedCorrectionUntil = now + SPEED_WINDOW_MS
            Log.d(TAG, "soft-sync drift=\${drift}ms speed=\${speed}")
        }
    }

    private fun restoreAudioSpeed() {
        if (audioPlayer.playbackParameters.speed != 1f) audioPlayer.setPlaybackSpeed(1f)
        speedCorrectionUntil = 0L
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        if (audioPlayer.playbackParameters.speed != 1f) audioPlayer.setPlaybackSpeed(1f)
    }
}
