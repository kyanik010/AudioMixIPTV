package com.audiomix.iptv

import androidx.media3.exoplayer.DefaultLoadControl

class BufferManager {
    fun createLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            // A larger live buffer helps absorb short stalls while avoiding
            // the very large memory footprint of an aggressive 120s buffer.
            .setBufferDurationsMs(
                25_000,
                90_000,
                5_000,
                10_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(5_000, false)
            .build()
    }
}
