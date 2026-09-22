package com.audiomix.iptv

data class XtreamConfig(val server: String, val username: String, val password: String)
data class XtreamChannel(val id: String, val name: String, val streamUrl: String, val logo: String? = null, val categoryId: String? = null)
enum class PlaybackState { IDLE, CONNECTING, BUFFERING, PLAYING, PAUSED, STALLING, RECONNECTING, ERROR, STOPPED }
data class DriftSample(val audioMs: Long, val videoMs: Long) { val driftMs: Long get() = audioMs - videoMs }
