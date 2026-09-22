package com.audiomix.iptv

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class XtreamClient(private val http: OkHttpClient = OkHttpClient()) {
    fun liveChannels(config: XtreamConfig): List<XtreamChannel> {
        val base = config.server.trimEnd('/')
        val url = (base + "/player_api.php").toHttpUrl().newBuilder()
            .addQueryParameter("username", config.username)
            .addQueryParameter("password", config.password)
            .addQueryParameter("action", "get_live_streams")
            .build()
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Xtream HTTP " + response.code)
            val body = response.body?.string() ?: return emptyList()
            val array = JSONArray(body)
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optString("stream_id")
                    val ext = item.optString("container_extension").ifBlank { "ts" }
                    val stream = base + "/live/" + config.username + "/" + config.password + "/" + id + "." + ext
                    add(XtreamChannel(
                        id = id,
                        name = item.optString("name"),
                        streamUrl = stream,
                        logo = item.optString("stream_icon").ifBlank { null },
                        categoryId = item.optString("category_id").ifBlank { null }
                    ))
                }
            }
        }
    }
}
