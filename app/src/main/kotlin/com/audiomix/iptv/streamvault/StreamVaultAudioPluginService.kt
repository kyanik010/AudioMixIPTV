package com.audiomix.iptv.streamvault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Message
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.audiomix.iptv.R
import org.json.JSONObject

class StreamVaultAudioPluginService : Service() {

    companion object {
        const val API_ACTION = "com.streamvault.plugin.API"
        const val CONFIGURE_ACTION = "com.audiomix.streamvault.audio.CONFIGURE"
        private const val CHANNEL_ID = "streamvault_audio"
        private const val NOTIFICATION_ID = 4107

        private const val MSG_GET_MANIFEST = 1
        private const val MSG_SET_ENABLED = 2
        private const val MSG_GET_STATUS = 3
        private const val MSG_GET_PROVIDER_URL = 4
        private const val MSG_PREPARE_PLAYBACK = 5
        private const val MSG_REWRITE_CAST_URL = 6
        private const val MSG_GET_CONFIGURATION_SCHEMA = 7
        private const val MSG_GET_CONFIGURATION_VALUES = 8
        private const val MSG_SET_CONFIGURATION_VALUES = 9
        private const val MSG_RUN_CONFIGURATION_ACTION = 10

        private const val KEY_API_VERSION = "api_version"
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_SUCCESS = "success"
        private const val KEY_MESSAGE = "message"
        private const val KEY_MANIFEST_JSON = "manifest_json"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STATUS_LABEL = "status_label"
        private const val KEY_INPUT_URL = "input_url"
        private const val KEY_HANDLED = "handled"
        private const val KEY_OUTPUT_URL = "output_url"
        private const val KEY_CONFIGURATION_VALUES_JSON = "configuration_values_json"
        private const val KEY_CONFIGURATION_SCHEMA_JSON = "configuration_schema_json"
        private const val KEY_CONFIGURATION_ACTION_ID = "configuration_action_id"
    }

    private val handler = IncomingHandler()
    private val messenger = Messenger(handler)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var enabled = false
    private var playGeneration = 0

    private val prefs by lazy {
        getSharedPreferences("streamvault_audio_plugin", MODE_PRIVATE)
    }

    private val manifestJson = JSONObject()
        .put("schemaVersion", 1)
        .put("id", "com.audiomix.streamvault.audio")
        .put("name", "StreamVault Audio Source")
        .put("versionName", "1.0.0")
        .put("versionCode", 1)
        .put("description", "External audio source companion for StreamVault.")
        .put("providerName", "AudioMix IPTV")
        .put("configurationMode", "activity")
        .put("configurationActivityAction", CONFIGURE_ACTION)
        .put("capabilities", org.json.JSONArray()
            .put("playback.prepare")
            .put("configuration.activity"))
        .put("playbackUrlSchemes", org.json.JSONArray().put("http").put("https"))
        .put("playbackUrlHosts", org.json.JSONArray().put("*"))
        .toString()

    override fun onCreate() {
        super.onCreate()
        enabled = prefs.getBoolean("enabled", true)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }

    private fun handlePluginMessage(msg: Message) {
        when (msg.what) {
            MSG_GET_MANIFEST -> reply(msg, Bundle().apply {
                putString(KEY_MANIFEST_JSON, manifestJson)
            })
            MSG_SET_ENABLED -> {
                enabled = msg.data.getBoolean(KEY_ENABLED, true)
                prefs.edit().putBoolean("enabled", enabled).apply()
                if (!enabled) stopAudio()
                reply(msg, Bundle())
            }
            MSG_GET_STATUS -> reply(msg, Bundle().apply {
                putString(KEY_STATUS_LABEL, if (player?.isPlaying == true) "Audio playing" else if (enabled) "Ready" else "Disabled")
                putString(KEY_MESSAGE, statusMessage())
            })
            MSG_PREPARE_PLAYBACK -> handlePrepare(msg)
            MSG_REWRITE_CAST_URL -> reply(msg, Bundle().apply {
                putBoolean(KEY_HANDLED, false)
                putString(KEY_MESSAGE, "Audio Source does not rewrite Cast URLs.")
            })
            MSG_GET_CONFIGURATION_VALUES -> reply(msg, Bundle().apply {
                putString(KEY_CONFIGURATION_VALUES_JSON, configurationValues().toString())
            })
            MSG_SET_CONFIGURATION_VALUES -> {
                val raw = msg.data.getString(KEY_CONFIGURATION_VALUES_JSON).orEmpty()
                persistConfiguration(raw)
                reply(msg, Bundle())
            }
            MSG_GET_CONFIGURATION_SCHEMA -> reply(msg, Bundle().apply {
                putString(KEY_CONFIGURATION_SCHEMA_JSON, configurationSchema())
            })
            MSG_RUN_CONFIGURATION_ACTION -> reply(msg, Bundle().apply {
                putString(KEY_MESSAGE, "Configuration action completed.")
            })
            MSG_GET_PROVIDER_URL -> reply(msg, Bundle().apply {
                putBoolean(KEY_HANDLED, false)
                putString(KEY_MESSAGE, "This plugin does not provide an M3U source.")
            })
            else -> reply(msg, Bundle().apply {
                putBoolean(KEY_SUCCESS, false)
                putString(KEY_MESSAGE, "Unsupported message.")
            })
        }
    }

