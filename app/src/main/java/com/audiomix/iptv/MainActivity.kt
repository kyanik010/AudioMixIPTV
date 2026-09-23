package com.audiomix.iptv

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * AudioMix IPTV main UI.
 * One Xtream subscription supplies both the video and audio channel lists.
 * Video quality is never forced to 4K; Media3 uses the stream's native quality.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var audioServer: EditText
    private lateinit var audioUsername: EditText
    private lateinit var audioPassword: EditText
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("audiomix", Context.MODE_PRIVATE) }
    private var channels: List<Channel> = emptyList()
    private var audioChannels: List<Channel> = emptyList()
    private var selectedVideo: Channel? = null
    private var selectedAudio: Channel? = null
    private var player: DualStreamPlayer? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        showLogin()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun showLogin() {
        root = FrameLayout(this)
        root.background = bg()
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(30), d(50), d(30), d(40))
        }
        scroll.addView(box)

        box.addView(title("AudioMix IPTV", 32f), lp(-1, 64, bottom = 22))
        box.addView(label("Video Source + Audio Source مستقلان", 18f, true).apply { gravity = Gravity.CENTER }, lp(-1, 40, bottom = 8))
        box.addView(label("حساب Xtream للفيديو + حساب Xtream مستقل للصوت", 13f).apply { gravity = Gravity.CENTER }, lp(-1, 40, bottom = 20))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(22), d(22), d(22), d(22))
            background = rounded(Color.rgb(24, 29, 38), 22f)
        }
        box.addView(card, lp(-1, -2))

        server = input("Video Server URL")
        username = input("Video Username")
        password = input("Video Password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        audioServer = input("Audio Server URL")
        audioUsername = input("Audio Username")
        audioPassword = input("Audio Password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }

        server.setText(prefs.getString("video_server", prefs.getString("server", "")) ?: "")
        username.setText(prefs.getString("video_username", prefs.getString("username", "")) ?: "")
        password.setText(prefs.getString("video_password", prefs.getString("password", "")) ?: "")
        audioServer.setText(prefs.getString("audio_server", "") ?: "")
        audioUsername.setText(prefs.getString("audio_username", "") ?: "")
        audioPassword.setText(prefs.getString("audio_password", "") ?: "")

        card.addView(label("Video Account", 16f, true), lp(-1, 30, top = 2))
        card.addView(server, fieldLp()); card.addView(username, fieldLp()); card.addView(password, fieldLp())
        card.addView(label("Audio Account", 16f, true), lp(-1, 30, top = 8))
        card.addView(audioServer, fieldLp()); card.addView(audioUsername, fieldLp()); card.addView(audioPassword, fieldLp())

        val connect = Button(this).apply { text = "اتصال وتحميل مصدري الفيديو والصوت"; style(this, true) }
        card.addView(connect, lp(-1, 56, top = 4))
        connect.setOnClickListener {
            val vs = server.text.toString().trim().removeSuffix("/")
            val vu = username.text.toString().trim()
            val vp = password.text.toString()
            val asrv = audioServer.text.toString().trim().removeSuffix("/")
            val au = audioUsername.text.toString().trim()
            val ap = audioPassword.text.toString()

            if (vs.isBlank() || vu.isBlank() || vp.isBlank() ||
                asrv.isBlank() || au.isBlank() || ap.isBlank()) {
                toast("أدخل بيانات حسابي Video وAudio كاملة")
                return@setOnClickListener
            }

            connect.isEnabled = false
            connect.text = "جاري تحميل Video + Audio..."
            executor.execute {
                try {
                    val videoResult = XtreamApi.loadLiveChannels(XtreamCredentials(vs, vu, vp))
                    if (videoResult.isEmpty()) throw IllegalStateException("VIDEO_EMPTY")
                    val audioResult = XtreamApi.loadLiveChannels(XtreamCredentials(asrv, au, ap))
                    if (audioResult.isEmpty()) throw IllegalStateException("AUDIO_EMPTY")

                    runOnUiThread {
                        connect.isEnabled = true
                        connect.text = "اتصال وتحميل مصدري الفيديو والصوت"
                        channels = videoResult
                        audioChannels = audioResult
                        prefs.edit()
                            .putString("video_server", vs)
                            .putString("video_username", vu)
                            .putString("video_password", vp)
                            .putString("audio_server", asrv)
                            .putString("audio_username", au)
                            .putString("audio_password", ap)
                            .apply()
                        showChannels()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        connect.isEnabled = true
                        connect.text = "اتصال وتحميل مصدري الفيديو والصوت"
                        toast(
                            when (e.message) {
                                "VIDEO_EMPTY" -> "حساب الفيديو لم يُرجع قنوات مباشرة."
                                "AUDIO_EMPTY" -> "حساب الصوت لم يُرجع قنوات مباشرة."
                                else -> "فشل الاتصال بأحد حسابي Xtream: " + (e.message ?: "غير معروف")
                            }
                        )
                    }
                }
            }
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun showChannels() {
        root = FrameLayout(this)
        root.background = bg()
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(22), d(18), d(22), d(14))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(title("AudioMix IPTV", 22f), lp(0, 54, weight = 1f))
        val settings = Button(this).apply { text = "الإعدادات"; style(this) }
        header.addView(settings, lp(100, 50))
        main.addView(header)

        val selected = label(selectionText(), 13f).apply {
            setTextColor(Color.LTGRAY); setPadding(d(14), d(12), d(14), d(12)); background = rounded(Color.rgb(25, 30, 39), 16f)
        }
        main.addView(selected, lp(-1, -2, top = 8))

        val search = input("بحث في القنوات...")
        main.addView(search, lp(-1, 52, top = 10))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val video = Button(this).apply { text = "اختيار الفيديو"; style(this, true) }
        val audio = Button(this).apply { text = "اختيار الصوت"; style(this) }
        actions.addView(video, lp(0, 52, weight = 1f))
        actions.addView(audio, lp(0, 52, weight = 1f, left = 8))
        main.addView(actions, lp(-1, 52, top = 10))

        val play = Button(this).apply { text = "▶ تشغيل Video + Audio"; style(this, true) }
        main.addView(play, lp(-1, 56, top = 10))
        main.addView(label("${channels.size} قناة • جودة الفيديو لا تُجبر على 4K", 12f), lp(-1, 24, top = 8))

        val list = ListView(this)
        main.addView(list, lp(-1, 0, weight = 1f, top = 8))

        fun refresh() {
            val q = search.text.toString().trim()
            val filtered = if (q.isBlank()) channels else channels.filter { it.name.contains(q, true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filtered.take(1200).map { it.name })
            list.setOnItemClickListener { _, _, pos, _ ->
                selectedVideo = filtered[pos]
                selected.text = selectionText()
                showAudioPicker()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        video.setOnClickListener { pickChannel("اختر قناة الفيديو") { selectedVideo = it; selected.text = selectionText() } }
        audio.setOnClickListener { pickChannel("اختر قناة الصوت", audioChannels) { selectedAudio = it; selected.text = selectionText() } }
        play.setOnClickListener { startPlayback() }
        settings.setOnClickListener { settingsDialog() }

        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        refresh()
        video.requestFocus()
    }

    private fun showAudioPicker() {
        val video = selectedVideo ?: return
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(22), d(22), d(22), d(22)); background = rounded(Color.rgb(16, 19, 25), 22f) }
        box.addView(title("AudioMix", 24f))
        box.addView(label("Video Source\n${video.name}", 15f, true).apply { setPadding(d(14), d(14), d(14), d(14)) }, lp(-1, -2, top = 14))
        val audio = Button(this).apply { text = if (selectedAudio == null) "اختيار Audio Source" else "Audio: ${selectedAudio!!.name}"; style(this) }
        val start = Button(this).apply { text = "تشغيل"; style(this, true) }
        box.addView(audio, lp(-1, 54, top = 10)); box.addView(start, lp(-1, 54, top = 10))
        audio.setOnClickListener { pickChannel("اختر مصدر الصوت", audioChannels) { selectedAudio = it; audio.text = "Audio: ${it.name}" } }
        start.setOnClickListener { if (selectedAudio == null) toast("اختر مصدر الصوت أولًا") else { dialog.dismiss(); startPlayback() } }
        dialog.setContentView(box); dialog.show(); dialog.window?.setLayout((resources.displayMetrics.widthPixels * .9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun pickChannel(titleText: String, source: List<Channel> = channels, onPick: (Channel) -> Unit) {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(20), d(20), d(20)); background = rounded(Color.rgb(16, 19, 25), 22f) }
        box.addView(title(titleText, 21f))
        val search = input("بحث..."); box.addView(search, lp(-1, 52, top = 10))
        val list = ListView(this); box.addView(list, lp(-1, 480, top = 8))
        fun refresh() {
            val q = search.text.toString().trim(); val rows = if (q.isBlank()) source else source.filter { it.name.contains(q, true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.take(1200).map { it.name })
            list.setOnItemClickListener { _, _, pos, _ -> onPick(rows[pos]); dialog.dismiss() }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.setContentView(box); dialog.show(); dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT); refresh()
    }

    private fun startPlayback() {
        val v = selectedVideo ?: run { toast("اختر الفيديو أولًا"); return }
        val a = selectedAudio ?: run { toast("اختر الصوت أولًا"); return }
        player?.release()
        player = try { DualStreamPlayer(this, v.streamUrls, a.streamUrls) } catch (e: Exception) { toast("تعذر تجهيز المشغل: ${e.message ?: "غير معروف"}"); null }
        if (player != null) showPlayer()
    }

    private fun showPlayer() {
        root = FrameLayout(this); root.setBackgroundColor(Color.BLACK)
        val view = PlayerView(this).apply { useController = true; setBackgroundColor(Color.BLACK) }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(10), d(8), d(10), d(8)); setBackgroundColor(Color.argb(180, 0, 0, 0)) }
        bar.addView(title("AudioMix IPTV", 18f), lp(0, 50, weight = 1f))
        val mix = Button(this).apply { text = "AudioMix"; style(this, true) }; bar.addView(mix, lp(105, 46))
        root.addView(bar, FrameLayout.LayoutParams(-1, 66).apply { gravity = Gravity.TOP })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(d(6), d(6), d(6), d(8)); setBackgroundColor(Color.argb(190, 0, 0, 0)) }
        val minus = Button(this).apply { text = "-0.5s"; style(this) }
        val delay = label(player?.getDelayText() ?: "0.0s", 15f).apply { gravity = Gravity.CENTER; setPadding(d(8), d(0), d(8), d(0)) }
        val plus = Button(this).apply { text = "+0.5s"; style(this) }
        val sync = Button(this).apply { text = "مزامنة"; style(this, true) }
        val audio = Button(this).apply { text = "تغيير الصوت"; style(this) }
        val back = Button(this).apply { text = "رجوع"; style(this) }
        bottom.addView(minus); bottom.addView(delay); bottom.addView(plus); bottom.addView(sync); bottom.addView(audio); bottom.addView(back)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
        minus.setOnClickListener { player?.changeDelay(-500); delay.text = player?.getDelayText() ?: "0.0s" }
        plus.setOnClickListener { player?.changeDelay(500); delay.text = player?.getDelayText() ?: "0.0s" }
        sync.setOnClickListener { player?.forceSync(); toast("تمت محاولة المزامنة") }
        audio.setOnClickListener { pickChannel("مصدر الصوت الجديد", audioChannels) { selectedAudio = it; if (player?.switchAudio(it.streamUrls) == true) toast("تم تبديل الصوت") } }
        back.setOnClickListener { player?.release(); player = null; showChannels() }
        mix.setOnClickListener { showAudioPicker() }
        setContentView(root)
        player?.attachVideoView(view)
        player?.play()
    }

    private fun settingsDialog() {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(22), d(22), d(22), d(22)); background = rounded(Color.rgb(16, 19, 25), 22f) }
        box.addView(title("الإعدادات", 22f))
        box.addView(label("جودة الفيديو تتبع المصدر الرسمي للبث. لا يتم إجبار أي قناة على 4K.", 13f).apply { setTextColor(Color.LTGRAY) }, lp(-1, -2, top = 14))
        val clear = Button(this).apply { text = "مسح بيانات الاشتراك"; style(this) }; box.addView(clear, lp(-1, 52, top = 16))
        clear.setOnClickListener { prefs.edit().clear().apply(); dialog.dismiss(); showLogin() }
        dialog.setContentView(box); dialog.show(); dialog.window?.setLayout((resources.displayMetrics.widthPixels * .88).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun selectionText() = "Video: ${selectedVideo?.name ?: "غير محدد"}\nAudio: ${selectedAudio?.name ?: "غير محدد"}"
    private fun title(text: String, size: Float) = label(text, size, true).apply { setTextColor(Color.WHITE) }
    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply { this.text = text; textSize = size; setTextColor(Color.WHITE); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun input(hint: String) = EditText(this).apply { this.hint = hint; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true); background = rounded(Color.rgb(16, 19, 25), 16f); setPadding(d(16), d(0), d(16), d(0)) }
    private fun style(b: Button, primary: Boolean = false) { b.isAllCaps = false; b.setTextColor(Color.WHITE); b.textSize = 13f; b.background = rounded(if (primary) Color.rgb(65, 132, 225) else Color.rgb(32, 38, 48), 15f); b.stateListAnimator = null; b.isFocusable = true }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun bg() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(6, 8, 12), Color.rgb(22, 27, 36), Color.rgb(5, 7, 10)))
    private fun d(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dim(value: Int): Int = if (value < 0) value else d(value)
    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(dim(w), dim(h), weight).apply {
            topMargin = d(top); bottomMargin = d(bottom); leftMargin = d(left); rightMargin = d(right)
        }
    private fun fieldLp() = lp(-1, 54, bottom = 12)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onBackPressed() {
        if (player != null) { player?.release(); player = null; showChannels() } else super.onBackPressed()
    }
}

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val streamUrls: List<String> = listOf(streamUrl)
)

data class XtreamCredentials(val server: String, val username: String, val password: String)

private object XtreamApi {
    fun loadLiveChannels(c: XtreamCredentials): List<Channel> {
        val u = URLEncoder.encode(c.username, "UTF-8")
        val p = URLEncoder.encode(c.password, "UTF-8")
        val endpoint = "${c.server}/player_api.php?username=$u&password=$p&action=get_live_streams"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000; requestMethod = "GET"; setRequestProperty("User-Agent", "AudioMix IPTV/1.0")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("stream_id").trim(); if (id.isBlank()) continue
                    val name = item.optString("name").trim().ifBlank { "Channel $id" }
                    val urls = listOf("${c.server}/live/${c.username}/${c.password}/$id.m3u8", "${c.server}/live/${c.username}/${c.password}/$id.ts", "${c.server}/live/${c.username}/${c.password}/$id").distinct()
                    add(Channel(id, name, urls.first(), urls))
                }
            }
        } finally { connection.disconnect() }
    }
}
