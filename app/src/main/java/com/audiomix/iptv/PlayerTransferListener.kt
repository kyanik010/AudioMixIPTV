package com.audiomix.iptv

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.util.concurrent.atomic.AtomicLong

class PlayerTransferListener(
    private val tag: String,
    private val bandwidthMeter: DefaultBandwidthMeter
) : TransferListener {

    private val totalBytes = AtomicLong(0L)
    private val windowBytes = AtomicLong(0L)
    @Volatile private var windowStartMs = SystemClock.elapsedRealtime()

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        bandwidthMeter.onTransferInitializing(source, dataSpec, isNetwork)
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        bandwidthMeter.onTransferStart(source, dataSpec, isNetwork)
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int
    ) {
        bandwidthMeter.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
        if (isNetwork) {
            totalBytes.addAndGet(bytesTransferred.toLong())
            windowBytes.addAndGet(bytesTransferred.toLong())
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        bandwidthMeter.onTransferEnd(source, dataSpec, isNetwork)
    }

    fun snapshot(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - windowStartMs).coerceAtLeast(1L)
        val bytes = windowBytes.getAndSet(0L)
        windowStartMs = now
        return Snapshot(
            totalBytes = totalBytes.get(),
            windowKbps = (bytes * 8_000L / elapsed).toInt()
        )
    }

    data class Snapshot(val totalBytes: Long, val windowKbps: Int)
}
