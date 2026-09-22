package com.audiomix.iptv

import androidx.media3.common.Player
import kotlin.math.abs

class SyncController(
    private val normalThresholdMs: Long = 120,
    private val softThresholdMs: Long = 250,
    private val hardThresholdMs: Long = 750
) {
    fun correct(video: Player, audio: Player): DriftSample {
        val sample = DriftSample(audio.currentPosition, video.currentPosition)
        val drift = sample.driftMs
        when {
            abs(drift) <= normalThresholdMs -> audio.setPlaybackSpeed(1f)
            abs(drift) <= softThresholdMs -> audio.setPlaybackSpeed(if (drift > 0) 0.995f else 1.005f)
            abs(drift) >= hardThresholdMs -> {
                audio.setPlaybackSpeed(1f)
                audio.seekTo(video.currentPosition)
            }
            else -> audio.setPlaybackSpeed(if (drift > 0) 0.995f else 1.005f)
        }
        return sample
    }
}
