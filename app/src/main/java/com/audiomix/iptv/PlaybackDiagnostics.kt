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
import java.util.concurrent.atomic.AtomicLong

class PlaybackDiagnostics(private val tag: String) : AnalyticsListener {
    private var player: ExoPlayer? = null
    private var lastState = Player.STATE_IDLE
    private var lastLoading = false
    private var bufferingSinceMs = 0L
    private var totalBufferingMs = 0L
    private val bytesLoaded = AtomicLong(0L)
    private var lastReportedBytes = 0L
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
                logSnapshot("state=\${stateName(playbackState)}")
            }
            override fun onIsLoadingChanged(isLoading: Boolean) {
                lastLoading = isLoading
                logSnapshot("loading=\${isLoading}")
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "\${tag} error=\${error.errorCodeName} message=\${error.message}", error)
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                Log.d(TAG, "\${tag} video=\${videoSize.width}x\${videoSize.height} fps=\${videoSize.frameRate}")
            }
            override fun onRenderedFirstFrame() { logSnapshot("first-frame") }
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

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        val total = bytesLoaded.addAndGet(loadEventInfo.bytesLoaded)
        if (total - lastReportedBytes >= 5L * 1024L * 1024L) {
            lastReportedBytes = total
            logSnapshot("loadedBytes=\${total}")
        }
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long
    ) {
        Log.w(TAG, "\${tag} droppedFrames=\${droppedFrames} elapsedMs=\${elapsedMs}")
    }

    private fun logSnapshot(reason: String) {
        val p = player ?: return
        val bufferMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        Log.d(TAG, "\${tag} reason=\${reason} state=\${stateName(lastState)} " +
            "playing=\${p.isPlaying} loading=\${lastLoading} position=\${p.currentPosition} " +
            "buffered=\${p.bufferedPosition} bufferMs=\${bufferMs} liveOffset=\${p.currentLiveOffset} " +
            "totalBufferingMs=\${totalBufferingMs} bytes=\${bytesLoaded.get()}")
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    fun release() {
        periodic = false
        handler.removeCallbacks(periodicLogger)
        player?.removeAnalyticsListener(this)
        player = null
    }

    companion object { private const val TAG = "AudioMix-Diagnostics" }
}
