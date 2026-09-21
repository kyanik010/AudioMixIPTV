package com.audiomix.iptv

import android.content.Context
import android.util.Log
import android.view.Surface
import dev.jdtech.mpv.MPVLib

/**
 * Single libmpv session for AudioMix.
 *
 * Video source is the main media. The independent audio source is injected into
 * the same mpv session with audio-add, so video and audio are scheduled by one
 * playback engine instead of two independent ExoPlayer instances.
 */
class MpvDualStreamPlayer(
    context: Context,
    private val videoUrl: String,
    private var audioUrl: String
) {
    private val mpv = requireNotNull(MPVLib.create(context)) { "Unable to create libmpv" }
    private var surface: Surface? = null
    private var released = false
    private var delayMs = 0L

    init {
        configure()
        mpv.init()
        observePlayback()
    }

    private fun configure() {
        setOption("vo", "gpu")
        setOption("hwdec", "auto-safe")
        setOption("cache", "yes")
        setOption("cache-secs", "8")
        setOption("demuxer-max-bytes", "64MiB")
        setOption("demuxer-max-back-bytes", "32MiB")
        setOption("network-timeout", "15")
        setOption("video-sync", "audio")
        setOption("audio-pitch-correction", "yes")
        setOption("keep-open", "yes")
        setOption("force-window", "no")
        setOption("osc", "no")
        setOption("osd-level", "1")
        setOption("idle", "yes")
        setOption("audio-delay", "0")
    }

    private fun setOption(name: String, value: String) {
        runCatching { mpv.setOptionString(name, value) }
            .onFailure { Log.w(TAG, "setOption $name failed", it) }
    }

    private fun observePlayback() {
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("cache-buffering-state", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    fun attachSurface(surface: Surface) {
        if (released) return
        this.surface = surface
        mpv.attachSurface(surface)
        load()
    }

    private fun load() {
        if (released) return
        mpv.command(arrayOf("loadfile", videoUrl, "replace"))
        mpv.command(arrayOf("audio-add", audioUrl, "select"))
        mpv.setPropertyBoolean("pause", false)
        applyDelay()
    }

    fun changeDelay(amountMs: Long) {
        if (released) return
        delayMs = (delayMs + amountMs).coerceIn(-15_000L, 15_000L)
        applyDelay()
    }

    fun setDelay(amountMs: Long) {
        if (released) return
        delayMs = amountMs.coerceIn(-15_000L, 15_000L)
        applyDelay()
    }

    private fun applyDelay() {
        mpv.setPropertyDouble("audio-delay", delayMs / 1000.0)
    }

    fun forceSync() {
        if (released) return
        applyDelay()
    }

    fun changeAudio(newUrl: String) {
        if (released || newUrl.isBlank()) return
        val oldUrl = audioUrl
        audioUrl = newUrl
        runCatching { mpv.command(arrayOf("audio-remove")) }
            .onFailure { Log.w(TAG, "audio-remove failed; loading new external audio", it) }
        mpv.command(arrayOf("audio-add", newUrl, "select"))
        applyDelay()
        Log.i(TAG, "Audio source changed from " + oldUrl + " to " + newUrl)
    }

    fun play() {
        if (released) return
        mpv.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (released) return
        mpv.setPropertyBoolean("pause", true)
    }

    fun release() {
        if (released) return
        released = true
        surface = null
        runCatching { mpv.command(arrayOf("stop")) }
        runCatching { mpv.destroy() }
    }

    fun getDelayText(): String {
        val sign = if (delayMs >= 0) "+" else ""
        return sign + delayMs + " ms"
    }

    companion object {
        private const val TAG = "AudioMix-MPV"
    }
}
