package com.audiomix.iptv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val streamUrls: List<String> = listOf(streamUrl)
)

data class XtreamCredentials(
    val server: String,
    val username: String,
    val password: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout

    private var credentials: XtreamCredentials? = null
    private var channels = emptyList<Channel>()

    private var selectedVideo: Channel? = null
    private var selectedAudio: Channel? = null

    private var dualPlayer: DualStreamPlayer? = null

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var serverField: EditText
    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginButton: Button

    private val prefs by lazy {
        getSharedPreferences("audiomix_settings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        showLoginScreen()
    }

    override fun onDestroy() {
        dualPlayer?.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun showLoginScreen() {
        root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(6, 8, 12), Color.rgb(22, 27, 36), Color.rgb(5, 7, 10))
        )

        val scroll = ScrollView(this)
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER_HORIZONTAL
        container.setPadding(42, 50, 42, 40)
        scroll.addView(container)

        val brand = TextView(this)
        brand.text = "▶  AudioMix IPTV"
        brand.textSize = 31f
        brand.typeface = Typeface.DEFAULT_BOLD
        brand.setTextColor(Color.WHITE)
        brand.gravity = Gravity.CENTER
        brand.setPadding(24, 16, 24, 16)
        brand.background = roundedBackground(Color.rgb(25, 39, 58), 28f)
        container.addView(brand, LinearLayout.LayoutParams(-2, 70).apply { bottomMargin = 26 })

        val hero = TextView(this)
        hero.text = "صورة من قناة\nوصوت من قناة أخرى"
        hero.textSize = 25f
        hero.typeface = Typeface.DEFAULT_BOLD
        hero.setTextColor(Color.WHITE)
        hero.gravity = Gravity.CENTER
        container.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })

        val sub = TextView(this)
        sub.text = "اشتراك Xtream واحد • تحكم مستقل بمصدر الفيديو والصوت"
        sub.textSize = 14f
        sub.setTextColor(Color.LTGRAY)
        sub.gravity = Gravity.CENTER
        container.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 28 })

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(24, 24, 24, 24)
        card.background = roundedBackground(Color.rgb(24, 28, 36), 24f)
        container.addView(card, LinearLayout.LayoutParams(-1, -2))

        serverField = styledField("Server URL")
        usernameField = styledField("Username")
        passwordField = styledField("Password")
        passwordField.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        card.addView(serverField, fieldParams())
        card.addView(usernameField, fieldParams())
        card.addView(passwordField, fieldParams())

        loginButton = Button(this)
        loginButton.text = "اتصال وفتح القنوات"
        styleButton(loginButton, true)
        card.addView(loginButton, LinearLayout.LayoutParams(-1, 56).apply { topMargin = 8 })

        val footer = TextView(this)
        footer.text = "Android • Android TV\nVideo Source + Audio Source"
        footer.textSize = 12f
        footer.setTextColor(Color.GRAY)
        footer.gravity = Gravity.CENTER
        container.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22 })

        setContentView(root)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        serverField.setText(prefs.getString("server", "") ?: "")
        usernameField.setText(prefs.getString("username", "") ?: "")
        passwordField.setText(prefs.getString("password", "") ?: "")

        loginButton.setOnClickListener {
            val serverText = serverField.text.toString().trim()
            val userText = usernameField.text.toString().trim()
            val passText = passwordField.text.toString()

            if (serverText.isEmpty() || userText.isEmpty() || passText.isEmpty()) {
                Toast.makeText(this, "أدخل السيرفر واسم المستخدم وكلمة المرور", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            loginButton.text = "جاري تحميل القنوات..."

            executor.execute {
                try {
                    val creds = XtreamCredentials(normalizeServer(serverText), userText, passText)
                    val result = XtreamApi.getLiveChannels(creds)
                    runOnUiThread {
                        loginButton.isEnabled = true
                        loginButton.text = "اتصال وفتح القنوات"
                        if (result.isEmpty()) {
                            Toast.makeText(this, "تعذر جلب القنوات. تحقق من بيانات Xtream Codes.", Toast.LENGTH_LONG).show()
                        } else {
                            credentials = creds
                            channels = result
                            prefs.edit()
                                .putString("server", creds.server)
                                .putString("username", creds.username)
                                .putString("password", creds.password)
                                .apply()
                            showChannelScreen()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        loginButton.isEnabled = true
                        loginButton.text = "اتصال وفتح القنوات"
                        Toast.makeText(this, "خطأ في الاتصال: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        if (serverField.text.isNotBlank() && usernameField.text.isNotBlank() && passwordField.text.isNotBlank()) {
            loginButton.postDelayed({ loginButton.performClick() }, 300)
        }
    }

    private fun showChannelScreen() {
        root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(7, 9, 13), Color.rgb(20, 24, 32), Color.rgb(7, 9, 13))
        )

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(26, 22, 26, 18)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val logo = TextView(this)
        logo.text = "▶  AudioMix IPTV"
        logo.textSize = 23f
        logo.typeface = Typeface.DEFAULT_BOLD
        logo.setTextColor(Color.WHITE)
        header.addView(logo, LinearLayout.LayoutParams(0, 54, 1f))

        val settings = Button(this)
        settings.text = "⚙"
        styleButton(settings)
        header.addView(settings, LinearLayout.LayoutParams(58, 50))
        main.addView(header)

        val title = TextView(this)
        title.text = "القنوات"
        title.textSize = 28f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.WHITE)
        main.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })

        val selectedText = TextView(this)
        selectedText.text = buildSelectionText()
        selectedText.textSize = 14f
        selectedText.setTextColor(Color.LTGRAY)
        selectedText.setPadding(16, 14, 16, 14)
        selectedText.background = roundedBackground(Color.rgb(25, 30, 39), 18f)
        main.addView(selectedText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })

        val search = EditText(this)
        search.hint = "بحث في القنوات..."
        search.setTextColor(Color.WHITE)
        search.setHintTextColor(Color.GRAY)
        search.setSingleLine(true)
        search.background = roundedBackground(Color.rgb(24, 28, 36), 18f)
        search.setPadding(18, 0, 18, 0)
        main.addView(search, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 12 })

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER

        val videoButton = Button(this)
        videoButton.text = "اختيار الفيديو"
        styleButton(videoButton, true)
        val audioButton = Button(this)
        audioButton.text = "اختيار الصوت"
        styleButton(audioButton)

        buttons.addView(videoButton, LinearLayout.LayoutParams(0, 52, 1f))
        buttons.addView(audioButton, LinearLayout.LayoutParams(0, 52, 1f).apply { leftMargin = 8 })
        main.addView(buttons, LinearLayout.LayoutParams(-1, 52).apply { topMargin = 12 })

        val playButton = Button(this)
        playButton.text = "▶  تشغيل Video + Audio"
        styleButton(playButton, true)
        main.addView(playButton, LinearLayout.LayoutParams(-1, 56).apply { topMargin = 10 })

        val count = TextView(this)
        count.text = "عدد القنوات: " + channels.size + "  •  مصدر فيديو + مصدر صوت من نفس الاشتراك"
        count.textSize = 12f
        count.setTextColor(Color.GRAY)
        main.addView(count, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })

        val list = ListView(this)
        main.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 10 })

        fun updateList(query: String) {
            val filtered = if (query.isBlank()) channels else channels.filter {
                it.name.contains(query, ignoreCase = true)
            }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filtered.map { it.name }.take(1000))
            list.setOnItemClickListener { _, _, position, _ ->
                val ch = filtered[position]
                selectedVideo = ch
                selectedText.text = buildSelectionText()
                showAudioMixPicker()
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateList(s?.toString() ?: "") }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        videoButton.setOnClickListener {
            showChannelPicker("اختر قناة الفيديو") {
                selectedVideo = it
                selectedText.text = buildSelectionText()
            }
        }

        audioButton.setOnClickListener {
            showChannelPicker("اختر قناة الصوت") {
                selectedAudio = it
                selectedText.text = buildSelectionText()
            }
        }

        playButton.setOnClickListener {
            if (selectedVideo == null) {
                Toast.makeText(this, "اختر قناة الفيديو أولًا", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (selectedAudio == null) {
                Toast.makeText(this, "اختر قناة الصوت أولًا", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startDualPlayback()
        }

        settings.setOnClickListener { showSettingsDialog() }

        main.addView(labelText("اختيار أي قناة = فتح AudioMix مباشرة", 12f), 4)
        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        updateList("")
    }

    private fun showAudioMixPicker() {
        val video = selectedVideo ?: return
        val dialog = DialogHelper.createDialog(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(24, 24, 24, 24)
        box.background = roundedBackground(Color.rgb(16, 19, 25), 24f)

        val title = TextView(this)
        title.text = "AudioMix"
        title.textSize = 25f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.WHITE)
        box.addView(title)

        val videoInfo = TextView(this)
        videoInfo.text = "🎥 Video Source\n" + video.name
        videoInfo.textSize = 15f
        videoInfo.setTextColor(Color.WHITE)
        videoInfo.setPadding(16, 14, 16, 14)
        videoInfo.background = roundedBackground(Color.rgb(26, 31, 40), 18f)
        box.addView(videoInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 })

        val audioButton = Button(this)
        audioButton.text = if (selectedAudio == null) "🔊 اختيار Audio Source" else "🔊 " + selectedAudio!!.name
        styleButton(audioButton)
        box.addView(audioButton, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 10 })

        val play = Button(this)
        play.text = "تشغيل Video + Audio"
        styleButton(play, true)
        box.addView(play, LinearLayout.LayoutParams(-1, 56).apply { topMargin = 12 })

        audioButton.setOnClickListener {
            showChannelPicker("اختر مصدر الصوت") {
                selectedAudio = it
                audioButton.text = "🔊 " + it.name
            }
        }

        play.setOnClickListener {
            if (selectedAudio == null) {
                Toast.makeText(this, "اختر مصدر الصوت أولًا", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            startDualPlayback()
        }

        dialog.setContentView(box)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun styledField(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
            background = roundedBackground(Color.rgb(16, 19, 25), 16f)
            setPadding(18, 0, 18, 0)
        }

    private fun fieldParams() = LinearLayout.LayoutParams(-1, 54).apply {
        bottomMargin = 12
    }

    private fun styleButton(button: Button, primary: Boolean = false) {
        button.isAllCaps = false
        button.setTextColor(Color.WHITE)
        button.textSize = 14f
        button.background = roundedBackground(
            if (primary) Color.rgb(65, 132, 225) else Color.rgb(32, 38, 48),
            16f
        )
        button.stateListAnimator = null
    }

    private fun labelText(textValue: String, size: Float): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(Color.GRAY)
        }


