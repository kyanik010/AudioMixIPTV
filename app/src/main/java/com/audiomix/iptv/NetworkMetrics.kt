package com.audiomix.iptv

import androidx.media3.exoplayer.source.LoadEventInfo
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Lightweight per-player network telemetry.
 *
 * It measures completed media loads only. It does not alter track selection,
 * buffering, or source quality.
 */
class NetworkMetrics {
    private val totalBytes = AtomicLong(0L)
    private val loadCount = AtomicLong(0L)

    @Volatile private var lastThroughputKbps = 0L
    @Volatile private var averageThroughputKbps = 0L
    @Volatile private var peakThroughputKbps = 0L
    @Volatile private var lastLoadDurationMs = 0L

    @Synchronized
    fun record(loadEventInfo: LoadEventInfo) {
        val bytes = loadEventInfo.bytesLoaded.coerceAtLeast(0L)
        val durationMs = loadEventInfo.loadDurationMs.coerceAtLeast(0L)
        totalBytes.addAndGet(bytes)
        loadCount.incrementAndGet()
        lastLoadDurationMs = durationMs

        if (bytes > 0L && durationMs > 0L) {
            val kbps = ((bytes * 8_000L) / durationMs).coerceAtLeast(1L)
            lastThroughputKbps = kbps
            peakThroughputKbps = max(peakThroughputKbps, kbps)

            val count = loadCount.get()
            averageThroughputKbps =
                if (count <= 1L) kbps
                else ((averageThroughputKbps * 3L) + kbps) / 4L
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        totalBytes = totalBytes.get(),
        loadCount = loadCount.get(),
        lastThroughputKbps = lastThroughputKbps,
        averageThroughputKbps = averageThroughputKbps,
        peakThroughputKbps = peakThroughputKbps,
        lastLoadDurationMs = lastLoadDurationMs
    )

    data class Snapshot(
        val totalBytes: Long,
        val loadCount: Long,
        val lastThroughputKbps: Long,
        val averageThroughputKbps: Long,
        val peakThroughputKbps: Long,
        val lastLoadDurationMs: Long
    )

    fun reset() {
        totalBytes.set(0L)
        loadCount.set(0L)
        lastThroughputKbps = 0L
        averageThroughputKbps = 0L
        peakThroughputKbps = 0L
        lastLoadDurationMs = 0L
    }
}
