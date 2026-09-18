package com.audiomix.iptv

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Gates reads from a secondary stream without tearing down its ExoPlayer.
 *
 * When the audio gate is closed, the loader thread waits before reading more
 * bytes. This is different from playWhenReady=false: it actually stops the
 * audio DataSource from consuming network bandwidth while video is under
 * pressure, while keeping the audio player/state alive for quick resume.
 */
class NetworkGateDataSource(
    private val upstream: DataSource,
    private val gate: NetworkGate
) : DataSource {

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        gate.awaitAllowed()
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}

class NetworkGate {
    @Volatile
    private var blocked = false

    @Synchronized
    fun setBlocked(value: Boolean) {
        blocked = value
        if (!value) {
            notifyAll()
        }
    }

    fun isBlocked(): Boolean = blocked

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun awaitAllowed() {
        while (blocked) {
            try {
                synchronized(this) {
                    if (!blocked) return
                    (this as java.lang.Object).wait(250L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
