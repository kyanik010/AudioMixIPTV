package com.audiomix.iptv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
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
        root.setBackgroundColor(Color.BLACK)

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER
        container.setPadding(50, 40, 50, 40)

        root.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val title = TextView(this)
        title.text = "AudioMix IPTV"
        title.textSize = 32f
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 40)

        container.addView(title)

        val server = EditText(this)
        serverField = server
        server.hint = "Server URL"
        server.setTextColor(Color.WHITE)
        server.setHintTextColor(Color.GRAY)
        server.setSingleLine(true)

        container.addView(
            server,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        )

        val username = EditText(this)
        usernameField = username
        username.hint = "Username"
        username.setTextColor(Color.WHITE)
        username.setHintTextColor(Color.GRAY)
        username.setSingleLine(true)

        container.addView(
            username,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        )

        val password = EditText(this)
        passwordField = password
        password.hint = "Password"
        password.setTextColor(Color.WHITE)
        password.setHintTextColor(Color.GRAY)
        password.inputType =
            android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.setSingleLine(true)

        container.addView(
            password,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        )

        loginButton = Button(this)
        loginButton.text = "تسجيل الدخول"

        container.addView(
            loginButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        loginButton.setOnClickListener {

            val serverText = server.text.toString().trim()
            val userText = username.text.toString().trim()
            val passText = password.text.toString()

            if (
                serverText.isEmpty() ||
                userText.isEmpty() ||
                passText.isEmpty()
            ) {
                Toast.makeText(
                    this,
                    "أدخل السيرفر واسم المستخدم وكلمة المرور",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            loginButton.text = "جاري الاتصال..."

            executor.execute {

                try {

                    val creds = XtreamCredentials(
                        normalizeServer(serverText),
                        userText,
                        passText
                    )

                    val result =
                        XtreamApi.getLiveChannels(creds)

                    runOnUiThread {

                        loginButton.isEnabled = true
                        loginButton.text = "تسجيل الدخول"

                        if (result.isEmpty()) {

                            Toast.makeText(
                                this,
                                "تعذر جلب القنوات. تحقق من بيانات Xtream Codes.",
                                Toast.LENGTH_LONG
                            ).show()

                        } else {

                            credentials = creds
                            channels = result

                            // حفظ بيانات Xtream على الجهاز حتى لا يضطر المستخدم
                            // لإدخالها مرة أخرى بعد إغلاق التطبيق.
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
                        loginButton.text = "تسجيل الدخول"

                        Toast.makeText(
                            this,
                            "خطأ في الاتصال: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        setContentView(root)

        // استعادة آخر بيانات الحساب تلقائيًا.
        serverField.setText(prefs.getString("server", "") ?: "")
        usernameField.setText(prefs.getString("username", "") ?: "")
        passwordField.setText(prefs.getString("password", "") ?: "")

        // إذا كانت البيانات محفوظة، حاول تسجيل الدخول تلقائيًا.
        if (serverField.text.isNotBlank() &&
            usernameField.text.isNotBlank() &&
            passwordField.text.isNotBlank()
        ) {
            loginButton.postDelayed({ loginButton.performClick() }, 250)
        }
    }

    private fun showChannelScreen() {

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(25, 25, 25, 25)

        root.addView(
            main,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val title = TextView(this)
        title.text = "اختيار القنوات"
        title.textSize = 28f
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 20)

        main.addView(title)

        val selectedText = TextView(this)
        selectedText.text = buildSelectionText()
        selectedText.textSize = 17f
        selectedText.setTextColor(Color.LTGRAY)
        selectedText.setPadding(10, 10, 10, 25)

        main.addView(selectedText)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.CENTER

        val videoButton = Button(this)
        videoButton.text = "اختيار الفيديو"

        val audioButton = Button(this)
        audioButton.text = "اختيار الصوت"

        buttons.addView(
            videoButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        buttons.addView(
            audioButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        main.addView(buttons)

        val playButton = Button(this)
        playButton.text = "تشغيل الفيديو + الصوت"

        main.addView(
            playButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 25
            }
        )

        val count = TextView(this)
        count.text = "عدد القنوات: ${channels.size}"
        count.textSize = 15f
        count.setTextColor(Color.GRAY)
        count.gravity = Gravity.CENTER
        count.setPadding(0, 25, 0, 0)

        main.addView(count)

        videoButton.setOnClickListener {

            showChannelPicker(
                "اختر قناة الفيديو"
            ) { channel ->

                selectedVideo = channel
                selectedText.text = buildSelectionText()
            }
        }

        audioButton.setOnClickListener {

            showChannelPicker(
                "اختر قناة الصوت"
            ) { channel ->

                selectedAudio = channel
                selectedText.text = buildSelectionText()
            }
        }

        playButton.setOnClickListener {

            if (selectedVideo == null) {

                Toast.makeText(
                    this,
                    "اختر قناة الفيديو أولًا",
                    Toast.LENGTH_LONG
                ).show()

                return@setOnClickListener
            }

            if (selectedAudio == null) {

                Toast.makeText(
                    this,
                    "اختر قناة الصوت أولًا",
                    Toast.LENGTH_LONG
                ).show()

                return@setOnClickListener
            }

            startDualPlayback()
        }

        setContentView(root)
    }

    private fun buildSelectionText(): String {

        val videoName =
            selectedVideo?.name ?: "لم يتم اختيار فيديو"

        val audioName =
            selectedAudio?.name ?: "لم يتم اختيار صوت"

        return """
            🎥 الفيديو:
            $videoName
            
            🔊 الصوت:
            $audioName
        """.trimIndent()
    }

    private fun showChannelPicker(
        titleText: String,
        onSelected: (Channel) -> Unit
    ) {

        val dialog =
            DialogHelper.createDialog(this)

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(25, 25, 25, 25)
        container.setBackgroundColor(
            Color.rgb(25, 25, 25)
        )

        val title = TextView(this)
        title.text = titleText
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        title.setPadding(0, 0, 0, 20)

        container.addView(title)

        val search = EditText(this)
        search.hint = "بحث عن قناة..."
        search.setTextColor(Color.WHITE)
        search.setHintTextColor(Color.GRAY)
        search.setSingleLine(true)

        container.addView(search)

        val list = ListView(this)

        container.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        fun updateList(query: String) {

            val filtered =
                if (query.isBlank()) {
                    channels
                } else {
                    channels.filter {
                        it.name.contains(
                            query,
                            ignoreCase = true
                        )
                    }
                }

            val names =
                filtered.map {
                    it.name
                }

            list.adapter =
                ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_1,
                    names
                )

            list.setOnItemClickListener {
                    _,
                    _,
                    position,
                    _ ->

                    val selected =
                        filtered[position]

                    onSelected(selected)

                    dialog.dismiss()
                }
        }

        search.addTextChangedListener(
            object : android.text.TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    updateList(
                        s?.toString() ?: ""
                    )
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {
                }
            }
        )

        updateList("")

        dialog.setContentView(container)

        dialog.show()

        val window = dialog.window

        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90)
                .toInt(),
            (resources.displayMetrics.heightPixels * 0.85)
                .toInt()
        )
    }

    private fun startDualPlayback() {

        val video =
            selectedVideo ?: return

        val audio =
            selectedAudio ?: return

        dualPlayer?.release()

        dualPlayer =
            DualStreamPlayer(
                this,
                video.streamUrls,
                audio.streamUrls
            )

        showPlayerScreen()

        dualPlayer?.play()
    }

    private fun showPlayerScreen() {

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val videoView =
            PlayerView(this)

        videoView.useController = true
        videoView.setBackgroundColor(Color.BLACK)

        root.addView(
            videoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // قسم التحكم مقسم إلى صفين حتى تظهر جميع الأزرار،
        // خصوصًا "تغيير الصوت"، على الهاتف وAndroid TV.
        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.gravity = Gravity.CENTER
        controls.setPadding(10, 10, 10, 10)
        controls.setBackgroundColor(Color.argb(190, 0, 0, 0))

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.gravity = Gravity.CENTER

        val delayMinus = Button(this)
        delayMinus.text = "-0.5s"

        val delayText = TextView(this)
        delayText.text = "0.0s"
        delayText.textSize = 18f
        delayText.setTextColor(Color.WHITE)
        delayText.gravity = Gravity.CENTER
        delayText.setPadding(18, 0, 18, 0)

        val delayPlus = Button(this)
        delayPlus.text = "+0.5s"

        val syncButton = Button(this)
        syncButton.text = "مزامنة"

        val changeVideoButton = Button(this)
        changeVideoButton.text = "تغيير الفيديو"

        val changeAudioButton = Button(this)
        changeAudioButton.text = "تغيير الصوت"

        val backButton = Button(this)
        backButton.text = "رجوع"

        row1.addView(delayMinus)
        row1.addView(delayText)
        row1.addView(delayPlus)
        row1.addView(syncButton)

        row2.addView(changeVideoButton)
        row2.addView(changeAudioButton)
        row2.addView(backButton)

        controls.addView(row1)
        controls.addView(row2)

        val controlsParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

        controlsParams.gravity =
            Gravity.BOTTOM

        root.addView(
            controls,
            controlsParams
        )

        delayMinus.setOnClickListener {

            dualPlayer?.changeDelay(
                -500
            )

            delayText.text =
                dualPlayer?.getDelayText()
                    ?: "0.0s"
        }

        delayPlus.setOnClickListener {

            dualPlayer?.changeDelay(
                500
            )

            delayText.text =
                dualPlayer?.getDelayText()
                    ?: "0.0s"
        }

        syncButton.setOnClickListener {

            dualPlayer?.forceSync()

            Toast.makeText(
                this,
                "تمت محاولة المزامنة",
                Toast.LENGTH_SHORT
            ).show()
        }

        changeVideoButton.setOnClickListener {
            showChannelPicker("اختر قناة الفيديو الجديدة") { channel ->
                selectedVideo = channel
                val changed = dualPlayer?.switchVideo(channel.streamUrls) ?: false
                if (changed) {
                    Toast.makeText(
                        this,
                        "تم تغيير مصدر الفيديو إلى: " + channel.name,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        changeAudioButton.setOnClickListener {
            showChannelPicker("اختر مصدر الصوت الجديد") { channel ->
                selectedAudio = channel
                val changed = dualPlayer?.switchAudio(channel.streamUrls) ?: false
                if (changed) {
                    Toast.makeText(
                        this,
                        "تم تغيير مصدر الصوت إلى: " + channel.name,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        backButton.setOnClickListener {

            dualPlayer?.release()

            dualPlayer = null

            showChannelScreen()
        }

        setContentView(root)

        dualPlayer?.attachVideoView(
            videoView
        )

        delayText.text =
            dualPlayer?.getDelayText()
                ?: "0.0s"
    }

    private fun normalizeServer(
        server: String
    ): String {

        var value =
            server.trim()

        if (
            !value.startsWith("http://") &&
            !value.startsWith("https://")
        ) {
            value =
                "http://$value"
        }

        return value.trimEnd('/')
    }
}

object XtreamApi {

    fun getLiveChannels(
        credentials: XtreamCredentials
    ): List<Channel> {

        val encodedUser =
            URLEncoder.encode(
                credentials.username,
                "UTF-8"
            )

        val encodedPassword =
            URLEncoder.encode(
                credentials.password,
                "UTF-8"
            )

        val apiUrl =
            "${credentials.server}/player_api.php" +
                    "?username=$encodedUser" +
                    "&password=$encodedPassword" +
                    "&action=get_live_streams"

        val json =
            request(apiUrl)

        val array =
            JSONArray(json)

        val result =
            ArrayList<Channel>()

        for (i in 0 until array.length()) {

            val item =
                array.getJSONObject(i)

            val id =
                item.optString(
                    "stream_id"
                )

            val name =
                item.optString(
                    "name"
                )

            if (
                id.isBlank() ||
                name.isBlank()
            ) {
                continue
            }

            val candidates = LinkedHashSet<String>()

            // استخدم مسارات Xtream القياسية أولًا. بعض لوحات Xtream ترسل
            // direct_source/stream_url غير صالحة أو غير قابلة للتشغيل بواسطة Media3.
            candidates.add(
                "${credentials.server}/live/" +
                        "${credentials.username}/" +
                        "${credentials.password}/" +
                        "$id.ts"
            )

            candidates.add(
                "${credentials.server}/live/" +
                        "${credentials.username}/" +
                        "${credentials.password}/" +
                        "$id.m3u8"
            )

            candidates.add(
                "${credentials.server}/live/" +
                        "${credentials.username}/" +
                        "${credentials.password}/" +
                        id
            )

            // ثم جرّب المسارات التي ترسلها لوحة Xtream نفسها كاحتياط.
            val directSource = item.optString("direct_source").trim()
            val streamUrl = item.optString("stream_url").trim()

            if (directSource.isNotBlank()) candidates.add(directSource)
            if (streamUrl.isNotBlank()) candidates.add(streamUrl)

            val urls = candidates.filter { it.isNotBlank() }
            if (urls.isEmpty()) continue

            result.add(
                Channel(
                    id = id,
                    name = name,
                    streamUrl = urls.first(),
                    streamUrls = urls
                )
            )
        }

        return result
    }

    private fun request(
        urlString: String
    ): String {

        val connection =
            URL(urlString)
                .openConnection()
                    as HttpURLConnection

        connection.requestMethod =
            "GET"

        connection.connectTimeout =
            15000

        connection.readTimeout =
            20000

        connection.setRequestProperty(
            "User-Agent",
            "AudioMix IPTV"
        )

        try {

            val responseCode =
                connection.responseCode

            if (
                responseCode !in 200..299
            ) {
                throw Exception(
                    "HTTP $responseCode"
                )
            }

            return connection.inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }

        } finally {

            connection.disconnect()
        }
    }
}

object DialogHelper {

    fun createDialog(
        context: Context
    ): Dialog {

        val dialog =
            Dialog(context)

        dialog.window
            ?.setBackgroundDrawableResource(
                android.R.color.transparent
            )

        return dialog
    }
}
