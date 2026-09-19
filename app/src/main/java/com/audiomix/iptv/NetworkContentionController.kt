package com.audiomix.iptv

import androidx.media3.common.C
import androidx.media3.common.Player

class NetworkContentionController {
    companion object { private const val VIDEO_CRITICAL_BUFFER_MS = 1_500L }

    data class State(
        val protectingVideo: Boolean, val reason: String,
        val videoBufferMs: Long, val videoBitrateKbps: Long, val videoThroughputKbps: Long,
        val audioBitrateKbps: Long, val audioThroughputKbps: Long
    )

    fun update(
        videoPlayer: Player, audioPlayer: Player, videoBufferMs: Long, audioBufferMs: Long,
        videoNetwork: NetworkMetrics.Snapshot, audioNetwork: NetworkMetrics.Snapshot
    ): State {
        val videoBitrate = selectedBitrateKbps(videoPlayer, C.TRACK_TYPE_VIDEO)
        val audioBitrate = selectedBitrateKbps(audioPlayer, C.TRACK_TYPE_AUDIO)
        val videoThroughput = effectiveThroughput(videoNetwork)
        val audioThroughput = effectiveThroughput(audioNetwork)
        val videoCritical = videoBufferMs <= VIDEO_CRITICAL_BUFFER_MS

        return State(
            protectingVideo = videoCritical,
            reason = if (videoCritical) "video-buffer-critical-no-audio-pause" else "normal",
            videoBufferMs = videoBufferMs,
            videoBitrateKbps = videoBitrate,
            videoThroughputKbps = videoThroughput,
            audioBitrateKbps = audioBitrate,
            audioThroughputKbps = audioThroughput
        )
    }

    private fun effectiveThroughput(network: NetworkMetrics.Snapshot): Long =
        network.averageThroughputKbps.takeIf { it > 0L } ?: network.lastThroughputKbps

    private fun selectedBitrateKbps(player: Player, trackType: Int): Long =
        player.currentTracks.groups.asSequence()
            .filter { it.type == trackType && it.isSelected }
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter { group.isTrackSelected(it) }
                    .mapNotNull { group.getTrackFormat(it).bitrate.takeIf { bitrate -> bitrate > 0 } }
            }
            .firstOrNull()?.div(1000L) ?: -1L
}