    private fun handlePrepare(msg: Message) {
        val inputUrl = msg.data.getString(KEY_INPUT_URL).orEmpty()
        val audioUrl = prefs.getString("audio_url", "").orEmpty()

        if (!enabled) {
            reply(msg, Bundle().apply {
                putBoolean(KEY_HANDLED, false)
                putString(KEY_MESSAGE, "Plugin is disabled.")
            })
            return
        }

        if (audioUrl.isBlank()) {
            reply(msg, Bundle().apply {
                putBoolean(KEY_HANDLED, false)
                putString(KEY_MESSAGE, "No audio source configured.")
            })
            return
        }

        if (inputUrl.isBlank()) {
            reply(msg, Bundle().apply {
                putBoolean(KEY_HANDLED, false)
                putString(KEY_MESSAGE, "No input video URL.")
            })
            return
        }

        startAudio(audioUrl)
        reply(msg, Bundle().apply {
            putBoolean(KEY_HANDLED, false)
            putString(KEY_MESSAGE, "External audio source started.")
        })
    }

    private fun startAudio(url: String) {
        ensureForeground()
        stopAudio()
        val generation = ++playGeneration
        val newPlayer = ExoPlayer.Builder(this).build()
        newPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        newPlayer.setMediaItem(MediaItem.fromUri(url))
        newPlayer.prepare()
        player = newPlayer

        val offset = prefs.getInt("offset_ms", 0).coerceIn(-15000, 15000)
        if (offset >= 0) {
            mainHandler.postDelayed({
                if (generation == playGeneration) newPlayer.play()
            }, offset.toLong())
        } else {
            newPlayer.seekTo((-offset).toLong())
            newPlayer.play()
        }
    }

    private fun stopAudio() {
        playGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureForeground() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("StreamVault Audio Source")
            .setContentText("External audio source is active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "StreamVault Audio Source",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun statusMessage(): String {
        val audio = prefs.getString("audio_url", "").orEmpty()
        return if (audio.isBlank()) "Configure an audio URL in the plugin." else "Audio source configured."
    }

    private fun configurationValues(): JSONObject =
        JSONObject()
            .put("audioUrl", prefs.getString("audio_url", ""))
            .put("offsetMs", prefs.getInt("offset_ms", 0))
            .put("enabled", enabled)

    private fun configurationSchema(): String =
        JSONObject()
            .put("schemaVersion", 1)
            .put("title", "StreamVault Audio Source")
            .put("description", "Select the external audio stream and its startup offset.")
            .put("sections", org.json.JSONArray().put(
                JSONObject()
                    .put("id", "audio")
                    .put("title", "Audio Source")
                    .put("fields", org.json.JSONArray()
                        .put(JSONObject()
                            .put("key", "audioUrl")
                            .put("type", "url")
                            .put("label", "Audio stream URL")
                            .put("required", true))
                        .put(JSONObject()
                            .put("key", "offsetMs")
                            .put("type", "number")
                            .put("label", "Audio startup offset (ms)")
                            .put("description", "Positive delays audio. Negative starts audio from later in the stream."))
                        .put(JSONObject()
                            .put("key", "enabled")
                            .put("type", "boolean")
                            .put("label", "Enable external audio"))))
            .toString()

    private fun persistConfiguration(raw: String) {
        runCatching {
            val json = JSONObject(raw)
            val audioUrl = json.optString("audioUrl", "").trim()
            val offset = json.optInt("offsetMs", 0).coerceIn(-15000, 15000)
            val newEnabled = json.optBoolean("enabled", enabled)
            prefs.edit()
                .putString("audio_url", audioUrl)
                .putInt("offset_ms", offset)
                .putBoolean("enabled", newEnabled)
                .apply()
            enabled = newEnabled
            if (!enabled) stopAudio()
        }
    }

    private fun reply(request: Message, data: Bundle) {
        val reply = Message.obtain(null, request.what)
        reply.data = Bundle().apply {
            putInt(KEY_API_VERSION, 1)
            putString(KEY_REQUEST_ID, request.data.getString(KEY_REQUEST_ID))
            putBoolean(KEY_SUCCESS, true)
            putAll(data)
        }
        runCatching { request.replyTo?.send(reply) }
    }

    private inner class IncomingHandler : android.os.Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            handlePluginMessage(msg)
        }
    }
}
