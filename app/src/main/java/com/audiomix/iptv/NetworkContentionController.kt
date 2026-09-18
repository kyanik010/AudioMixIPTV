package com.audiomix.iptv

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.math.max

/**
 * Protects the primary video stream when two independent live streams
 * compete for the same network connection.
 *
 * This never forces a video resolution. It only uses the already-selected
 * format bitrate and current buffers to decide whether the secondary audio
 * stream must temporarily yield network loading.
 */
class NetworkContentionController {

    companion object {
        private const val VIDEO_CRITICAL_BUFFER_MS = 1_500L
        private const val VIDEO_RECOVERY_BUFFER_MS = 5_000L
        private const val MIN_VIDEO_REQUIRED_KBPS = 500L
        private const val SAFETY_FACTOR = 1.15
    }

    data class State(
        val protectingVideo: Boolean,
        val reason: String,
        val videoBufferMs: Long,
        val videoBitrateKbps: Long,
        val videoThroughputKbps: Long,
        val audioBitrateKbps: Long,
        val audioThroughputKbps: Long
    )

    private var protectingVideo = false

    fun update(
        videoPlayer: Player,
        audioPlayer: Player,
        videoBufferMs: Long,
        audioBufferMs: Long,
        videoNetwork: NetworkMetrics.Snapshot,
        audioNetwork: NetworkMetrics.Snapshot
    ): State {
        val videoBitrate = selectedBitrateKbps(videoPlayer, C.TRACK_TYPE_VIDEO)
        val audioBitrate = selectedBitrateKbps(audioPlayer, C.TRACK_TYPE_AUDIO)

        val videoThroughput = effectiveThroughput(videoNetwork)
        val audioThroughput = effectiveThroughput(audioNetwork)

        val videoNeedsProtection =
            videoBufferMs <= VIDEO_CRITICAL_BUFFER_MS &&
                videoBitrate > 0L &&
                videoThroughput > 0L &&
                videoThroughput < max(
                    MIN_VIDEO_REQUIRED_KBPS,
                    (videoBitrate * SAFETY_FACTOR).toLong()
                )

        if (!protectingVideo && videoNeedsProtection) {
            protectingVideo = true
        } else if (protectingVideo && videoBufferMs >= VIDEO_RECOVERY_BUFFER_MS) {
            protectingVideo = false
        }

        return State(
            protectingVideo = protectingVideo,
            reason = when {
                protectingVideo -> "video-buffer-protection"
                videoBufferMs <= VIDEO_CRITICAL_BUFFER_MS && videoThroughput <= 0L ->
                    "video-network-metrics-pending"
                else -> "normal"
            },
            videoBufferMs = videoBufferMs,
            videoBitrateKbps = videoBitrate,
            videoThroughputKbps = videoThroughput,
            audioBitrateKbps = audioBitrate,
            audioThroughputKbps = audioThroughput
        )
    }

    private fun effectiveThroughput(network: NetworkMetrics.Snapshot): Long =
        network.averageThroughputKbps
            .takeIf { it > 0L }
            ?: network.lastThroughputKbps

    private fun selectedBitrateKbps(player: Player, trackType: Int): Long =
        player.currentTracks.groups
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
            ?: -1L
}
