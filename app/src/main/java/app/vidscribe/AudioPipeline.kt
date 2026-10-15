package app.vidscribe

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Audio ready to send for transcription. [kbps] is set when the app made the MP3 itself
 * (constant bitrate), which lets long recordings be split into parts.
 */
data class AudioResult(val file: File, val title: String, val source: String, val kbps: Int? = null)

/** Thrown when the link itself can't be fetched — the UI then asks the user to download the video. */
class LinkFailedException(val detail: String) : Exception(detail)

typealias Progress = (stage: String, fraction: Float?) -> Unit

/**
 * Gets audio out of a link (any site yt-dlp supports — well over a thousand) or a local file,
 * squeezed to small mono speech-quality MP3 so it fits the transcription API's 25 MB limit.
 */
class AudioPipeline(private val ctx: Context) {

    companion object {
        const val PROCESS_ID = "vidscribe-job"
        /** Transcription APIs accept up to 25 MB; keep a safety margin. */
        const val MAX_BYTES = 24L * 1024 * 1024
        private val API_FORMATS = setOf("mp3", "mp4", "mpeg", "mpga", "m4a", "wav", "webm", "ogg", "flac")
        private val initLock = Mutex()
        @Volatile private var ready = false
    }

    private suspend fun ensureReady() = initLock.withLock {
        if (!ready) {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
            ready = true
        }
    }

    /** Websites change often; refresh yt-dlp at most once a day. Failures are harmless. */
    private fun maybeUpdateDownloader() {
        val prefs = ctx.getSharedPreferences("pipeline", Context.MODE_PRIVATE)
        val last = prefs.getLong("ytdlp_updated", 0)
        if (System.currentTimeMillis() - last < 24 * 3600 * 1000L) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx)
            prefs.edit().putLong("ytdlp_updated", System.currentTimeMillis()).apply()
        } catch (_: Exception) { }
    }

    private fun newWorkDir(): File {
        val root = File(ctx.cacheDir, "work")
        root.deleteRecursively()
        return File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
    }

    /**
     * Highest speech bitrate (kbps) that fits in one upload. Longer recordings use 24 kbps
     * and are split into parts before transcription.
     */
    private fun pickBitrate(durationSec: Double?): Int {
        if (durationSec == null || durationSec <= 0) return 24
        for (kbps in listOf(48, 32)) {
            if (durationSec * kbps * 1000 / 8 * 1.05 < MAX_BYTES) return kbps
        }
        return 24
    }

    // ---------------------------------------------------------------- links

    /**
     * [titleOverride], [sourceOverride] and [durationHint] are used for podcast episodes,
     * where the feed already tells us the name and length.
     */
    suspend fun fromUrl(
        url: String,
        progress: Progress,
        titleOverride: String? = null,
        sourceOverride: String? = null,
        durationHint: Double? = null,
    ): AudioResult = withContext(Dispatchers.IO) {
        progress("Preparing…", null)
        ensureReady()
        progress("Checking for downloader updates…", null)
        maybeUpdateDownloader()
        val dir = newWorkDir()
        val ytdl = YoutubeDL.getInstance()

        // 1. Look up title and length (no download yet).
        progress("Opening link…", null)
        val info = try {
            ytdl.execute(YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--skip-download")
                addOption("--print", "TITLE:%(title)s")
                addOption("--print", "DUR:%(duration)s")
            }, PROCESS_ID).out
        } catch (e: Exception) {
            throw LinkFailedException(cleanError(e))
        }
        val title = info.lineSequence().firstOrNull { it.startsWith("TITLE:") }
            ?.removePrefix("TITLE:")?.trim()?.takeIf { it.isNotEmpty() && it != "NA" } ?: "Video transcript"
        val duration = info.lineSequence().firstOrNull { it.startsWith("DUR:") }
            ?.removePrefix("DUR:")?.trim()?.toDoubleOrNull()
        val kbps = pickBitrate(duration ?: durationHint)

        // 2. Download only the audio and convert it to compact speech MP3.
        progress("Downloading audio…", 0f)
        try {
            ytdl.execute(YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-f", "bestaudio/best")
                addSpeechMp3Options(kbps)
                addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
            }, PROCESS_ID) { pct, _, line ->
                if (line.contains("[ExtractAudio]")) progress("Extracting audio…", null)
                else if (pct >= 0) progress("Downloading audio…", (pct / 100f).coerceIn(0f, 1f))
            }
        } catch (e: Exception) {
            throw LinkFailedException(cleanError(e))
        }
        val audio = findOutput(dir, "audio") ?: throw LinkFailedException("No audio was found at that link.")
        AudioResult(audio, titleOverride ?: title, sourceOverride ?: url, kbps)
    }

    // ---------------------------------------------------------------- local files

    suspend fun fromFile(uri: Uri, progress: Progress): AudioResult = withContext(Dispatchers.IO) {
        progress("Preparing…", null)
        ensureReady()
        val dir = newWorkDir()
        val (name, size) = queryNameAndSize(uri)
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "mp4" }
        val title = name.substringBeforeLast('.').ifBlank { "Transcript" }

        // Copy into app storage (yt-dlp/ffmpeg need a real file path).
        val src = File(dir, "source.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            src.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    progress("Reading file…", if (size > 0) (copied.toFloat() / size).coerceIn(0f, 1f) else null)
                }
            }
        } ?: throw IOException("Couldn't open that file.")

        // Small enough and in a format the API accepts? Send it as it is.
        if (ext in API_FORMATS && src.length() <= MAX_BYTES) return@withContext AudioResult(src, title, name)

        val kbps = pickBitrate(durationOf(src))
        progress("Extracting audio…", null)
        try {
            YoutubeDL.getInstance().execute(YoutubeDLRequest("file://" + src.absolutePath).apply {
                addOption("--enable-file-urls")
                addSpeechMp3Options(kbps)
                addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
            }, PROCESS_ID)
        } catch (e: Exception) {
            throw IOException("Couldn't extract audio from that file. ${cleanError(e)}")
        }
        val audio = findOutput(dir, "audio") ?: throw IOException("Couldn't extract audio from that file.")
        AudioResult(audio, title, name, kbps)
    }

    // ---------------------------------------------------------------- helpers

    /** Mono, 16 kHz MP3 at a low bitrate — plenty for speech, tiny files. */
    private fun YoutubeDLRequest.addSpeechMp3Options(kbps: Int) {
        addOption("-x")
        addOption("--audio-format", "mp3")
        addOption("--audio-quality", "${kbps}K")
        addOption("--postprocessor-args", "ExtractAudio:-ac 1 -ar 16000")
        addOption("--no-mtime")
    }

    private fun findOutput(dir: File, stem: String): File? =
        dir.listFiles()
            ?.filter { it.name.startsWith("$stem.") && it.extension == "mp3" && it.length() > 0 }
            ?.maxByOrNull { it.length() }

    private fun durationOf(file: File): Double? = try {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            release()
            ms?.div(1000.0)
        }
    } catch (_: Exception) { null }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) c.getString(n)?.let { name = it }
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (_: Exception) { }
        return name to size
    }

    /** Turns yt-dlp's verbose stderr into one readable line. */
    private fun cleanError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        val line = msg.lines().lastOrNull { it.contains("ERROR:") } ?: msg.lines().lastOrNull { it.isNotBlank() } ?: msg
        return line.substringAfter("ERROR:").trim().take(300)
    }
}
