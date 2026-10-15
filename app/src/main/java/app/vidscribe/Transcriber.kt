package app.vidscribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Provider(val label: String, val baseUrl: String, val model: String, val keyUrl: String) {
    GROQ("Groq (fast, generous free tier)", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo",
        "https://console.groq.com/keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "whisper-1",
        "https://platform.openai.com/api-keys"),
}

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var provider: Provider
        get() = runCatching { Provider.valueOf(p.getString("provider", "GROQ")!!) }.getOrDefault(Provider.GROQ)
        set(v) = p.edit().putString("provider", v.name).apply()
    var apiKey: String
        get() = p.getString("key_" + provider.name, "") ?: ""
        set(v) = p.edit().putString("key_" + provider.name, v.trim()).apply()
    fun keyFor(pr: Provider) = p.getString("key_" + pr.name, "") ?: ""
    fun setKeyFor(pr: Provider, v: String) = p.edit().putString("key_" + pr.name, v.trim()).apply()
    /** ISO-639-1 code such as "en"; blank = detect automatically. */
    var language: String
        get() = p.getString("language", "") ?: ""
        set(v) = p.edit().putString("language", v.trim().lowercase()).apply()
}

/** The service asked us to slow down; [waitSeconds] is how long it wants. */
class RateLimitException(val waitSeconds: Long) :
    IOException("Your API account's usage limit was reached. Try again in a while.")

data class Segment(val start: Double, val end: Double, val text: String)

class Transcriber(private val settings: Settings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(audio: File): List<Segment> = withContext(Dispatchers.IO) {
        val provider = settings.provider
        val key = settings.apiKey
        if (key.isBlank()) throw IOException("Add your ${provider.name.lowercase().replaceFirstChar { it.uppercase() }} API key in Settings (⚙ top right).")

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply { if (settings.language.isNotBlank()) addFormDataPart("language", settings.language) }
            .addFormDataPart("file", audio.name, audio.asRequestBody("application/octet-stream".toMediaType()))
            .build()

        val req = Request.Builder()
            .url(provider.baseUrl + "/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 429) {
                val wait = resp.header("retry-after")?.toDoubleOrNull()?.toLong() ?: 60L
                throw RateLimitException(wait)
            }
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException(
                    when (resp.code) {
                        401 -> "The API key was rejected. Check it in Settings."
                        413 -> "The audio file is too large for the transcription service."
                        else -> "Transcription failed (${resp.code}). ${msg ?: text.take(200)}"
                    }
                )
            }
            parse(text)
        }
    }

    private fun parse(json: String): List<Segment> {
        val o = JSONObject(json)
        val arr = o.optJSONArray("segments")
        if (arr == null || arr.length() == 0) {
            val t = o.optString("text").trim()
            return if (t.isEmpty()) emptyList() else listOf(Segment(0.0, 0.0, t))
        }
        return (0 until arr.length()).map {
            val s = arr.getJSONObject(it)
            Segment(s.optDouble("start", 0.0), s.optDouble("end", 0.0), s.optString("text"))
        }
    }
}
