package com.audiomix.iptv

import androidx.media3.exoplayer.DefaultLoadControl

class BufferManager {
    fun createLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 90_000, 4_000, 8_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(15_000, false)
            .build()
    }
}
