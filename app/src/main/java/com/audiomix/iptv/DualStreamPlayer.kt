package com.audiomix.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

class DualStreamPlayer(
    private val context: Context,
    initialVideoUrls: List<String>,
    initialAudioUrls: List<String>
) {
    private val bufferManager = BufferManager(context)
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
    private val adaptiveNetwork = AdaptiveNetworkController()
    private val networkContention = NetworkContentionController()
    private var audioNetworkYielding = false
    private val recovery = RecoverySystem()
    private val handler = Handler(Looper.getMainLooper())

    private var released = false
    private var videoGeneration = 0L
    private var audioGeneration = 0L
    private var audioErrorShown = false
    private var videoBufferingSince = 0L
    private var videoPreparedAtMs = 0L
    private var videoFirstFrameAtMs = 0L
    private var videoLastPositionMs = -1L
    private var videoLastProgressAtMs = 0L
    private var videoNoFrameRecoveryGeneration = -1L
    private var videoStallRecoveryGeneration = -1L
    private var playbackSessionActive = false
    private var videoUserPaused = false
    private var audioBufferingSince = 0L
    private var videoUrls = initialVideoUrls.distinct().filter { it.isNotBlank() }
    private var audioUrls = initialAudioUrls.distinct().filter { it.isNotBlank() }
    private var audioIndex = 0
    private var currentAudioUrl: String? = null

    private val videoPlayer: ExoPlayer
    private var audioPlayer: ExoPlayer
    private var standbyAudioPlayer: ExoPlayer? = null
    private val syncEngine: SyncEngine
    private val videoDiagnostics = PlaybackDiagnostics("VIDEO")
    private val audioDiagnostics = PlaybackDiagnostics("AUDIO")

    private val bufferWatchdog: Runnable = object : Runnable {
        override fun run() {
            if (released) return

            val now = android.os.SystemClock.elapsedRealtime()

            // Hostile-stream protection inspired by OwnTV: a provider can open without ever rendering a frame.
            if (playbackSessionActive && videoFirstFrameAtMs == 0L && videoPreparedAtMs > 0L &&
                now - videoPreparedAtMs >= NO_FRAME_TIMEOUT_MS && videoNoFrameRecoveryGeneration != videoGeneration) {
                videoNoFrameRecoveryGeneration = videoGeneration
                val generation = videoGeneration
                Log.w(TAG, "VIDEO_NO_FRAME generation=$generation elapsedMs=${now - videoPreparedAtMs}")
                recovery.retry("video-no-frame", generation, { videoGeneration == generation }) {
                    if (!released && videoGeneration == generation) {
                        videoPlayer.seekToDefaultPosition()
                        videoPlayer.prepare()
                        videoPlayer.playWhenReady = true
                        videoPlayer.play()
                    }
                }
            }

            // Catch a decoder/demuxer wedge where ExoPlayer stays READY and isPlaying=true.
            if (playbackSessionActive && videoPlayer.isPlaying && videoPlayer.playbackState == Player.STATE_READY) {
                val position = videoPlayer.currentPosition
                if (position != videoLastPositionMs) {
                    videoLastPositionMs = position
                    videoLastProgressAtMs = now
                    videoStallRecoveryGeneration = -1L
                } else if (videoLastProgressAtMs > 0L &&
                    now - videoLastProgressAtMs >= POSITION_STALL_TIMEOUT_MS &&
                    videoStallRecoveryGeneration != videoGeneration) {
                    videoStallRecoveryGeneration = videoGeneration
                    val generation = videoGeneration
                    Log.w(TAG, "VIDEO_POSITION_STALL generation=$generation stalledMs=${now - videoLastProgressAtMs}")
                    recovery.retry("video-position-stall", generation, { videoGeneration == generation }) {
                        if (!released && videoGeneration == generation) {
                            videoPlayer.prepare()
                            videoPlayer.playWhenReady = true
                            videoPlayer.play()
                        }
                    }
                }
            } else if (!videoPlayer.isPlaying) {
                videoLastProgressAtMs = 0L
            }

            val videoAheadMs = (videoPlayer.bufferedPosition - videoPlayer.currentPosition).coerceAtLeast(0L)
            if (videoPlayer.playbackState == Player.STATE_BUFFERING &&
                videoPlayer.isLoading &&
                videoAheadMs <= 1_500L
            ) {
                if (videoBufferingSince == 0L) videoBufferingSince = now
                val grace = bufferManager.starvationGraceMs(true, videoAheadMs)
                if (now - videoBufferingSince >= grace) {
                    val generation = videoGeneration
                    Log.w(
                        TAG,
                        "VIDEO_STARVATION generation=$generation aheadMs=$videoAheadMs graceMs=$grace " +
                            "profile=${bufferManager.profileDescription()}"
                    )
                    val decision = adaptiveNetwork.evaluate(videoPlayer, true, videoAheadMs, videoDiagnostics.networkSnapshot())
                    if (decision.allowRecovery) {
                        // Do not re-prepare a live player merely because its
                        // buffer is temporarily low. A prepare() tears down the
                        // current loading pipeline and is a common source of
                        // visible stutter on bursty IPTV feeds. Let ExoPlayer's
                        // LoadControl continue filling the existing timeline.
                        Log.d(TAG, "VIDEO_STARVATION keep-current-load reason=" + decision.reason)
                    } else {
                        Log.d(
                            TAG,
                            "VIDEO_STARVATION recovery deferred reason=" + decision.reason +
                                " throughputKbps=" + decision.throughputKbps +
                                " bitrateKbps=" + decision.bitrateKbps
                        )
                    }
                    videoBufferingSince = now
                }
            } else if (videoPlayer.playbackState != Player.STATE_BUFFERING) {
                videoBufferingSince = 0L
            }

            val audioAheadMs = (audioPlayer.bufferedPosition - audioPlayer.currentPosition).coerceAtLeast(0L)

            val contention = networkContention.update(
                videoPlayer = videoPlayer,
                audioPlayer = audioPlayer,
                videoBufferMs = videoAheadMs,
                audioBufferMs = audioAheadMs,
                videoNetwork = videoDiagnostics.networkSnapshot(),
                audioNetwork = audioDiagnostics.networkSnapshot()
            )
            if (contention.protectingVideo && !audioNetworkYielding) {
                audioNetworkYielding = true
                audioPlayer.playWhenReady = false
                audioPlayer.pause()
                Log.w(
                    TAG,
                    "NETWORK_PROTECTION yieldAudio=true reason=${contention.reason} " +
                        "videoBufferMs=${contention.videoBufferMs} videoBitrateKbps=${contention.videoBitrateKbps} " +
                        "videoThroughputKbps=${contention.videoThroughputKbps}"
                )
            } else if (!contention.protectingVideo && audioNetworkYielding) {
                audioNetworkYielding = false
                audioPlayer.playWhenReady = true
                audioPlayer.play()
                audioPlayer.play()
                Log.i(
                    TAG,
                    "NETWORK_PROTECTION yieldAudio=false videoBufferMs=${contention.videoBufferMs}"
                )
            }
            if (audioNetworkYielding) {
                audioBufferingSince = 0L
            } else if (audioPlayer.playbackState == Player.STATE_BUFFERING &&
                audioPlayer.isLoading &&
                audioAheadMs <= 1_000L
            ) {
                if (audioBufferingSince == 0L) audioBufferingSince = now
                val grace = bufferManager.starvationGraceMs(false, audioAheadMs)
                if (now - audioBufferingSince >= grace) {
                    val decision = adaptiveNetwork.evaluate(
                        audioPlayer,
                        false,
                        audioAheadMs,
                        audioDiagnostics.networkSnapshot()
                    )
                    if (decision.allowRecovery) {
                        val generation = audioGeneration
                        Log.w(
                            TAG,
                            "AUDIO_STARVATION generation=" + generation + " aheadMs=" + audioAheadMs + " graceMs=" + grace +
                                " profile=" + bufferManager.profileDescription()
                        )
                        // Audio starvation is not a reason to tear down the
                        // live audio timeline. Re-preparing here can create a
                        // second HTTP request and reset the live edge. Preserve
                        // the current player and let its existing loader recover.
                        Log.d(TAG, "AUDIO_STARVATION keep-current-load reason=" + decision.reason)
                    } else {
                        Log.d(
                            TAG,
                            "AUDIO_STARVATION recovery deferred reason=" + decision.reason +
                                " throughputKbps=" + decision.throughputKbps +
                                " bitrateKbps=" + decision.bitrateKbps
                        )
                    }
                    audioBufferingSince = now
                }
            } else if (audioPlayer.playbackState != Player.STATE_BUFFERING) {
                audioBufferingSince = 0L
            }

            if (!released) handler.postDelayed(this, 2_000L)
        }
    }

    init {
        require(videoUrls.isNotEmpty()) { "No video URL" }
        require(audioUrls.isNotEmpty()) { "No audio URL" }

        videoPlayer = buildVideoPlayer()
        audioPlayer = buildAudioPlayer()
        syncEngine = SyncEngine(videoPlayer, audioPlayer)

        videoDiagnostics.attach(videoPlayer)
        audioDiagnostics.attach(audioPlayer)

        installListeners()
        handler.post(bufferWatchdog)
        prepareVideo(videoUrls.first())
        prepareAudio(audioUrls.first())
    }

    private fun mediaSourceFactory(isAudio: Boolean): DefaultMediaSourceFactory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("AudioMix IPTV/1.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(bandwidthMeter)

        // Do not block a DataSource.read() with a monitor. That keeps a network
        // loader thread parked while the underlying HTTP call remains open and
        // can turn a temporary contention event into a socket stall.
        // ExoPlayer itself owns loading/pausing; contention control below only
        // yields the secondary player.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val retryPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(
                loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
            ): Long {
                return when (loadErrorInfo.errorCount) {
                    1 -> 250L
                    2 -> 750L
                    3 -> 1_500L
                    else -> 3_000L
                }
            }
        }

        return DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(retryPolicy)
    }

    private fun newRenderersFactory() =
        DefaultRenderersFactory(context).setEnableDecoderFallback(true)

    private fun buildVideoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setLoadControl(bufferManager.createVideoLoadControl())
            .setRenderersFactory(newRenderersFactory())
             .setMediaSourceFactory(mediaSourceFactory(isAudio = false))
            .setBandwidthMeter(bandwidthMeter)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { player ->
                player.trackSelectionParameters =
                    player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                player.volume = 0f
            }
    }

    private fun buildAudioPlayer(): ExoPlayer {
        val selector = DefaultTrackSelector(context)
        selector.setParameters(
            selector.buildUponParameters()
                .setConstrainAudioChannelCountToDeviceCapabilities(false)
                .setAudioOffloadPreferences(
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                        )
                        .build()
                )
                .build()
        )

        return ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .setLoadControl(bufferManager.createAudioLoadControl())
            .setRenderersFactory(newRenderersFactory())
            .setMediaSourceFactory(mediaSourceFactory(isAudio = true))
            .setBandwidthMeter(bandwidthMeter)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { player ->
                player.trackSelectionParameters =
                    player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .build()
                player.volume = 1f
                player.setSkipSilenceEnabled(false)

                val attrs = androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                player.setAudioAttributes(attrs, false)
            }
    }

    private fun installListeners() {
        videoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.d(
                    TAG,
                    "VIDEO state=" + stateName(state) +
                        " playing=" + videoPlayer.isPlaying +
                        " buffer=" + videoPlayer.bufferedPosition +
                        " position=" + videoPlayer.currentPosition
                )
                if (state == Player.STATE_READY) {
                    recovery.resetAllFor("video")
                    videoBufferingSince = 0L
                    if (videoLastProgressAtMs == 0L) {
                        videoLastPositionMs = videoPlayer.currentPosition
                        videoLastProgressAtMs = android.os.SystemClock.elapsedRealtime()
                    }

                    // A live stream can return to READY after a network stall while
                    // playWhenReady has been cleared by an internal recovery path.
                    // Resume automatically unless the user explicitly paused it.
                    if (playbackSessionActive && !videoUserPaused && !videoPlayer.isPlaying) {
                        videoPlayer.playWhenReady = true
                        videoPlayer.play()
                        Log.i(TAG, "VIDEO auto-resume after READY")
                    }
                }
                if (state != Player.STATE_BUFFERING) videoBufferingSince = 0L
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "VIDEO playing=" + isPlaying +
                    " buffer=" + videoPlayer.bufferedPosition)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.d(
                    TAG,
                    "VIDEO playWhenReady=" + playWhenReady +
                        " reason=" + reason +
                        " sessionActive=" + playbackSessionActive +
                        " userPaused=" + videoUserPaused
                )

                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    videoUserPaused = !playWhenReady
                }

                if (
                    playbackSessionActive &&
                    !videoUserPaused &&
                    playWhenReady
                ) {
                    videoPlayer.play()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "VIDEO error=" + error.errorCodeName, error)
                if (released) return

                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    recoverBehindLiveWindow(videoPlayer, "video")
                } else {
                    val generation = videoGeneration
                    recovery.retry("video", generation, { videoGeneration == generation }) {
                        if (!released && videoGeneration == generation) {
                            videoPlayer.prepare()
                            videoPlayer.playWhenReady = true
                            videoPlayer.play()
                        }
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                recovery.resetAllFor("video")
                videoBufferingSince = 0L
                videoFirstFrameAtMs = android.os.SystemClock.elapsedRealtime()
                videoNoFrameRecoveryGeneration = -1L
                videoStallRecoveryGeneration = -1L
                videoLastPositionMs = videoPlayer.currentPosition
                videoLastProgressAtMs = videoFirstFrameAtMs
                Log.i(TAG, "VIDEO first frame rendered generation=$videoGeneration")
            }
        })

        audioPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.d(
                    TAG,
                    "AUDIO state=" + stateName(state) +
                        " playing=" + audioPlayer.isPlaying +
                        " buffer=" + audioPlayer.bufferedPosition +
                        " position=" + audioPlayer.currentPosition +
                        " url=" + currentAudioUrl
                )
                if (state == Player.STATE_READY) {
                    recovery.resetAllFor("audio")
                    audioBufferingSince = 0L
                }
                if (state != Player.STATE_BUFFERING) audioBufferingSince = 0L
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "AUDIO playing=" + isPlaying +
                    " buffer=" + audioPlayer.bufferedPosition)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "AUDIO error=" + error.errorCodeName +
                    " url=" + currentAudioUrl, error)
                if (released) return

                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    recoverBehindLiveWindow(audioPlayer, "audio")
                    return
                }
                tryNextAudioCandidate(error.errorCodeName)
            }
        })
    }

    private fun recoverBehindLiveWindow(player: ExoPlayer, key: String) {
        val generation = if (key == "video") videoGeneration else audioGeneration
        recovery.retry(key + "-live", generation, { if (key == "video") videoGeneration == generation else audioGeneration == generation }) {
            if (released || (key == "video" && videoGeneration != generation) || (key == "audio" && audioGeneration != generation)) return@retry
            player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = true
            player.play()
        }
    }

    private fun prepareVideo(url: String) {
        videoFirstFrameAtMs = 0L
        videoPreparedAtMs = android.os.SystemClock.elapsedRealtime()
        videoLastPositionMs = -1L
        videoLastProgressAtMs = 0L
        videoNoFrameRecoveryGeneration = -1L
        videoStallRecoveryGeneration = -1L
        videoPlayer.setMediaItem(createMediaItem(url))
        videoPlayer.prepare()
        videoPlayer.playWhenReady = true
    }

    private fun prepareAudio(url: String) {
        currentAudioUrl = url
        audioPlayer.setMediaItem(createMediaItem(url))
        audioPlayer.prepare()
        audioPlayer.playWhenReady = true
    }

    private fun createMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val clean = url.substringBefore('?').lowercase()
        when {
            clean.endsWith(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            clean.endsWith(".ts") -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
            clean.endsWith(".aac") -> builder.setMimeType(MimeTypes.AUDIO_AAC)
            clean.endsWith(".mp3") -> builder.setMimeType(MimeTypes.AUDIO_MPEG)
        }
        return builder.build()
    }

    fun attachVideoView(playerView: PlayerView) {
        playerView.player = videoPlayer
    }

    fun play() {
        if (released) return
        playbackSessionActive = true
        videoUserPaused = false
        videoPlayer.volume = 0f
        audioPlayer.volume = 1f
        audioNetworkYielding = false
        videoPlayer.playWhenReady = true
        audioPlayer.playWhenReady = true
        videoPlayer.play()
        audioPlayer.play()
        syncEngine.start()
    }

    fun changeDelay(amountMs: Long) {
        syncEngine.setDelay(amountMs)
    }

    fun getDelayText(): String = syncEngine.delayText()

    fun forceSync() {
        syncEngine.forceSync()
    }

    fun switchVideo(newVideoUrls: List<String>): Boolean {
        if (released) return false
        val urls = newVideoUrls.distinct().filter { it.isNotBlank() }
        if (urls.isEmpty()) return false

        videoUrls = urls
        val generation = ++videoGeneration
        recovery.resetAllFor("video")
        return try {
            videoPlayer.stop()
            videoPlayer.clearMediaItems()
            prepareVideo(videoUrls.first())
            videoPlayer.play()
            handler.postDelayed({
                if (!released && videoGeneration == generation) syncEngine.forceSync()
            }, 1_500L)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Video switch failed", e)
            false
        }
    }

    fun switchAudio(newAudioUrls: List<String>): Boolean {
        if (released) return false
        val urls = newAudioUrls.distinct().filter { it.isNotBlank() }
        if (urls.isEmpty()) return false

        audioUrls = urls
        audioIndex = 0
        audioErrorShown = false
        val generation = ++audioGeneration
        recovery.resetAllFor("audio")

        return try {
            val oldPlayer = audioPlayer
            standbyAudioPlayer?.release()
            val nextPlayer = buildAudioPlayer()
            standbyAudioPlayer = nextPlayer

            var handedOff = false
            fun handoff() {
                if (released || handedOff || audioGeneration != generation || standbyAudioPlayer !== nextPlayer) return
                handedOff = true

                nextPlayer.volume = 0f
                nextPlayer.playWhenReady = true
                nextPlayer.play()

                audioPlayer = nextPlayer
                standbyAudioPlayer = null
                syncEngine.switchAudioPlayer(nextPlayer)

                val startVolume = oldPlayer.volume
                val steps = 6
                for (i in 1..steps) {
                    handler.postDelayed({
                        if (!released) {
                            nextPlayer.volume = (i.toFloat() / steps).coerceIn(0f, 1f)
                            oldPlayer.volume = (startVolume * (1f - i.toFloat() / steps)).coerceAtLeast(0f)
                        }
                    }, i * 50L)
                }

                handler.postDelayed({
                    oldPlayer.stop()
                    oldPlayer.release()
                }, (steps * 50L) + 150L)

                handler.postDelayed({
                    if (!released && audioGeneration == generation) syncEngine.forceSync()
                }, 900L)

                Log.d(TAG, "Audio source handoff completed generation=$generation")
            }

            nextPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        val ahead = (nextPlayer.bufferedPosition - nextPlayer.currentPosition).coerceAtLeast(0L)
                        if (ahead >= 750L) {
                            handoff()
                        } else {
                            handler.postDelayed({ handoff() }, 500L)
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (released || audioGeneration != generation) return
                    Log.e(TAG, "Standby audio failed: " + error.errorCodeName, error)
                    if (standbyAudioPlayer === nextPlayer) {
                        standbyAudioPlayer = null
                    }
                    nextPlayer.release()
                    Toast.makeText(context, "تعذر تشغيل مصدر الصوت. جرب قناة صوت أخرى.", Toast.LENGTH_LONG).show()
                }
            })

            currentAudioUrl = urls.first()
            nextPlayer.setMediaItem(createMediaItem(urls.first()))
            nextPlayer.prepare()
            nextPlayer.playWhenReady = true

            true
        } catch (e: Exception) {
            Log.e(TAG, "Audio seamless switch failed", e)
            standbyAudioPlayer = null
            false
        }
    }

    private fun tryNextAudioCandidate(reason: String) {
        if (released) return
        val next = audioIndex + 1
        if (next < audioUrls.size) {
            audioIndex = next
            val generation = ++audioGeneration
            recovery.resetAllFor("audio")
            Log.w(TAG, "Audio candidate failed: " + reason + "; generation=" + generation + "; trying #" + audioIndex)
            audioPlayer.stop()
            audioPlayer.clearMediaItems()
            prepareAudio(audioUrls[audioIndex])
            audioPlayer.play()
            return
        }

        if (!audioErrorShown) {
            audioErrorShown = true
            Toast.makeText(
                context,
                "تعذر تشغيل مصدر الصوت. جرب قناة صوت أخرى.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stateName(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> state.toString()
        }
    }

    fun release() {
        if (released) return
        released = true
        playbackSessionActive = false
        handler.removeCallbacksAndMessages(null)
        syncEngine.release()
        recovery.release()
        videoDiagnostics.release()
        audioDiagnostics.release()
        videoPlayer.stop()
        audioPlayer.stop()
        standbyAudioPlayer?.stop()
        videoPlayer.release()
        audioPlayer.release()
        standbyAudioPlayer?.release()
        standbyAudioPlayer = null
    }

    companion object {
        private const val TAG = "AudioMix-Core"
        private const val NO_FRAME_TIMEOUT_MS = 12_000L
        private const val POSITION_STALL_TIMEOUT_MS = 12_000L
    }
}
