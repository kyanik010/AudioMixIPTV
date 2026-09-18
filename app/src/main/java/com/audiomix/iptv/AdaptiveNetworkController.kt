package com.audiomix.iptv

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.math.max

/**
 * Decides whether a destructive recovery is safe during live playback.
 *
 * Recovery is deliberately conservative: Media3 should be allowed to drain
 * existing buffer and recover naturally. A prepare() while useful video data
 * remains buffered can throw away the very buffer protecting playback.
 */
class AdaptiveNetworkController {

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

    fun evaluate(
        player: Player,
        isVideo: Boolean,
        bufferAheadMs: Long,
        network: NetworkMetrics.Snapshot
    ): Decision {
        val trackType = if (isVideo) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
        val bitrateKbps = player.currentTracks.groups
            .asSequence()
            .filter { it.type == trackType && it.isSelected }
            .flatMap { group ->
                (0 until group.length)
                    .asSequence()
                    .filter { group.isTrackSelected(it) }
                    .mapNotNull { group.getTrackFormat(it).bitrate.takeIf { b -> b > 0 } }
            }
            .firstOrNull()
            ?.div(1000L)
            ?: UNKNOWN

        val throughputKbps = network.averageThroughputKbps
            .takeIf { it > 0L }
            ?: network.lastThroughputKbps.takeIf { it > 0L }
            ?: UNKNOWN

        if (bufferAheadMs > 0L) {
            if (bitrateKbps != UNKNOWN && throughputKbps != UNKNOWN) {
                val requiredKbps = max(1L, (bitrateKbps * SAFETY_FACTOR).toLong())
                if (throughputKbps < requiredKbps) {
                    Log.w(
                        TAG,
                        "network underspeed type=" + (if (isVideo) "VIDEO" else "AUDIO") +
                            " throughput=" + throughputKbps + "kbps format=" + bitrateKbps +
                            "kbps required=" + requiredKbps + "kbps buffer=" + bufferAheadMs +
                            "ms; preserve buffer"
                    )
                    return Decision(
                        false,
                        bitrateKbps,
                        throughputKbps,
                        bufferAheadMs,
                        "throughput-below-format-bitrate"
                    )
                }
            }

            return Decision(
                false,
                bitrateKbps,
                throughputKbps,
                bufferAheadMs,
                "buffer-still-available"
            )
        }

        if (bitrateKbps == UNKNOWN || throughputKbps == UNKNOWN) {
            return Decision(
                true,
                bitrateKbps,
                throughputKbps,
                bufferAheadMs,
                "buffer-exhausted-insufficient-metrics"
            )
        }

        return Decision(
            true,
            bitrateKbps,
            throughputKbps,
            bufferAheadMs,
            "buffer-exhausted"
        )
    }
}
