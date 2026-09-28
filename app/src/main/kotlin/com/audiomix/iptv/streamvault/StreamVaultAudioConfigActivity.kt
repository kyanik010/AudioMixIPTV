package com.audiomix.iptv.streamvault

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class StreamVaultAudioConfigActivity : AppCompatActivity() {
    private val prefs by lazy {
        getSharedPreferences("streamvault_audio_plugin", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "StreamVault Audio Source"
            textSize = 24f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val info = TextView(this).apply {
            text = "Configure the independent audio stream used when StreamVault starts playback."
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        root.addView(info)

        val url = EditText(this).apply {
            hint = "https://example.com/audio.m3u8"
            setSingleLine(true)
            setText(prefs.getString("audio_url", ""))
        }
        root.addView(url)

        val offset = EditText(this).apply {
            hint = "Offset in milliseconds"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            setText(prefs.getInt("offset_ms", 0).toString())
        }
        root.addView(offset)

        val save = Button(this).apply {
            text = "Save"
            isFocusable = true
            setOnClickListener {
                val offsetValue = offset.text.toString().toIntOrNull()?.coerceIn(-15000, 15000) ?: 0
                prefs.edit()
                    .putString("audio_url", url.text.toString().trim())
                    .putInt("offset_ms", offsetValue)
                    .putBoolean("enabled", true)
                    .apply()
                finish()
            }
        }
        root.addView(save)

        val stop = Button(this).apply {
            text = "Stop external audio"
            isFocusable = true
            setOnClickListener {
                prefs.edit().putBoolean("enabled", false).apply()
                finish()
            }
        }
        root.addView(stop)

        setContentView(root)
    }
}
