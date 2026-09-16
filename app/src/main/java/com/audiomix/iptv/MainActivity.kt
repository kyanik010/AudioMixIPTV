package com.audiomix.iptv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    val streamUrl: String
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

        val loginButton = Button(this)
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
                video.streamUrl,
                audio.streamUrl
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

        val controls =
            LinearLayout(this)

        controls.orientation =
            LinearLayout.HORIZONTAL

        controls.gravity =
            Gravity.CENTER

        controls.setPadding(
            10,
            10,
            10,
            10
        )

        controls.setBackgroundColor(
            Color.argb(
                190,
                0,
                0,
                0
            )
        )

        val delayMinus =
            Button(this)

        delayMinus.text =
            "-0.5s"

        val delayText =
            TextView(this)

        delayText.text =
            "0.0s"

        delayText.textSize =
            18f

        delayText.setTextColor(
            Color.WHITE
        )

        delayText.gravity =
            Gravity.CENTER

        delayText.setPadding(
            25,
            0,
            25,
            0
        )

        val delayPlus =
            Button(this)

        delayPlus.text =
            "+0.5s"

        val syncButton =
            Button(this)

        syncButton.text =
            "مزامنة تلقائية"

        val backButton =
            Button(this)

        backButton.text =
            "رجوع"

        controls.addView(delayMinus)
        controls.addView(delayText)
        controls.addView(delayPlus)
        controls.addView(syncButton)
        controls.addView(backButton)

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

            val direct =
                item.optString(
                    "stream_url"
                )

            val candidates =
                ArrayList<String>()

            if (
                direct.isNotBlank()
            ) {
                candidates.add(
                    direct
                )
            }

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
                        "$id.ts"
            )

            result.add(
                Channel(
                    id = id,
                    name = name,
                    streamUrl =
                        candidates.first()
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

class DualStreamPlayer(
    private val context: Context,
    private val videoUrl: String,
    private val audioUrl: String
) {

    private val videoPlayer: ExoPlayer
    private val audioPlayer: ExoPlayer

    private var manualDelayMs =
        0L

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var released =
        false

    private val syncRunnable =
        object : Runnable {

            override fun run() {

                if (!released) {

                    synchronize()

                    handler.postDelayed(
                        this,
                        500
                    )
                }
            }
        }

    init {

        videoPlayer =
            ExoPlayer.Builder(
                context
            ).build()

        audioPlayer =
            ExoPlayer.Builder(
                context
            ).build()

        configurePlayers()
    }

    private fun configurePlayers() {

        val videoParameters =
            videoPlayer
                .trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(
                    C.TRACK_TYPE_AUDIO,
                    true
                )
                .build()

        videoPlayer.trackSelectionParameters =
            videoParameters

        val audioParameters =
            audioPlayer
                .trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(
                    C.TRACK_TYPE_VIDEO,
                    true
                )
                .build()

        audioPlayer.trackSelectionParameters =
            audioParameters

        videoPlayer.setAudioAttributes(
            androidx.media3.common.AudioAttributes
                .Builder()
                .setContentType(
                    C.AUDIO_CONTENT_TYPE_MOVIE
                )
                .setUsage(
                    C.USAGE_MEDIA
                )
                .build(),
            false
        )

        audioPlayer.setAudioAttributes(
            androidx.media3.common.AudioAttributes
                .Builder()
                .setContentType(
                    C.AUDIO_CONTENT_TYPE_MUSIC
                )
                .setUsage(
                    C.USAGE_MEDIA
                )
                .build(),
            true
        )

        videoPlayer.setMediaItem(
            createMediaItem(
                videoUrl
            )
        )

        audioPlayer.setMediaItem(
            createMediaItem(
                audioUrl
            )
        )

        videoPlayer.prepare()
        audioPlayer.prepare()

        videoPlayer.playWhenReady =
            true

        audioPlayer.playWhenReady =
            true
    }

    private fun createMediaItem(
        url: String
    ): MediaItem {

        val builder =
            MediaItem.Builder()
                .setUri(url)

        when {

            url.contains(
                ".m3u8",
                ignoreCase = true
            ) -> {

                builder.setMimeType(
                    MimeTypes.APPLICATION_M3U8
                )
            }

            url.contains(
                ".ts",
                ignoreCase = true
            ) -> {

                builder.setMimeType(
                    MimeTypes.VIDEO_MP2T
                )
            }
        }

        return builder.build()
    }

    fun attachVideoView(
        playerView: PlayerView
    ) {

        playerView.player =
            videoPlayer
    }

    fun play() {

        videoPlayer.play()
        audioPlayer.play()

        handler.post(
            syncRunnable
        )
    }

    fun changeDelay(
        amountMs: Long
    ) {

        manualDelayMs =
            (
                manualDelayMs +
                        amountMs
            ).coerceIn(
                -10_000L,
                10_000L
            )

        synchronize()
    }

    fun getDelayText(): String {

        val seconds =
            manualDelayMs / 1000.0

        return String.format(
            "%.1fs",
            seconds
        )
    }

    fun forceSync() {

        synchronize(
            force = true
        )
    }

    private fun synchronize(
        force: Boolean = false
    ) {

        if (released) return

        val videoOffset =
            videoPlayer.currentLiveOffset

        val audioOffset =
            audioPlayer.currentLiveOffset

        if (
            videoOffset != C.TIME_UNSET &&
            audioOffset != C.TIME_UNSET
        ) {

            val desiredAudioOffset =
                videoOffset +
                        manualDelayMs

            val difference =
                audioOffset -
                        desiredAudioOffset

            correctAudio(
                difference,
                force
            )

        } else {

            val videoPosition =
                videoPlayer.currentPosition

            val audioPosition =
                audioPlayer.currentPosition

            val difference =
                audioPosition -
                        videoPosition -
                        manualDelayMs

            correctAudio(
                difference,
                force
            )
        }
    }

    private fun correctAudio(
        difference: Long,
        force: Boolean
    ) {

        val absolute =
            abs(difference)

        if (
            force ||
            absolute > 1500L
        ) {

            val target =
                audioPlayer.currentPosition -
                        difference

            if (
                target >= 0
            ) {

                audioPlayer.seekTo(
                    target
                )
            }

            audioPlayer.setPlaybackParameters(
                PlaybackParameters(
                    1f
                )
            )

        } else if (
            absolute > 250L
        ) {

            val speed =
                if (
                    difference > 0
                ) {
                    0.98f
                } else {
                    1.02f
                }

            audioPlayer.setPlaybackParameters(
                PlaybackParameters(
                    speed
                )
            )

        } else {

            audioPlayer.setPlaybackParameters(
                PlaybackParameters(
                    1f
                )
            )
        }
    }

    fun release() {

        if (released) return

        released = true

        handler.removeCallbacksAndMessages(
            null
        )

        videoPlayer.stop()
        audioPlayer.stop()

        videoPlayer.release()
        audioPlayer.release()
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
