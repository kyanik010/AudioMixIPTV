package com.audiomix.iptv

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlin.math.max

class AdaptiveNetworkController(
    private val bandwidthMeter: DefaultBandwidthMeter
) {
    companion object {
        private const val TAG = "AudioMix-Adaptive"
        private const val SAFETY_FACTOR = 1.15
        private const val UNKNOWN = -1L
    }

    data class Decision(
        val allowRecovery: Boolean,
        val bitrateKbps: Long,
        val throughputKbps: Long,
        val bufferAheadMs: Long,
        val reason: String
    )

    fun evaluate(player: Player, isVideo: Boolean, bufferAheadMs: Long): Decision {
        val format = if (isVideo) player.videoFormat else player.audioFormat
        val bitrateKbps = format?.bitrate?.takeIf { it > 0 }?.div(1000L) ?: UNKNOWN
        val throughputKbps = bandwidthMeter.bitrateEstimate
            .takeIf { it > 0L }
            ?.div(1000L) ?: UNKNOWN

        if (bitrateKbps == UNKNOWN || throughputKbps == UNKNOWN) {
            return Decision(true, bitrateKbps, throughputKbps, bufferAheadMs, "insufficient-metrics")
        }

        val requiredKbps = max(1L, (bitrateKbps * SAFETY_FACTOR).toLong())
        val underspeed = throughputKbps < requiredKbps

        if (isVideo && underspeed && bufferAheadMs > 0L) {
            Log.w(TAG, "VIDEO network underspeed: throughput=" + throughputKbps + "kbps format=" + bitrateKbps + "kbps required=" + requiredKbps + "kbps buffer=" + bufferAheadMs + "ms; keep player alive for adaptive selection")
            return Decision(false, bitrateKbps, throughputKbps, bufferAheadMs, "throughput-below-format-bitrate")
        }

        return Decision(true, bitrateKbps, throughputKbps, bufferAheadMs, "recovery-allowed")
    }
}