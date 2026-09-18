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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
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
    private val bufferManager = BufferManager()
    private val recovery = RecoverySystem()
    private val handler = Handler(Looper.getMainLooper())

    private var released = false
    private var audioErrorShown = false
    private var videoUrls = initialVideoUrls.distinct().filter { it.isNotBlank() }
    private var audioUrls = initialAudioUrls.distinct().filter { it.isNotBlank() }
    private var audioIndex = 0
    private var currentAudioUrl: String? = null

    private val videoPlayer: ExoPlayer
    private val audioPlayer: ExoPlayer
    private val syncEngine: SyncEngine

    init {
        require(videoUrls.isNotEmpty()) { "No video URL" }
        require(audioUrls.isNotEmpty()) { "No audio URL" }

        videoPlayer = buildVideoPlayer()
        audioPlayer = buildAudioPlayer()
        syncEngine = SyncEngine(videoPlayer, audioPlayer)

        installListeners()
        prepareVideo(videoUrls.first())
        prepareAudio(audioUrls.first())
    }

    private fun mediaSourceFactory(): DefaultMediaSourceFactory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("AudioMix IPTV/1.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

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
            .setLoadControl(bufferManager.createLoadControl())
            .setRenderersFactory(newRenderersFactory())
            .setMediaSourceFactory(mediaSourceFactory())
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
            .setLoadControl(bufferManager.createLoadControl())
            .setRenderersFactory(newRenderersFactory())
            .setMediaSourceFactory(mediaSourceFactory())
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
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "VIDEO playing=" + isPlaying +
                    " buffer=" + videoPlayer.bufferedPosition)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "VIDEO error=" + error.errorCodeName, error)
                if (released) return

                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    recoverBehindLiveWindow(videoPlayer, "video")
                } else {
                    recovery.retry("video") {
                        if (!released) {
                            videoPlayer.prepare()
                            videoPlayer.playWhenReady = true
                            videoPlayer.play()
                        }
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                recovery.reset("video")
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
                if (state == Player.STATE_READY) recovery.reset("audio")
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
        recovery.retry(key + "-live") {
            if (released) return@retry
            player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = true
            player.play()
        }
    }

    private fun prepareVideo(url: String) {
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
        videoPlayer.volume = 0f
        audioPlayer.volume = 1f
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
        return try {
            videoPlayer.stop()
            videoPlayer.clearMediaItems()
            prepareVideo(videoUrls.first())
            videoPlayer.play()
            handler.postDelayed({
                if (!released) syncEngine.forceSync()
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
        recovery.reset("audio")

        return try {
            audioPlayer.stop()
            audioPlayer.clearMediaItems()
            prepareAudio(audioUrls.first())
            audioPlayer.play()
            handler.postDelayed({
                if (!released) syncEngine.forceSync()
            }, 1_500L)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Audio switch failed", e)
            false
        }
    }

    private fun tryNextAudioCandidate(reason: String) {
        if (released) return
        val next = audioIndex + 1
        if (next < audioUrls.size) {
            audioIndex = next
            Log.w(TAG, "Audio candidate failed: " + reason + "; trying #" + audioIndex)
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
        handler.removeCallbacksAndMessages(null)
        syncEngine.release()
        recovery.release()
        videoPlayer.stop()
        audioPlayer.stop()
        videoPlayer.release()
        audioPlayer.release()
    }

    companion object {
        private const val TAG = "AudioMix-Core"
    }
}
