package app.vidscribe

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Podcast(val name: String, val author: String, val feedUrl: String)
data class Episode(val title: String, val audioUrl: String, val date: String, val durationSec: Double?)
data class PodcastFeed(val name: String, val feedUrl: String, val episodes: List<Episode>)

/**
 * Podcast search uses Apple's free public directory (no key needed), which lists almost every
 * podcast. Episodes come straight from the podcast's own RSS feed.
 */
class PodcastRepo {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun search(term: String): List<Podcast> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&term=" +
            URLEncoder.encode(term, "UTF-8")
        val arr = JSONObject(get(url)).optJSONArray("results") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val feed = o.optString("feedUrl")
            if (feed.isBlank()) null
            else Podcast(o.optString("collectionName"), o.optString("artistName"), feed)
        }
    }

    suspend fun feed(feedUrl: String): PodcastFeed = withContext(Dispatchers.IO) {
        val xml = get(feedUrl)
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))

        var channelTitle = ""
        val episodes = mutableListOf<Episode>()
        var inItem = false
        var title = ""; var audio = ""; var date = ""; var dur: String? = null

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item" -> { inItem = true; title = ""; audio = ""; date = ""; dur = null }
                    "title" -> {
                        val t = p.nextText().trim()
                        if (inItem) { if (title.isEmpty()) title = t } else if (channelTitle.isEmpty()) channelTitle = t
                    }
                    "enclosure" -> if (inItem && audio.isEmpty()) audio = p.getAttributeValue(null, "url").orEmpty()
                    "pubDate" -> if (inItem) date = p.nextText().trim()
                    "itunes:duration" -> if (inItem) dur = p.nextText().trim()
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                if (audio.isNotEmpty()) {
                    episodes += Episode(title.ifEmpty { "Untitled episode" }, audio, prettyDate(date), parseDuration(dur))
                    if (episodes.size >= 300) break
                }
            }
            ev = p.next()
        }
        if (episodes.isEmpty()) throw IOException("No episodes with audio were found in this feed.")
        PodcastFeed(channelTitle.ifEmpty { "Podcast" }, feedUrl, episodes)
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "VidScribe/1.0 (Android)").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Couldn't load podcast data (${r.code}).")
            return r.body?.string().orEmpty()
        }
    }

    companion object {
        /** "3600", "62:03" or "1:02:03" → seconds. */
        fun parseDuration(s: String?): Double? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(":").map { it.trim().toDoubleOrNull() ?: return null }
            return parts.fold(0.0) { acc, v -> acc * 60 + v }.takeIf { it > 0 }
        }

        fun prettyDuration(sec: Double?): String {
            if (sec == null) return ""
            val m = (sec / 60).toInt()
            return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
        }

        private fun prettyDate(raw: String): String {
            if (raw.isBlank()) return ""
            return try {
                val d = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).parse(raw)
                SimpleDateFormat("d MMM yyyy", Locale.UK).format(d!!)
            } catch (_: Exception) {
                raw.take(16)
            }
        }
    }
}
