package com.audiomix.iptv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class PlaybackOrchestrator(context: Context) {
    private val videoPlayer = ExoPlayer.Builder(context).build()
    private val audioPlayer = ExoPlayer.Builder(context).build()
    private val sync = SyncController()

    init {
        audioPlayer.trackSelectionParameters =
            audioPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
    }

    fun playVideo(url: String) {
        videoPlayer.setMediaItem(MediaItem.fromUri(url))
        videoPlayer.prepare()
        videoPlayer.play()
    }

    fun playAudio(url: String) {
        audioPlayer.setMediaItem(MediaItem.fromUri(url))
        audioPlayer.prepare()
        audioPlayer.play()
    }

    fun synchronize(): DriftSample = sync.correct(videoPlayer, audioPlayer)
    fun release() { videoPlayer.release(); audioPlayer.release() }
}
