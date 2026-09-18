package com.audiomix.iptv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

class PlaybackDiagnostics(private val tag: String) : AnalyticsListener {
    private var player: ExoPlayer? = null
    private var lastState = Player.STATE_IDLE
    private var lastLoading = false
    private var bufferingSinceMs = 0L
    private var totalBufferingMs = 0L
    private var droppedFramesTotal = 0L
    private var firstFrameAtMs = 0L
    private val networkMetrics = NetworkMetrics()
    private val handler = Handler(Looper.getMainLooper())
    private var periodic = false

    fun attach(target: ExoPlayer) {
        player = target
        target.addAnalyticsListener(this)
        target.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                lastState = playbackState
                val now = SystemClock.elapsedRealtime()
                if (playbackState == Player.STATE_BUFFERING) {
                    if (bufferingSinceMs == 0L) bufferingSinceMs = now
                } else if (bufferingSinceMs != 0L) {
                    totalBufferingMs += now - bufferingSinceMs
                    bufferingSinceMs = 0L
                }
                logSnapshot("state=${stateName(playbackState)}")
            }
            override fun onIsLoadingChanged(isLoading: Boolean) {
                lastLoading = isLoading
                logSnapshot("loading=$isLoading")
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "$tag error=${error.errorCodeName} message=${error.message}", error)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                Log.d(TAG, "$tag video=${videoSize.width}x${videoSize.height} pixelRatio=${videoSize.pixelWidthHeightRatio}")
            }
            override fun onRenderedFirstFrame() {
                if (firstFrameAtMs == 0L) firstFrameAtMs = SystemClock.elapsedRealtime()
                logSnapshot("first-frame")
            }
        })
        periodic = true
        handler.post(periodicLogger)
    }

    private val periodicLogger = object : Runnable {
        override fun run() {
            if (!periodic) return
            logSnapshot("tick")
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData
    ) {
        val format = mediaLoadData.trackFormat ?: return
        val kind = when (mediaLoadData.trackType) {
            androidx.media3.common.C.TRACK_TYPE_VIDEO -> "VIDEO"
            androidx.media3.common.C.TRACK_TYPE_AUDIO -> "AUDIO"
            else -> "TRACK"
        }
        Log.d(
            TAG,
            "$tag format=$kind mime=${format.sampleMimeType} " +
                "bitrate=${format.bitrate}bps width=${format.width} height=${format.height} " +
                "fps=${format.frameRate} audioRate=${format.sampleRate} channels=${format.channelCount} " +
                "codecs=${format.codecs}"
        )
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        networkMetrics.record(loadEventInfo)
        val snapshot = networkMetrics.snapshot()
        if (snapshot.loadCount == 1L || snapshot.loadCount % 5L == 0L) {
            logSnapshot("network throughputKbps=${snapshot.lastThroughputKbps} avgKbps=${snapshot.averageThroughputKbps} peakKbps=${snapshot.peakThroughputKbps} loadMs=${snapshot.lastLoadDurationMs}")
        }
    }

    override fun onDecoderCountersUpdated(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: androidx.media3.decoder.DecoderCounters
    ) {
        decoderCounters.ensureUpdated()
        Log.d(
            TAG,
            "$tag decoder rendered=${decoderCounters.renderedOutputBufferCount} " +
                "skipped=${decoderCounters.skippedOutputBufferCount} " +
                "dropped=${decoderCounters.droppedBufferCount} " +
                "maxConsecutiveDropped=${decoderCounters.maxConsecutiveDroppedBufferCount} " +
                "input=${decoderCounters.inputBufferCount}"
        )
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        Log.w(
            TAG,
            "$tag AUDIO_UNDERRUN bufferSize=$bufferSize bufferMs=$bufferSizeMs " +
                "elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs"
        )
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long
    ) {
        droppedFramesTotal += droppedFrames.toLong()
        Log.w(TAG, "$tag droppedFrames=$droppedFrames totalDropped=$droppedFramesTotal elapsedMs=$elapsedMs")
    }

    private fun logSnapshot(reason: String) {
        val p = player ?: return
        val bufferMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        val network = networkMetrics.snapshot()
        Log.d(TAG, "$tag reason=$reason state=${stateName(lastState)} playing=${p.isPlaying} loading=$lastLoading position=${p.currentPosition} buffered=${p.bufferedPosition} bufferMs=$bufferMs liveOffset=${p.currentLiveOffset} totalBufferingMs=$totalBufferingMs bytes=${network.totalBytes} avgKbps=${network.averageThroughputKbps} lastKbps=${network.lastThroughputKbps} peakKbps=${network.peakThroughputKbps} loads=${network.loadCount} droppedFrames=$droppedFramesTotal firstFrameAt=$firstFrameAtMs")
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    fun networkSnapshot(): NetworkMetrics.Snapshot = networkMetrics.snapshot()

    fun release() {
        periodic = false
        handler.removeCallbacks(periodicLogger)
        player?.removeAnalyticsListener(this)
        player = null
    }

    companion object {
        private const val TAG = "AudioMix-Diagnostics"
    }
}
