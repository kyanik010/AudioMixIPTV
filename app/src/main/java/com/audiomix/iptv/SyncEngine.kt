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
    private var audioPlayer: ExoPlayer
) {
    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private var manualDelayMs = 0L
    private var lastHardCorrectionAt = 0L
    private var speedCorrectionUntil = 0L
    private var anchorVideoPositionMs = C.TIME_UNSET
    private var anchorAudioPositionMs = C.TIME_UNSET

    companion object {
        private const val TAG = "AudioMix-Sync"
        private const val SAMPLE_INTERVAL_MS = 2_500L
        private const val SOFT_THRESHOLD_MS = 180L
        private const val HARD_THRESHOLD_MS = 1_800L
        private const val FORCE_THRESHOLD_MS = 700L
        private const val HARD_COOLDOWN_MS = 8_000L
        private const val SPEED_WINDOW_MS = 1_500L
        private const val MIN_SPEED = 0.985f
        private const val MAX_SPEED = 1.015f
        private const val MAX_CORRECTION_SEEK_MS = 3_000L
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

    fun switchAudioPlayer(newPlayer: ExoPlayer) {
        if (released) return
        if (audioPlayer === newPlayer) return
        if (audioPlayer.playbackParameters.speed != 1f) audioPlayer.setPlaybackSpeed(1f)
        audioPlayer = newPlayer
        anchorVideoPositionMs = C.TIME_UNSET
        anchorAudioPositionMs = C.TIME_UNSET
        lastHardCorrectionAt = 0L
        speedCorrectionUntil = 0L
        synchronize(true)
    }

    private fun synchronize(force: Boolean) {
        if (released || !videoPlayer.isPlaying || !audioPlayer.isPlaying) return
        if (videoPlayer.playbackState != Player.STATE_READY ||
            audioPlayer.playbackState != Player.STATE_READY) return

        val videoOffset = videoPlayer.currentLiveOffset
        val audioOffset = audioPlayer.currentLiveOffset

        if (videoOffset != C.TIME_UNSET && audioOffset != C.TIME_UNSET) {
            val desiredAudioOffset = videoOffset + manualDelayMs
            // Positive drift means the audio is further behind the desired
            // position, so it must catch up.
            correctAudio(audioOffset - desiredAudioOffset, force)
            return
        }

        // Progressive Xtream .ts streams often do not expose a live offset.
        // Keep a relative playback anchor instead of giving up on sync.
        if (anchorVideoPositionMs == C.TIME_UNSET || anchorAudioPositionMs == C.TIME_UNSET) {
            anchorVideoPositionMs = videoPlayer.currentPosition
            anchorAudioPositionMs = audioPlayer.currentPosition
            Log.d(TAG, "sync-anchor video=" + anchorVideoPositionMs + " audio=" + anchorAudioPositionMs)
            return
        }

        val videoElapsed = videoPlayer.currentPosition - anchorVideoPositionMs
        val audioElapsed = audioPlayer.currentPosition - anchorAudioPositionMs
        val audioBehind = videoElapsed + manualDelayMs - audioElapsed
        correctAudio(audioBehind, force)
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
            // Never perform a large automatic seek on an independent live stream.
            // A seek can move audio to a different live segment and create a larger
            // discontinuity than the original drift. Normal sync uses speed correction.
            if (force && absolute <= MAX_CORRECTION_SEEK_MS) {
                val target = (audioPlayer.currentPosition + drift).coerceAtLeast(0L)
                Log.d(TAG, "forced-sync drift=${drift}ms target=${target} delay=${manualDelayMs}ms")
                audioPlayer.setPlaybackSpeed(1f)
                audioPlayer.seekTo(target)
                lastHardCorrectionAt = now
                speedCorrectionUntil = now + SPEED_WINDOW_MS
                return
            }

            val ratio = absolute.coerceAtMost(1_500L) / 1_500f
            val boundedSpeed = if (drift > 0L) {
                (1f + ratio * 0.015f).coerceIn(1f, MAX_SPEED)
            } else {
                (1f - ratio * 0.015f).coerceIn(MIN_SPEED, 1f)
            }
            audioPlayer.setPlaybackSpeed(boundedSpeed)
            speedCorrectionUntil = now + SPEED_WINDOW_MS
            lastHardCorrectionAt = now
            Log.d(TAG, "safe-sync drift=${drift}ms speed=${boundedSpeed}")
            return
        }

        if (now >= speedCorrectionUntil) {
            // Use proportional correction instead of jumping immediately to
            // +/-1.5%. Small live drift should sound continuous, not like a
            // repeated micro-stutter.
            val ratio = (absolute.coerceAtMost(1_500L) / 1_500f)
            val correction = (ratio * 0.015f).coerceIn(0.0015f, 0.015f)
            val speed = if (drift > 0L) {
                (1f + correction).coerceAtMost(MAX_SPEED)
            } else {
                (1f - correction).coerceAtLeast(MIN_SPEED)
            }
            audioPlayer.setPlaybackSpeed(speed)
            speedCorrectionUntil = now + SPEED_WINDOW_MS
            Log.d(TAG, "soft-sync drift=" + drift + "ms speed=" + speed)
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
