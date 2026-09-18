package com.audiomix.iptv

import android.app.ActivityManager
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Device-aware playback buffering policy.
 *
 * It never forces a video resolution. The channel/manifest remains responsible
 * for selecting 4K/1080p/720p/SD.
 */
class BufferManager(context: Context) {

    data class Profile(
        val videoMinMs: Int,
        val videoMaxMs: Int,
        val videoPlaybackStartMs: Int,
        val videoRebufferMs: Int,
        val audioMinMs: Int,
        val audioMaxMs: Int,
        val audioPlaybackStartMs: Int,
        val audioRebufferMs: Int
    )

    private val profile: Profile = chooseProfile(context)

    fun createVideoLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.videoMinMs,
                profile.videoMaxMs,
                profile.videoPlaybackStartMs,
                profile.videoRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(3_000, false)
            .build()

    fun createAudioLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.audioMinMs,
                profile.audioMaxMs,
                profile.audioPlaybackStartMs,
                profile.audioRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(1_000, false)
            .build()

    fun starvationGraceMs(isVideo: Boolean, bufferAheadMs: Long): Long {
        val base = if (isVideo) 4_000L else 3_000L
        return when {
            bufferAheadMs <= 250L -> base
            bufferAheadMs <= 750L -> base + 1_000L
            else -> base + 2_000L
        }
    }

    fun profileDescription(): String =
        "video=${profile.videoMinMs}-${profile.videoMaxMs}ms " +
            "audio=${profile.audioMinMs}-${profile.audioMaxMs}ms"

    private fun chooseProfile(context: Context): Profile {
        val memoryClassMb = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 256
        return when {
            memoryClassMb <= 192 -> Profile(
                10_000, 35_000, 2_500, 5_000,
                4_000, 10_000, 1_500, 3_000
            )
            memoryClassMb <= 256 -> Profile(
                12_000, 45_000, 3_000, 6_000,
                5_000, 12_000, 1_500, 3_500
            )
            else -> Profile(
                15_000, 50_000, 3_000, 7_000,
                6_000, 14_000, 2_000, 4_000
            )
        }
    }
}
