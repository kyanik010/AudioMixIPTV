package com.audiomix.iptv

import android.app.ActivityManager
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl

class BufferManager(context: Context) {
    data class Profile(
        val videoMinMs: Int, val videoMaxMs: Int, val videoPlaybackStartMs: Int, val videoRebufferMs: Int,
        val audioMinMs: Int, val audioMaxMs: Int, val audioPlaybackStartMs: Int, val audioRebufferMs: Int
    )

    private val profile: Profile = chooseProfile(context)

    fun createVideoLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(profile.videoMinMs, profile.videoMaxMs, profile.videoPlaybackStartMs, profile.videoRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(3_000, false)
            .build()

    fun createAudioLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(profile.audioMinMs, profile.audioMaxMs, profile.audioPlaybackStartMs, profile.audioRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(2_000, false)
            .build()

    fun starvationGraceMs(isVideo: Boolean, bufferAheadMs: Long): Long {
        val base = 5_000L
        return when {
            bufferAheadMs <= 250L -> base
            bufferAheadMs <= 750L -> base + 1_000L
            else -> base + 2_000L
        }
    }

    fun profileDescription(): String =
        "video=\${profile.videoMinMs}-\${profile.videoMaxMs}ms audio=\${profile.audioMinMs}-\${profile.audioMaxMs}ms"

    private fun chooseProfile(context: Context): Profile {
        val memoryClassMb = context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 256
        return when {
            memoryClassMb <= 192 -> Profile(12_000, 45_000, 3_000, 6_000, 10_000, 30_000, 3_000, 6_000)
            memoryClassMb <= 256 -> Profile(15_000, 60_000, 4_000, 7_000, 12_000, 35_000, 3_000, 7_000)
            else -> Profile(20_000, 75_000, 4_000, 8_000, 15_000, 45_000, 3_000, 8_000)
        }
    }
}