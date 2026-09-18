package com.audiomix.iptv

import androidx.media3.exoplayer.DefaultLoadControl

class BufferManager {
    fun createVideoLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(25_000, 90_000, 5_000, 10_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(5_000, false)
            .build()
    }

    fun createAudioLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 30_000, 2_000, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(2_000, false)
            .build()
    }
}
